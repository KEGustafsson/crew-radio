// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * One node on the crew channel, as a phone is: a random sender id, a hello every second, a
 * roster kept from everyone else's hellos and audio, replays and duplicates dropped by
 * (sender, seq) the way the app does it (lib/replay.js), and the ability to key the channel
 * with 20 ms PCM frames, paced in real time so the phones' jitter queues see a talker, not a
 * burst.
 *
 * The link is anything with `send(buf)` and a 'packet' event (see lan.js). Events out:
 * 'roster' (when the rendered list changes), 'talking' (someone else started or stopped),
 * 'speaking' (our own announcement starts or ends), 'stale' (packets from a clock more than
 * a minute off ours, at most once per 30 s).
 */

const crypto = require("node:crypto");
const { EventEmitter } = require("node:events");
const P = require("./packet");
const { ReplayGuard } = require("./replay");
const { WireLimiter } = require("./wirelimit");

const FRAME_BYTES = 640; // 16 kHz * 20 ms * 2 bytes
const FRAME_MS = 20;
const LEAD_MS = 100;       // how far ahead of real time frames may go out (the phones buffer up to 200 ms)
const MAX_NODES = 64;      // far more than a crew; a ceiling, not a target (the app's MAX_NODES)
const TALK_HOLD_MS = 400;  // how long after the last frame a node still shows as talking (the app's TALK_HOLD_MS)
const STALE_REPORT_MS = 30_000;

class ChannelNode extends EventEmitter {
  /**
   * @param {object} opts
   * @param {string} opts.name        shown on the phones' roster (32 UTF-8 bytes at most)
   * @param {import('./crypto').ChannelCrypto} opts.crypto
   * @param {{send(buf:Buffer):boolean, on(ev:string, fn:Function):any}} opts.link
   * @param {number} [opts.ttl=4]     hop budget stamped on our packets
   * @param {number} [opts.heartbeatMs=1000]
   * @param {number} [opts.silenceMs=4000]  a node silent this long is dropped from the roster
   * @param {number} [opts.talkingMs=400]   audio within this long ago means "talking"
   * @param {ReplayGuard} [opts.guard]      the replay state; pass one guard across link reopens
   * @param {() => number} [opts.now]       milliseconds; also the clock the packets' `time` is checked against
   */
  constructor(opts) {
    super();
    this.name = opts.name;
    this.crypto = opts.crypto;
    this.link = opts.link;
    this.ttl = opts.ttl ?? 4;
    this.heartbeatMs = opts.heartbeatMs ?? 1000;
    this.silenceMs = opts.silenceMs ?? 4000;
    this.talkingMs = opts.talkingMs ?? TALK_HOLD_MS;
    this.leadMs = Number.isFinite(opts.leadMs) && opts.leadMs >= 0 ? opts.leadMs : LEAD_MS;   // how far ahead of real time frames go out
    this.repeatMs = opts.repeatMs ?? 0;         // > 0: send every audio packet a second time this much later (heals a lost copy)
    this.now = opts.now ?? Date.now;
    this.guard = opts.guard ?? new ReplayGuard();
    this.limiter = opts.limiter ?? new WireLimiter({ now: () => this.now() });
    this.senderId = randomSenderId();
    this.audioSeq = 0;
    this.helloSeq = 0;
    this.nodes = new Map(); // senderId -> {name, transports, hops, versionCode, lastSeen, lastAudio, address}
    this.timer = null;
    this.speaking = null; // {cancel, done} of the announcement going out right now
    this.chain = Promise.resolve(); // announcements go out one after another, in call order
    this.lastRosterKey = "";
    this.stats = { rx: 0, rejected: 0, stale: 0, late: 0, tx: 0 };
    this.staleReportedAt = 0;
    this.staleSinceReport = 0;
    this.onPacket = (buf, rinfo) => this.receive(buf, rinfo);
  }

  start() {
    this.link.on("packet", this.onPacket);
    this.tick();
    this.timer = setInterval(() => this.tick(), this.heartbeatMs);
    if (this.timer.unref) this.timer.unref();
  }

  /** Leaves the channel. The replay guard is the owner's and keeps its memory. */
  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.cancel();
    if (typeof this.link.off === "function") this.link.off("packet", this.onPacket);
    this.nodes.clear();
  }

  /** Hello out, stale nodes swept, roster republished if it changed. */
  tick() {
    this.broadcast(P.Codec.HELLO, P.encodeHello({ name: this.name, transports: P.Transports.LAN, ttl: this.ttl, versionCode: 0 }));
    const now = this.now();
    let changed = false;
    for (const [id, n] of this.nodes) {
      if (now - n.lastSeen > this.silenceMs) {
        this.nodes.delete(id);
        changed = true;
      }
    }
    this.publishRoster(changed);
  }

  /** The roster as the phones would list it. */
  roster() {
    const now = this.now();
    return [...this.nodes.entries()]
      .map(([id, n]) => ({
        id,
        name: n.name ?? `#${(id >>> 0).toString(16)}`,
        transports: n.transports,
        hops: n.hops,
        versionCode: n.versionCode,
        talking: now - n.lastAudio < this.talkingMs,
        ageMs: now - n.lastSeen,
      }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  /** True while any other node is sending audio. */
  get someoneTalking() {
    const now = this.now();
    for (const n of this.nodes.values()) if (now - n.lastAudio < this.talkingMs) return true;
    return false;
  }

  /** Resolves when nobody has talked for `gapMs`, or after `maxWaitMs` regardless. */
  waitForSilence(gapMs = 300, maxWaitMs = 3000) {
    const deadline = this.now() + maxWaitMs;
    return new Promise((resolve) => {
      const check = () => {
        const now = this.now();
        let last = 0;
        for (const n of this.nodes.values()) last = Math.max(last, n.lastAudio);
        if (last === 0 || now - last >= gapMs) resolve(true);      // nobody has talked, or not lately
        else if (now >= deadline) resolve(false);
        else setTimeout(check, 50);
      };
      check();
    });
  }

  /**
   * Keys the channel with 16 kHz mono PCM16 (a Buffer of little-endian bytes), one frame every
   * 20 ms. Resolves when the last frame has gone out. Calls queue behind each other in call
   * order (a promise chain, so two waiters can never both start). cancel() stops the current one.
   */
  speak(pcmBytes) {
    const turn = this.chain.then(() => this.sendFrames(pcmBytes));
    this.chain = turn.catch(() => {});
    return turn;
  }

  async sendFrames(pcmBytes) {
    const frames = [];
    for (let off = 0; off < pcmBytes.length; off += FRAME_BYTES) {
      const f = Buffer.alloc(FRAME_BYTES); // the last frame is padded with silence
      pcmBytes.copy(f, 0, off, Math.min(off + FRAME_BYTES, pcmBytes.length));
      frames.push(f);
    }
    if (frames.length === 0) return;
    let cancelled = false;
    let resolveDone;
    const done = new Promise((r) => (resolveDone = r));
    this.speaking = { cancel: () => (cancelled = true), done };
    this.emit("speaking", true);
    try {
      // Frames go out LEAD_MS ahead of real time. Node's timers are coarse on some hosts
      // (Windows: 15.6 ms ticks, so gaps of 15 or 31 ms instead of 20), and a frame that
      // arrives late makes the phones' mixer run dry and conceal, which sounds like warble;
      // early frames only sit in their jitter queue. The schedule is drift-corrected, so the
      // cushion never shrinks over a long announcement.
      const t0 = this.now();
      for (let i = 0; i < frames.length && !cancelled; i++) {
        const packet = this.broadcast(P.Codec.PCM, frames[i]);
        if (this.repeatMs > 0) {
          // The same packet again a little later: a copy lost on the air is replaced before its
          // playback slot, and the phones drop the second copy by (sender, seq) when both arrive.
          const t = setTimeout(() => this.link.send(packet, this.unicastTargets()), this.repeatMs);
          if (t.unref) t.unref();
        }
        // A datagram leaves only on the next turn of the event loop (the address lookup completes
        // on a tick), so yield before any blocking wait or the whole announcement goes out in one burst.
        await nextTurn();
        const due = t0 + (i + 1) * FRAME_MS - this.leadMs;
        const wait = due - this.now();
        if (wait > 0) await sleep(wait);
      }
    } finally {
      this.speaking = null;
      this.emit("speaking", false);
      resolveDone();
    }
  }

  cancel() {
    this.speaking?.cancel();
  }

  /** Stamps (with the clock, in seconds), seals and sends one of our own packets. */
  broadcast(codec, payload) {
    const seq = codec === P.Codec.HELLO ? this.helloSeq++ : this.audioSeq++;
    const header = P.encodeHeader({ senderId: this.senderId, seq, codec, ttl: this.ttl, time: Math.floor(this.now() / 1000) });
    const packet = Buffer.concat([header, this.crypto.seal(P.aadOf(header), payload)]);
    this.stats.tx++;
    this.link.send(packet, this.unicastTargets());
    return packet;
  }

  /** The addresses the known nodes were last heard from, for unicast copies (see lan.js). */
  unicastTargets() {
    const out = new Set();
    for (const n of this.nodes.values()) if (n.address) out.add(n.address);
    return [...out];
  }

  /**
   * The receive path, in the app's order: parse, drop our own, authenticate, drop a stale
   * clock, then the replay guard (seen or late), and only then the roster or talking.
   */
  receive(buf, rinfo) {
    // Nothing below may throw into the dgram callback: that is the top of a libuv tick, so an
    // uncaught exception there is the default-exit kind, and this plugin runs inside the boat's
    // Signal K server. The app keeps the same rule for its transport threads.
    try {
      this.receiveOrThrow(buf, rinfo);
    } catch (e) {
      this.stats.rejected++;
      this.emit("fault", e);
    }
  }

  receiveOrThrow(buf, rinfo) {
    const h = P.parseHeader(buf);
    if (!h) { this.stats.rejected++; return; }
    // The global budget first, before the packet is opened: the sender id it claims is chosen by
    // whoever sent it, so nothing is charged to a sender yet - and our own id is in the clear in
    // every packet we send, so dropping our echo before this would be a way in that costs nothing.
    if (!this.limiter.allowGlobal()) { this.stats.rejected++; return; }
    if (h.senderId === this.senderId) return;
    // Authenticate first, then dedupe, as the app does: a forged header must not be able to
    // occupy a (sender, seq) slot and get the authentic packet dropped as its duplicate, nor
    // touch the stale counter.
    const plain = this.crypto.open(P.aadOf(buf), buf.subarray(P.HEADER));
    if (!plain) { this.stats.rejected++; this.limiter.allowJunk(); return; }
    const now = this.now();
    if (!P.isFresh(h.time, Math.floor(now / 1000))) { this.stale(now); return; }
    const verdict = this.guard.admit(h.senderId, h.seq, h.codec === P.Codec.HELLO ? "hello" : "audio");
    if (verdict === "seen") return;                  // the broadcast twin, or a relay echo
    if (verdict === "late") { this.stats.late++; return; }
    // Charged after the AEAD and after the duplicate look, so only authenticated first copies
    // cost a sender anything: the two WLAN copies of every frame would otherwise spend a
    // talker's budget in seconds, which is what the app measured in the field.
    if (!this.limiter.allowSender(h.senderId)) { this.stats.rejected++; return; }
    let n = this.nodes.get(h.senderId);
    const fresh = !n;
    if (fresh) {
      if (this.nodes.size >= MAX_NODES) return;
      n = { name: null, transports: 0, hops: 0, versionCode: 0, lastSeen: now, lastAudio: 0, address: null };
      this.nodes.set(h.senderId, n);
    }
    this.stats.rx++;
    n.lastSeen = now;
    // Where a unicast copy reaches this node: only from packets that came straight from it (a
    // relayed packet arrives from the relaying node's address, which would get the copies
    // instead) and, by the guard above, only from a packet that advanced its sequence, so a
    // replayed one cannot re-point the copies. The address itself is not authenticated - the AEAD
    // covers the header and the payload, not the IP source - so the link drops any copy that
    // would leave our own subnet (LanLink.onSubnet); the group and the broadcast go out anyway.
    if (rinfo && rinfo.address && h.hops - h.ttl === 0) n.address = rinfo.address;
    if (h.codec === P.Codec.HELLO) {
      const hello = P.decodeHello(plain);
      if (hello) {
        const hops = Math.max(0, hello.ttl - h.ttl);
        if (hello.name !== n.name || hello.transports !== n.transports || hops !== n.hops || hello.versionCode !== n.versionCode) {
          n.name = hello.name;
          n.transports = hello.transports;
          n.hops = hops;
          n.versionCode = hello.versionCode;
          this.publishRoster(true);
          return;
        }
      }
    } else {
      const was = now - n.lastAudio < this.talkingMs;
      n.lastAudio = now;
      if (!was) {
        this.emit("talking", true, h.senderId);
        this.publishRoster(true);
        return;
      }
    }
    this.publishRoster(fresh);
  }

  /** Counts a stale packet and reports the count at most once per 30 s. */
  stale(now) {
    this.stats.stale++;
    this.staleSinceReport++;
    if (now - this.staleReportedAt >= STALE_REPORT_MS) {
      this.staleReportedAt = now;
      const count = this.staleSinceReport;
      this.staleSinceReport = 0;
      this.emit("stale", count);
    }
  }

  publishRoster(force) {
    const r = this.roster();
    const key = r.map((n) => `${n.id}|${n.name}|${n.transports}|${n.hops}|${n.versionCode}|${n.talking ? 1 : 0}`).join("\n");
    if (force || key !== this.lastRosterKey) {
      this.lastRosterKey = key;
      this.emit("roster", r);
    }
  }
}

function randomSenderId() {
  for (;;) {
    const id = crypto.randomBytes(4).readInt32BE(0);
    if (id !== 0) return id;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const nextTurn = () => new Promise((r) => setImmediate(r));

module.exports = { ChannelNode, FRAME_BYTES, FRAME_MS, LEAD_MS, MAX_NODES, TALK_HOLD_MS, STALE_REPORT_MS };
