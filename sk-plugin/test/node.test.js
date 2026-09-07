// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { ChannelNode, FRAME_BYTES, LEAD_MS, MAX_NODES, TALK_HOLD_MS, STALE_REPORT_MS } = require("../lib/node");
const { ChannelCrypto } = require("../lib/crypto");
const { ReplayGuard } = require("../lib/replay");
const P = require("../lib/packet");

/** A shared medium: every node's send is every other node's 'packet', twice (multicast + broadcast). */
class Medium {
  constructor() { this.links = []; }
  link() {
    const l = new EventEmitter();
    l.send = (buf) => {
      for (const other of this.links) for (let i = 0; i < 2; i++) setImmediate(() => other.emit("packet", Buffer.from(buf)));
      return true;
    };
    this.links.push(l);
    return l;
  }
}

const crypto = ChannelCrypto.forChannelKeySync("north-star-2026");
const tick = (ms = 5) => new Promise((r) => setTimeout(r, ms));
/** Polls until `cond()` holds (the test files run in parallel, so fixed waits are flaky). */
async function until(cond, timeoutMs = 2000) {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > timeoutMs) throw new Error("condition not met in time");
    await tick(5);
  }
}
/** A sealed packet as a phone would send it; `time` in seconds defaults to the clock now. */
function packet({ senderId, seq, codec = P.Codec.HELLO, ttl = 4, hops = ttl, time, payload, name = `n${senderId}` }) {
  const h = P.encodeHeader({ senderId, seq, codec, ttl, hops, ...(time !== undefined ? { time } : {}) });
  const plain = payload ?? (codec === P.Codec.HELLO ? P.encodeHello({ name, transports: 1, ttl: hops, versionCode: 7 }) : Buffer.alloc(FRAME_BYTES, 1));
  return Buffer.concat([h, crypto.seal(P.aadOf(h), plain)]);
}

test("two nodes see each other by hello, duplicates are dropped, and silence drops a node", async () => {
  const m = new Medium();
  let clock = 1_000_000;
  const now = () => clock;
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000, silenceMs: 4000, now });
  const b = new ChannelNode({ name: "Anna", crypto, link: m.link(), heartbeatMs: 100000, silenceMs: 4000, now });
  a.start(); b.start();
  await until(() => a.roster().length === 1 && b.roster().length === 1);
  await tick(20); // the broadcast twins arrive too
  assert.deepEqual(a.roster().map((n) => [n.name, n.versionCode]), [["Anna", 0]], "the plugin's build is 0");
  assert.deepEqual(b.roster().map((n) => n.name), ["Boat"]);
  assert.equal(a.stats.rx, 1, "the broadcast twin was dropped as a duplicate");
  assert.equal(a.stats.stale, 0);
  clock += 5000;
  a.tick();
  assert.deepEqual(a.roster(), [], "Anna silent for 5 s is gone");
  a.stop(); b.stop();
});

test("a node with a different channel key is rejected, not listed", async () => {
  const m = new Medium();
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000 });
  const x = new ChannelNode({ name: "Intruder", crypto: ChannelCrypto.forChannelKeySync("wrong"), link: m.link(), heartbeatMs: 100000 });
  a.start(); x.start();
  await until(() => a.stats.rejected >= 2);
  assert.deepEqual(a.roster(), []);
  assert.equal(a.stats.rejected, 2, "both copies fail authentication; a forged header cannot occupy a seen-slot");
  a.stop(); x.stop();
});

test("a packet from a clock more than 60 s off is stale: dropped before any cache, counted, reported once per 30 s", async () => {
  const link = new EventEmitter();
  link.send = () => true;
  let clock = 2_000_000_000;   // ms
  const a = new ChannelNode({ name: "Boat", crypto, link, heartbeatMs: 100000, now: () => clock });
  const reports = [];
  a.on("stale", (n) => reports.push(n));
  a.start();
  const nowS = Math.floor(clock / 1000);
  link.emit("packet", packet({ senderId: 1, seq: 0, time: nowS - 61 }), { address: "10.0.0.1" });
  link.emit("packet", packet({ senderId: 1, seq: 1, time: nowS + 61 }), { address: "10.0.0.1" });
  assert.equal(a.stats.stale, 2);
  assert.equal(a.stats.rx, 0);
  assert.deepEqual(a.roster(), [], "not listed");
  assert.deepEqual(reports, [1], "reported at once, then held for 30 s");
  assert.equal(a.guard.admit(1, 0, "hello"), "new", "the stale packets touched no cache");
  link.emit("packet", packet({ senderId: 1, seq: 2, time: nowS - 60 }), { address: "10.0.0.1" });
  assert.equal(a.stats.rx, 1, "60 s off is still inside the window");
  clock += STALE_REPORT_MS;
  link.emit("packet", packet({ senderId: 1, seq: 3, time: nowS - 100 }), { address: "10.0.0.1" });
  assert.deepEqual(reports, [1, 2], "the next report carries what came in between");
  const forged = packet({ senderId: 2, seq: 0, time: nowS - 100 }); forged[forged.length - 1] ^= 1;
  link.emit("packet", forged, { address: "10.0.0.2" });
  assert.equal(a.stats.stale, 3, "an unauthenticated packet cannot inflate the stale count");
  assert.equal(a.stats.rejected, 1);
  a.stop();
});

test("a replayed authentic packet is dropped: seen when cached, late once the sender moved on, even after a reconnect", async () => {
  const link = new EventEmitter();
  link.send = () => true;
  const guard = new ReplayGuard();
  const a = new ChannelNode({ name: "Boat", crypto, link, heartbeatMs: 100000, guard });
  a.start();
  const talking = [];
  a.on("talking", (on, id) => talking.push([on, id]));
  const first = packet({ senderId: 5, seq: 10, codec: P.Codec.PCM });
  link.emit("packet", first, { address: "10.0.0.5" });
  link.emit("packet", first, { address: "10.0.0.5" });
  link.emit("packet", packet({ senderId: 5, seq: 12, codec: P.Codec.PCM }), { address: "10.0.0.5" });
  link.emit("packet", packet({ senderId: 5, seq: 11, codec: P.Codec.PCM }), { address: "10.0.0.5" });
  assert.deepEqual({ rx: a.stats.rx, late: a.stats.late }, { rx: 2, late: 1 }, "11 arrived after 12: late, as the app's SeqTracker rules");
  assert.deepEqual(talking, [[true, 5]]);
  a.stop();
  const b = new ChannelNode({ name: "Boat", crypto, link, heartbeatMs: 100000, guard });   // the link reopened: same guard
  b.start();
  link.emit("packet", first, { address: "10.0.0.5" });
  assert.equal(b.stats.rx, 0, "the recording is still known");
  assert.deepEqual(b.roster(), []);
  link.emit("packet", packet({ senderId: 5, seq: 13, codec: P.Codec.PCM }), { address: "10.0.0.5" });
  assert.equal(b.stats.rx, 1);
  b.stop();
});

test("speaking sends 640-byte PCM frames at 20 ms, pads the last one, and the listener hears talking", async () => {
  const m = new Medium();
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000 });
  const b = new ChannelNode({ name: "Anna", crypto, link: m.link(), heartbeatMs: 100000 });
  const sent = [];
  const origSend = a.link.send;
  a.link.send = (buf) => { sent.push(Buffer.from(buf)); return origSend(buf); };
  a.start(); b.start();
  await until(() => a.roster().length === 1 && b.roster().length === 1);
  const talking = [];
  b.on("talking", (on) => talking.push(on));
  const t0 = Date.now();
  await a.speak(Buffer.alloc(FRAME_BYTES * 9 + 100, 7));
  const elapsed = Date.now() - t0;
  const frames = sent.map((p) => P.parseHeader(p)).filter((h) => h && h.codec === P.Codec.PCM);
  assert.equal(frames.length, 10);
  assert.deepEqual(frames.map((h) => h.seq), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
  const nowS = Date.now() / 1000;
  assert.ok(frames.every((h) => Math.abs(h.time - nowS) < 5), "stamped with the clock");
  // 10 frames = 200 ms of speech, minus the lead: paced, not a burst; a loaded runner may be slow, never fast
  assert.ok(elapsed >= 200 - LEAD_MS - 40, `paced with a lead: ${elapsed} ms`);
  assert.ok(elapsed < 3000, `not stuck: ${elapsed} ms`);
  const lastPlain = crypto.open(P.aadOf(sent.at(-1)), sent.at(-1).subarray(P.HEADER));
  assert.equal(lastPlain.length, FRAME_BYTES);
  assert.equal(lastPlain[99], 7);
  assert.equal(lastPlain[100], 0, "padded with silence");
  await until(() => talking.length > 0);
  assert.deepEqual(talking, [true]);
  assert.ok(b.roster()[0].talking);
  assert.equal(TALK_HOLD_MS, 400, "as the app's TALK_HOLD_MS");
  assert.equal(b.talkingMs, 400);
  a.stop(); b.stop();
});

test("waitForSilence: at once when nobody ever talked, after the gap when someone did, false at the deadline", async () => {
  const m = new Medium();
  let clock = 1000;
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000, now: () => clock });
  a.start();
  assert.equal(await a.waitForSilence(300, 1000), true);
  a.nodes.set(1, { name: "x", transports: 1, hops: 0, lastSeen: 1000, lastAudio: 900 }); // talked 100 ms ago
  let settled = false;
  const p = a.waitForSilence(300, 5000).then((v) => { settled = true; return v; });
  await tick(20);
  assert.equal(settled, false, "still inside the gap");
  clock = 1400; // 500 ms since the last audio
  assert.equal(await p, true);
  a.nodes.get(1).lastAudio = 1400;
  const late = a.waitForSilence(300, 200); // keeps talking: gives up at the deadline (1600)
  await tick(60);
  clock = 1650; // 250 ms since the audio: still inside the gap, but past the deadline
  assert.equal(await late, false);
  a.stop();
});

test("a node's address is learnt from its own packets, only when its sequence advances, and every packet out carries the unicast list", async () => {
  const link = new EventEmitter();
  const sent = [];
  link.send = (buf, unicast) => { sent.push(unicast ?? []); return true; };
  const a = new ChannelNode({ name: "Boat", crypto, link, heartbeatMs: 100000 });
  a.start();
  assert.deepEqual(sent.at(-1), [], "nobody known yet: multicast and broadcast only");
  const hello = (id, seq = 0, ttl = 4) => packet({ senderId: id, seq, ttl, hops: 4 });
  link.emit("packet", hello(1), { address: "192.168.0.30" });
  link.emit("packet", hello(2), { address: "192.168.0.35" });
  link.emit("packet", hello(3), { address: "192.168.0.35" });   // two nodes behind one address: one copy
  link.emit("packet", hello(4, 0, 3), { address: "192.168.0.99" });   // ttl decremented by a relay: not its own address
  a.tick();
  assert.deepEqual(sent.at(-1).sort(), ["192.168.0.30", "192.168.0.35"]);
  link.emit("packet", hello(1, 0), { address: "192.168.0.66" });   // the same hello again from elsewhere: a replay
  a.tick();
  assert.deepEqual(sent.at(-1).sort(), ["192.168.0.30", "192.168.0.35"], "a replay does not re-point the copies");
  link.emit("packet", hello(1, 1), { address: "192.168.0.31" });   // the node moved: its next hello says so
  a.tick();
  assert.deepEqual(sent.at(-1).sort(), ["192.168.0.31", "192.168.0.35"]);
  await a.speak(Buffer.alloc(FRAME_BYTES));
  assert.deepEqual(sent.at(-1).sort(), ["192.168.0.31", "192.168.0.35"], "audio frames too");
  a.stop();
});

test("the roster is capped at 64 nodes, as the app's", () => {
  const link = new EventEmitter();
  link.send = () => true;
  const a = new ChannelNode({ name: "Boat", crypto, link, heartbeatMs: 100000 });
  a.start();
  for (let id = 1; id <= MAX_NODES + 5; id++) link.emit("packet", packet({ senderId: id, seq: 0 }), { address: `10.0.${id >> 8}.${id & 255}` });
  assert.equal(MAX_NODES, 64);
  assert.equal(a.roster().length, 64);
  assert.equal(a.stats.rx, 64, "the ones over the cap are not counted as received");
  link.emit("packet", packet({ senderId: 1, seq: 1 }), { address: "10.0.0.1" });
  assert.equal(a.stats.rx, 65, "a known node still gets through");
  a.stop();
});

test("two announcements queued at once go out one after the other, never interleaved", async () => {
  const m = new Medium();
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000 });
  const order = [];
  const origSend = a.link.send;
  a.link.send = (buf) => {
    const h = P.parseHeader(buf);
    if (h && h.codec === P.Codec.PCM) order.push(crypto.open(P.aadOf(buf), buf.subarray(P.HEADER))[0]);
    return origSend(buf);
  };
  a.start();
  const first = a.speak(Buffer.alloc(FRAME_BYTES * 3, 1));
  const second = a.speak(Buffer.alloc(FRAME_BYTES * 3, 2));
  const third = a.speak(Buffer.alloc(FRAME_BYTES * 2, 3));
  await Promise.all([first, second, third]);
  assert.deepEqual(order, [1, 1, 1, 2, 2, 2, 3, 3]);
  a.stop();
});
