// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Text to speech inside the plugin: Flite (CMU's Festival-lite) compiled to WebAssembly, run
 * through Node's WASI on a worker thread (lib/tts-worker.js), so the server's main thread and
 * the plugin's frame timer never wait for it. English only; four voices built into the module,
 * "slt" (female) by default. Output is 16 kHz mono PCM16, the channel's own rate, so nothing is
 * resampled.
 *
 * This side keeps what must be shared: the byte-bounded cache (an alarm said every 30 s comes
 * from it) and the promise chain that serialises synthesis, one sentence at a time. The worker
 * is started on the first sentence, kept idle without holding the process open, and ended by
 * stop(); a worker that dies is replaced on the next sentence.
 *
 * Numbers and the units that appear in Signal K alarm texts are spelled out before synthesis,
 * so "25 m" is read as "25 metres" and "12.2 V" as "12.2 volts".
 */

const os = require("node:os");
const path = require("node:path");
const { Worker } = require("node:worker_threads");
const { parseWav, CHANNEL_RATE } = require("./wav");

const VOICES = Object.freeze(["slt", "kal16", "rms", "awb"]);
const MAX_TEXT = 500;
const SYNTHESIS_TIMEOUT_MS = 30_000;

class FliteTts {
  /**
   * @param {object} [opts]
   * @param {string} [opts.voice="slt"]
   * @param {number} [opts.rate=1]        speaking rate, 0.7 (slow) .. 1.3 (fast)
   * @param {string} [opts.tempDir]       scratch directory for the WAV round trip (default: the OS temp dir)
   * @param {number} [opts.cacheBytes]    how much rendered speech to keep (default 8 MiB, about 4 minutes)
   * @param {string} [opts.wasmPath]      override for tests
   * @param {number} [opts.timeoutMs]     a sentence taking longer than this ends the worker (default 30 s)
   */
  constructor(opts = {}) {
    this.voice = opts.voice ?? "slt";
    if (!VOICES.includes(this.voice)) throw new Error(`unknown Flite voice "${this.voice}" (one of ${VOICES.join(", ")})`);
    this.rate = clamp(Number(opts.rate ?? 1), 0.5, 2);
    this.tempDir = opts.tempDir ?? path.join(os.tmpdir(), "signalk-crewradio");
    this.cacheBytes = opts.cacheBytes ?? 8 * 1024 * 1024;
    this.wasmPath = opts.wasmPath ?? require.resolve("@echogarden/flite-wasi");
    this.timeoutMs = opts.timeoutMs ?? SYNTHESIS_TIMEOUT_MS;
    this.worker = null;
    this.pending = new Map(); // id -> {resolve, reject, timer}
    this.nextId = 1;
    this.cache = new Map(); // key -> Buffer, insertion ordered
    this.cacheSize = 0;
    this.chain = Promise.resolve();
    this.gen = 0;                                          // bumped by stop(): a sentence still queued behind others fails too
    this.stats = { synthesized: 0, cached: 0, ms: 0 };
  }

  /** The text as it will be spoken: trimmed, capped, numbers and units expanded. */
  static normalise(text) {
    let t = String(text ?? "").replace(/\s+/g, " ").trim();
    if (t.length > MAX_TEXT) t = t.slice(0, MAX_TEXT - 1) + ".";
    t = t.replace(/[0-9]+[.][0-9]{2,}/g, (m) => String(Math.round(Number(m) * 10) / 10));   // 69.771 -> 69.8: the rest is not worth hearing
    // units after a number (Signal K alarm texts: depth, wind, battery, temperature)
    t = t.replace(/(\d)\s*(km\/h|m\/s|nm|kn|kts|kt|km|m|ft|°C|°F|°|%|hPa|mbar|bar|kW|W|Ah|A|V|l|L|min|s|h)(?![A-Za-z])/g, (_, d, u) => `${d} ${UNITS[u] ?? u}`);
    t = t.replace(/(\d)\s*°\s*(?![CF])/g, "$1 degrees ");
    return t;
  }

  /** 16 kHz mono PCM16 for `text`; an empty text yields an empty buffer. */
  synthesize(text) {
    const spoken = FliteTts.normalise(text);
    if (!spoken) return Promise.resolve(Buffer.alloc(0));
    const key = `${this.voice}|${this.rate}|${spoken}`;
    const hit = this.cache.get(key);
    if (hit) {
      this.cache.delete(key); this.cache.set(key, hit);      // most recently used last
      this.stats.cached++;
      return Promise.resolve(hit);
    }
    const gen = this.gen;
    const turn = this.chain
      .then(() => {
        if (this.gen !== gen) throw new Error("speech engine stopped");   // stop() while this sentence waited its turn
        return this.run(spoken);
      })
      .then((pcm) => { this.remember(key, pcm); return pcm; });
    this.chain = turn.catch(() => {});
    return turn;
  }

  /** Ends the worker; anything in flight fails. A later synthesize() starts a new one. */
  stop() {
    const w = this.worker;
    this.worker = null;
    this.gen++;
    this.fail(new Error("speech engine stopped"));
    if (w) w.terminate().catch(() => {});
  }

  /** One sentence on the worker, timed. */
  run(text) {
    const t0 = Date.now();
    const worker = this.ensureWorker();
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Flite took more than ${this.timeoutMs} ms`));
        if (this.worker === worker) this.stop();             // a stuck instance is not reused
      }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      worker.ref();                                          // held only while a sentence is in flight
      worker.postMessage({ id, text, voice: this.voice, rate: this.rate, tempDir: this.tempDir, wasmPath: this.wasmPath });
    }).then((pcm) => {
      this.stats.synthesized++;
      this.stats.ms += Date.now() - t0;
      return pcm;
    });
  }

  ensureWorker() {
    if (this.worker) return this.worker;
    const worker = new Worker(path.join(__dirname, "tts-worker.js"));
    worker.on("message", (m) => {
      const p = this.pending.get(m.id);
      if (!p) return;
      this.pending.delete(m.id);
      clearTimeout(p.timer);
      if (this.pending.size === 0) worker.unref();
      if (m.error) p.reject(new Error(m.error));
      else p.resolve(Buffer.from(m.pcm));
    });
    const gone = (why) => {
      if (this.worker === worker) this.worker = null;
      this.fail(new Error(why));
    };
    worker.on("error", (e) => gone(`speech worker failed: ${e?.message ?? e}`));
    worker.on("exit", (code) => gone(`speech worker exited (${code})`));
    worker.unref();
    this.worker = worker;
    return worker;
  }

  /** Rejects everything in flight. */
  fail(err) {
    const waiting = [...this.pending.values()];
    this.pending.clear();
    for (const p of waiting) { clearTimeout(p.timer); p.reject(err); }
  }

  remember(key, pcm) {
    if (pcm.length > this.cacheBytes) return;
    const previous = this.cache.get(key);           // two misses for the same text before either finished
    if (previous) { this.cacheSize -= previous.length; this.cache.delete(key); }
    this.cache.set(key, pcm);
    this.cacheSize += pcm.length;
    while (this.cacheSize > this.cacheBytes && this.cache.size > 1) {
      const oldest = this.cache.keys().next().value;
      this.cacheSize -= this.cache.get(oldest).length;
      this.cache.delete(oldest);
    }
  }
}

const UNITS = {
  m: "metres", km: "kilometres", nm: "nautical miles", kn: "knots", kts: "knots", kt: "knots", ft: "feet",
  "km/h": "kilometres per hour", "m/s": "metres per second",
  "°C": "degrees", "°F": "degrees Fahrenheit", "°": "degrees", "%": "percent",
  hPa: "hectopascal", mbar: "millibar", bar: "bar", kW: "kilowatts", W: "watts", Ah: "amp hours", A: "amps", V: "volts",
  l: "litres", L: "litres", min: "minutes", s: "seconds", h: "hours",
};

const clamp = (v, lo, hi) => (Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : 1);

module.exports = { FliteTts, VOICES, CHANNEL_RATE, MAX_TEXT, SYNTHESIS_TIMEOUT_MS, parseWav };
