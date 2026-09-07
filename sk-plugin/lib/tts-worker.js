// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The worker thread behind FliteTts (lib/tts.js): Flite in WebAssembly through Node's WASI,
 * off the server's main thread, so a sentence's synthesis (tens of milliseconds on a laptop,
 * a second on a Pi) stalls neither Signal K nor the plugin's own 20 ms frame timer.
 *
 * Messages in: {id, text, voice, rate, tempDir, wasmPath}. Out: {id, pcm} (16 kHz mono PCM16,
 * the buffer transferred) or {id, error}. The module is compiled once per worker; each sentence
 * gets a fresh instance (Flite's C entry point is not re-entrant) and a WAV round trip through
 * the scratch directory. Requests are answered one at a time, in order.
 */

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { parentPort } = require("node:worker_threads");
const { WASI } = require("node:wasi");
const { resample, bytesToSamples, samplesToBytes } = require("./resample");
const { parseWav, CHANNEL_RATE } = require("./wav");

let compiled = null;   // wasmPath -> the compiled module (one path per worker in practice)
let compiledFor = null;

async function synthesize({ text, voice, rate, tempDir, wasmPath }) {
  if (!compiled || compiledFor !== wasmPath) {
    compiled = await WebAssembly.compile(fs.readFileSync(wasmPath));
    compiledFor = wasmPath;
  }
  fs.mkdirSync(tempDir, { recursive: true });
  const outName = `${crypto.randomBytes(8).toString("hex")}.wav`;
  const outPath = path.join(tempDir, outName);
  const devnull = fs.openSync(os.devNull, "w");
  try {
    const wasi = new WASI({
      version: "preview1",
      args: ["--", "-voice", voice, "--setf", `duration_stretch=${(1 / rate).toFixed(3)}`, ` ${text} `, outName],
      env: {},
      preopens: { ".": tempDir },
      stdout: devnull,
      stderr: devnull,
      returnOnExit: true,
    });
    const instance = await WebAssembly.instantiate(compiled, { wasi_snapshot_preview1: wasi.wasiImport });
    const exit = wasi.start(instance);
    if (exit !== 0) throw new Error(`Flite exited with ${exit}`);
    const { rate: wavRate, pcm } = parseWav(fs.readFileSync(outPath));
    return wavRate === CHANNEL_RATE ? pcm : samplesToBytes(resample(bytesToSamples(pcm), wavRate, CHANNEL_RATE));
  } finally {
    fs.closeSync(devnull);
    try { fs.unlinkSync(outPath); } catch { /* never written */ }
  }
}

let chain = Promise.resolve();
parentPort.on("message", (req) => {
  chain = chain.then(async () => {
    try {
      const pcm = await synthesize(req);
      // A dedicated ArrayBuffer, not `Buffer.from(pcm).buffer`: a Buffer that small comes out of
      // Node's shared pool, whose ArrayBuffer holds other buffers too and cannot be handed over
      // (Node 24 refuses it outright). Copy into memory of our own and transfer that.
      const out = new ArrayBuffer(pcm.length);
      new Uint8Array(out).set(pcm);
      parentPort.postMessage({ id: req.id, pcm: out }, [out]);
    } catch (e) {
      parentPort.postMessage({ id: req.id, error: e?.message ?? String(e) });
    }
  });
});
