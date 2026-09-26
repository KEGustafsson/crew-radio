// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const C = require("../lib/rns/crypto");
const P = require("../lib/rns/packet");
const I = require("../lib/rns/identity");
const L = require("../lib/rns/link");
const Carry = require("../lib/rns/carry");
const { ReticulumTransport, STALE_MS, CONFIRM_MS, LINK_TIMEOUT_MS, GRACE_MS, STABLE_MS, GATE_BURST } = require("../lib/rns/transport");
const { ChannelCrypto } = require("../lib/crypto");
const CP = require("../lib/packet");

const V = require("./rns.vector.json");
const hex = (s) => Buffer.from(s, "hex");
/** The key proof key of the tests' channel; a stranger has another. */
const CK = Buffer.alloc(32, 7);
const seq = (start, n) => Buffer.from(Array.from({ length: n }, (_, i) => (start + i) & 0xff));

// ---- the cross-language vector (checked against the reference implementation when it was made) ----

test("vector: the channel's tag, name hash, identity and destination", () => {
  assert.equal(ChannelCrypto.forChannelKeySync(V.channelKey).reticulumTag, V.reticulumTag);
  assert.equal(I.nameHash(V.destinationName).toString("hex"), V.nameHash);
  const id = new I.Identity(hex(V.identity.encPriv), hex(V.identity.sigSeed));
  assert.equal(id.publicKey.toString("hex"), V.identity.publicKey);
  assert.equal(id.hash.toString("hex"), V.identity.hash);
  assert.equal(I.destinationHash(hex(V.nameHash), id.hash).toString("hex"), V.announce.destination);
});

test("vector: the announce, byte for byte, framed, and it parses back", () => {
  const id = new I.Identity(hex(V.identity.encPriv), hex(V.identity.sigSeed));
  const a = I.buildAnnounce(id, hex(V.nameHash), Buffer.alloc(0), hex(V.announce.random), V.announce.time);
  const raw = P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data });
  assert.equal(raw.toString("hex"), V.announce.raw);
  assert.equal(P.frame(raw).toString("hex"), V.announce.framed);
  const parsed = I.parseAnnounce(P.decode(raw));
  assert.equal(parsed.destination.toString("hex"), V.announce.destination);
  assert.equal(parsed.emitted, V.announce.time);
});

test("vector: link request, link id, proof, derived key, token and RTT", () => {
  const id = new I.Identity(hex(V.identity.encPriv), hex(V.identity.sigSeed));
  const peer = { destination: hex(V.announce.destination), sigPub: id.sigPub };
  const req = L.requestLink(peer, hex(V.linkRequest.transportId), { encPriv: hex(V.linkRequest.encPriv), sigSeed: hex(V.linkRequest.sigSeed) });
  assert.equal(req.raw.toString("hex"), V.linkRequest.raw);
  assert.equal(req.id.toString("hex"), V.linkRequest.linkId);
  const acc = L.acceptLink(id, P.decode(req.raw), hex(V.linkProof.encPriv));
  assert.equal(acc.proof.toString("hex"), V.linkProof.raw);
  assert.equal(acc.link.key.toString("hex"), V.linkProof.key);
  const link = req.complete(P.decode(acc.proof));
  assert.equal(link.key.toString("hex"), V.linkProof.key);
  const token = C.tokenEncrypt(link.key, hex(V.token.plain), hex(V.token.iv));
  assert.equal(token.toString("hex"), V.token.token);
  assert.equal(P.encode({ packetType: P.PacketType.DATA, destType: P.DestType.LINK, destination: link.id, data: token }).toString("hex"), V.token.dataRaw);
  assert.equal(L.packRtt(V.rtt.seconds).toString("hex"), V.rtt.packed);
});

test("vector: the key proof of each end of a link", () => {
  const ck = ChannelCrypto.forChannelKeySync(V.channelKey).reticulumConfirmKey;
  assert.equal(ck.toString("hex"), V.keyProof.confirmKey);
  const id = hex(V.keyProof.linkId);
  assert.equal(Carry.keyProof(ck, id, true).toString("hex"), V.keyProof.initiator);
  assert.equal(Carry.keyProof(ck, id, false).toString("hex"), V.keyProof.responder);
  assert.ok(Carry.proofMatches(hex(V.keyProof.initiator), ck, id, true));
  assert.ok(!Carry.proofMatches(hex(V.keyProof.initiator), ck, id, false), "bound to the role");
  assert.ok(!Carry.proofMatches(hex(V.keyProof.initiator), ck, Buffer.alloc(16), true), "bound to the link");
  assert.ok(!Carry.isKeyProof(Buffer.alloc(33)) && !Carry.isKeyProof(hex(V.keyProof.initiator).subarray(1)));
});

test("vector: a PCM-sized packet is cut into the same parts", () => {
  assert.deepEqual(Carry.cut(seq(0, 686), V.carry.id).map((b) => b.toString("hex")), V.carry.parts);
});

// ---- primitives ----

test("a token opens only with its key and untouched, and never throws", () => {
  const key = seq(1, 64);
  const t = C.tokenEncrypt(key, Buffer.from("hello"));
  assert.equal(C.tokenDecrypt(key, t).toString(), "hello");
  for (const i of [0, 20, t.length - 1]) {
    const bad = Buffer.from(t);
    bad[i] ^= 1;
    assert.equal(C.tokenDecrypt(key, bad), null, `byte ${i}`);
  }
  assert.equal(C.tokenDecrypt(seq(2, 64), t), null);
  assert.equal(C.tokenDecrypt(key, t.subarray(0, 40)), null);
  assert.equal(C.tokenDecrypt(key, "nope"), null);
  assert.equal(C.ed25519Verify(Buffer.alloc(3), Buffer.alloc(1), Buffer.alloc(64)), false);
  assert.equal(C.ed25519Verify(Buffer.alloc(32, 0xff), Buffer.alloc(1), Buffer.alloc(64)), false);
});

test("packets: both header types round-trip, the hashable part ignores hops and the transport id", () => {
  const dest = seq(0x40, 16);
  const one = P.encode({ packetType: P.PacketType.LINKREQUEST, destType: P.DestType.SINGLE, destination: dest, data: seq(0, 64) });
  const two = P.encode({ packetType: P.PacketType.LINKREQUEST, destType: P.DestType.SINGLE, destination: dest, data: seq(0, 64), transportId: seq(0x80, 16), hops: 3 });
  const a = P.decode(one);
  const b = P.decode(two);
  assert.equal(a.headerType, P.HeaderType.ONE);
  assert.equal(b.headerType, P.HeaderType.TWO);
  assert.equal(b.propagation, P.Propagation.TRANSPORT);
  assert.deepEqual(b.transportId, seq(0x80, 16));
  assert.equal(b.hops, 3);
  assert.deepEqual(a.destination, b.destination);
  assert.deepEqual(P.hashablePart(one), P.hashablePart(two));
  assert.deepEqual(P.packetHash(one), P.packetHash(two));
  assert.equal(P.decode(Buffer.alloc(5)), null);
  const ifac = Buffer.from(one);
  ifac[0] |= 0x80;
  assert.equal(P.decode(ifac), null, "an interface access code is not ours to check");
  assert.equal(P.decode(Buffer.alloc(P.MTU + 1)), null);
  const shortTwo = Buffer.from(two.subarray(0, 20));
  assert.equal(P.decode(shortTwo), null);
});

test("HDLC: flags and escapes survive, junk before a frame and oversized frames are dropped", () => {
  const got = [];
  const d = new P.Deframer((f) => got.push(f), 32);
  const packet = Buffer.from([1, 0x7e, 2, 0x7d, 3]);
  const framed = P.frame(packet);
  assert.deepEqual(framed, Buffer.from([0x7e, 1, 0x7d, 0x5e, 2, 0x7d, 0x5d, 3, 0x7e]));
  d.push(Buffer.from([9, 9, 9]));                            // before any flag
  d.push(framed.subarray(0, 4));
  d.push(framed.subarray(4));
  d.push(P.frame(Buffer.alloc(100, 1)));                     // too long for this deframer
  d.push(P.frame(Buffer.from([5])));
  assert.deepEqual(got, [packet, Buffer.from([5])]);
});

test("announces: a tampered one, one for another key or with a ratchet parse as they should", () => {
  const id = I.Identity.generate();
  const nh = I.nameHash("crewradio.channel.test");
  const a = I.buildAnnounce(id, nh, Buffer.from("app"));
  const raw = P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data });
  const ok = I.parseAnnounce(P.decode(raw));
  assert.equal(ok.appData.toString(), "app");
  assert.deepEqual(ok.identityHash, id.hash);
  const bad = Buffer.from(raw);
  bad[bad.length - 1] ^= 1;
  assert.equal(I.parseAnnounce(P.decode(bad)), null, "app data is signed");
  const otherDest = P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: seq(0, 16), data: a.data });
  assert.equal(I.parseAnnounce(P.decode(otherDest)), null, "the destination must follow from the key");
  assert.equal(I.parseAnnounce({ ...P.decode(raw), data: a.data.subarray(0, 100) }), null);
  // With a ratchet: the context flag says 32 more bytes sit before the signature, and they are signed.
  const ratchet = seq(0x33, 32);
  const randomHash = a.data.subarray(74, 84);
  const sig = id.sign(Buffer.concat([a.destination, id.publicKey, nh, randomHash, ratchet]));
  const data = Buffer.concat([id.publicKey, nh, randomHash, ratchet, sig]);
  const withRatchet = P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data, contextFlag: true });
  assert.ok(I.parseAnnounce(P.decode(withRatchet)));
  assert.equal(I.plainHash("rnstransport.path.request").length, 16);
});

test("links: a proof from anyone but the destination is refused; data, keep-alive and close work both ways", () => {
  const dest = I.Identity.generate();
  const impostor = I.Identity.generate();
  const peer = { destination: seq(0, 16), sigPub: dest.sigPub };
  const req = L.requestLink(peer);
  const request = P.decode(req.raw);
  assert.equal(L.acceptLink(dest, { ...request, packetType: P.PacketType.DATA }), null);
  assert.equal(L.acceptLink(dest, { ...request, data: request.data.subarray(0, 10) }), null);
  assert.equal(req.complete(P.decode(L.acceptLink(impostor, request).proof)), null, "signed by someone else");
  const acc = L.acceptLink(dest, request);
  const proof = P.decode(acc.proof);
  assert.equal(req.complete({ ...proof, destination: seq(9, 16) }), null);
  assert.equal(req.complete({ ...proof, data: proof.data.subarray(0, 50) }), null);
  const mine = req.complete(proof);
  const theirs = acc.link;
  assert.equal(theirs.active, false, "the responder waits for the RTT");
  assert.equal(theirs.handle(P.decode(mine.rttPacket(0.1))).kind, "rtt");
  assert.equal(theirs.active, true);
  const d = theirs.handle(P.decode(mine.dataPacket(Buffer.from("over"))));
  assert.equal(d.kind, "data");
  assert.equal(d.plain.toString(), "over");
  assert.equal(mine.handle(P.decode(theirs.dataPacket(Buffer.from("back")))).plain.toString(), "back");
  const ka = theirs.handle(P.decode(mine.keepalivePacket()));
  assert.equal(ka.kind, "keepalive");
  assert.equal(mine.handle(P.decode(ka.reply)).reply, null, "an answer is not answered");
  assert.equal(theirs.handle({ ...P.decode(mine.keepalivePacket()), data: Buffer.alloc(2) }), null);
  assert.throws(() => mine.dataPacket(Buffer.alloc(L.MDU + 1)));
  assert.equal(mine.handle({ ...P.decode(theirs.dataPacket(Buffer.from("x"))), context: 0x09 }), null, "requests are not ours");
  const forged = P.decode(theirs.dataPacket(Buffer.from("x")));
  forged.data[5] ^= 1;
  assert.equal(mine.handle(forged), null);
  const closeWrongId = P.encode({ packetType: P.PacketType.DATA, destType: P.DestType.LINK, destination: mine.id, context: P.Context.LINKCLOSE, data: mine.encrypt(seq(0, 16)) });
  assert.equal(theirs.handle(P.decode(closeWrongId)), null);
  assert.equal(theirs.handle(P.decode(mine.closePacket())).kind, "close");
  assert.equal(theirs.closed, true);
  assert.equal(theirs.handle(P.decode(mine.dataPacket(Buffer.from("late")))), null);
  assert.equal(mine.handle({ ...P.decode(mine.closePacket()), packetType: P.PacketType.PROOF }), null);
  assert.ok(L.MDU === 431, "the base-MTU link MDU");
});

test("a request with signalling bytes still gets the link id of its keys alone", () => {
  const dest = I.Identity.generate();
  const req = L.requestLink({ destination: seq(0, 16), sigPub: dest.sigPub });
  const p = P.decode(req.raw);
  const withMtu = P.encode({ packetType: P.PacketType.LINKREQUEST, destType: P.DestType.SINGLE, destination: p.destination, data: Buffer.concat([p.data, Buffer.from([0x21, 0x01, 0xf4])]) });
  const acc = L.acceptLink(dest, P.decode(withMtu));
  assert.deepEqual(acc.link.id, req.id);
  assert.ok(req.complete(P.decode(acc.proof)));
});

test("carry: whole, two and three parts; a lost or reordered part drops only that packet", () => {
  const j = new Carry.Joiner();
  const small = seq(0, 100);
  assert.deepEqual(j.push(Carry.cut(small, 0)[0]), small);
  const pcm = seq(0, 686);
  const two = Carry.cut(pcm, 1);
  assert.equal(two.length, 2);
  assert.ok(two.every((p) => p.length <= L.MDU));
  assert.equal(j.push(two[0]), null);
  assert.deepEqual(j.push(two[1]), pcm);
  const big = seq(0, 1024);
  const three = Carry.cut(big, 2);
  assert.equal(three.length, 3);
  assert.equal(j.push(three[0]), null);
  assert.equal(j.push(three[1]), null);
  assert.deepEqual(j.push(three[2]), big);
  assert.equal(j.push(two[1]), null, "a second half without its first");
  assert.equal(j.push(two[0]), null);
  assert.equal(j.push(Carry.cut(pcm, 3)[1]), null, "another packet's half");
  assert.deepEqual(j.push(Carry.cut(small, 4)[0]), small, "and the next whole one is fine");
  assert.equal(j.push(Buffer.from([9, 0, 0, 1])), null);
  assert.equal(j.push(Buffer.from([2, 0])), null);
  assert.equal(j.push("x"), null);
  assert.throws(() => Carry.cut(Buffer.alloc(2000), 0));
});

// ---- the transport, over a fake shared medium ----

/** A broadcast medium: every frame one socket writes reaches every other socket, as on one LoRa channel or one hub segment. */
class Medium {
  constructor() { this.socks = new Set(); this.refuse = false; }
  connect() {
    if (this.refuse) throw new Error("refused");
    const medium = this;
    const s = Object.assign(new EventEmitter(), {
      destroyed: false,
      setNoDelay() {},
      write(buf) { for (const o of medium.socks) if (o !== s && !o.destroyed) o.emit("data", Buffer.from(buf)); return true; },
      destroy() { this.destroyed = true; medium.socks.delete(s); },
      end() { this.destroy(); },
    });
    this.socks.add(s);
    setImmediate(() => s.emit("connect"));
    return s;
  }
}

const tick = () => new Promise((r) => setImmediate(r));
async function settle(n = 20) { for (let i = 0; i < n; i++) await tick(); }

function sealed(crypto, codec, payload, senderId = 7, seqNo = 1) {
  const header = CP.encodeHeader({ senderId, seq: seqNo, codec, ttl: 4, time: Math.floor(Date.now() / 1000) });
  return Buffer.concat([header, crypto.seal(CP.aadOf(header), payload)]);
}

function pair(medium, tag = "a2ddc18dee75e2bd", clock, keys = [CK, CK]) {
  const now = clock ?? (() => Date.now());
  const mk = (confirmKey) => new ReticulumTransport({ host: "hub", port: 4242, tag, confirmKey, connect: () => medium.connect(), now });
  let a = mk(keys[0]);
  let b = mk(keys[1]);
  if (!a.weDial(b.destination)) [a, b] = [b, a];            // a dials
  return { a, b };
}

test("transport: two nodes find each other, link, prove the key to each other and carry the channel", async () => {
  const medium = new Medium();
  const { a, b } = pair(medium);
  const got = { a: [], b: [] };
  a.on("packet", (buf, via) => got.a.push({ buf, via }));
  b.on("packet", (buf, via) => got.b.push({ buf, via }));
  const states = [];
  a.on("state", (s) => states.push(s));
  a.start();
  b.start();
  await settle();
  // b heard a's announce first or second; either way one announce round later they are linked.
  b.tick();
  a.tick();
  b.reannounceAt = 1; b.tick();
  await settle();
  assert.equal(a.linkCount, 1, "each end's key proof checked out at the other");
  assert.equal(b.linkCount, 1);
  assert.deepEqual(states, [true]);
  const crypto = ChannelCrypto.forChannelKeySync("north-star-2026");
  const hello = sealed(crypto, CP.Codec.HELLO, CP.encodeHello({ name: "A", transports: 8, ttl: 4 }));
  const opus = sealed(crypto, CP.Codec.OPUS, Buffer.alloc(60, 1), 7, 2);
  const pcm = sealed(crypto, CP.Codec.PCM, Buffer.alloc(640, 2), 7, 3);
  assert.equal(a.send(hello), true);
  assert.equal(a.send(opus), true);
  assert.equal(a.send(pcm), true);
  await settle();
  assert.deepEqual(got.b.map((g) => g.buf.length), [hello.length, opus.length, pcm.length], "PCM arrives whole, in two parts");
  assert.equal(b.send(opus, got.b[0].via), false, "never back where it came from");
  assert.equal(b.send(opus), true);
  await settle();
  assert.equal(got.a.length, 1);
  a.stop();
  b.stop();
});

test("transport: a stranger who copies the name hash gets and passes nothing, however it replays or reflects", async () => {
  let t = 5_000_000;
  const medium = new Medium();
  const { a, b } = pair(medium, "a2ddc18dee75e2bd", () => t, [CK, Buffer.alloc(32, 9)]);
  const mine = a.confirmKey.equals(CK) ? a : b;
  const stranger = mine === a ? b : a;
  // The stranger sends back, on the same link, whatever proof it is given: a reflection.
  stranger.onKeyProof = (e, payload) => stranger.write(e.link.dataPacket(payload));
  const got = [];
  mine.on("packet", (buf) => got.push(buf));
  mine.start();
  stranger.start();
  await settle();
  mine.tick(); stranger.tick();
  mine.reannounceAt = 1; stranger.reannounceAt = 1; mine.tick(); stranger.tick();
  await settle();
  assert.equal(mine.links.size, 1, "linked");
  const first = [...mine.links.keys()][0];
  assert.equal(mine.linkCount, 0, "but its proof (made with another key) and our own reflected back prove nothing");
  const crypto = ChannelCrypto.forChannelKeySync("north-star-2026");
  const hello = sealed(crypto, CP.Codec.HELLO, CP.encodeHello({ name: "A", transports: 8, ttl: 4 }));
  assert.equal(mine.send(hello), false, "nothing of ours goes to it, hellos included");
  // A genuine sealed packet copied from anywhere, sent on its link, is dropped unread.
  const e = [...stranger.links.values()][0];
  for (const part of Carry.cut(hello, 0)) stranger.write(e.link.dataPacket(part));
  await settle();
  assert.deepEqual(got, []);
  // Our proof keeps going out every couple of seconds; after CONFIRM_MS the link is closed,
  // although it never fell silent (the proofs kept it fresh), so it is the confirm rule that did it.
  for (let s = 0; s < CONFIRM_MS / 1000; s += 2) { t += 2000; mine.tick(); await settle(); }
  t += 1000;
  mine.tick();
  await settle();
  assert.ok(!mine.links.has(first), "closed (a redial may already have made the next one)");
  assert.equal(mine.linkCount, 0);
  mine.stop();
  stranger.stop();
});

test("transport: a key proof lost on the way is sent again, and the ends still confirm", async () => {
  let t = 7_000_000;
  const medium = new Medium();
  const { a, b } = pair(medium, "7777777777777777", () => t);
  // The first proof each end sends is lost.
  for (const n of [a, b]) {
    const real = n.sendProof.bind(n);
    let dropped = false;
    n.sendProof = (e) => { if (!dropped) { dropped = true; e.proofAt = t; return; } real(e); };
  }
  a.start();
  b.start();
  await settle();
  b.tick(); a.tick(); b.reannounceAt = 1; b.tick();
  await settle();
  assert.equal(a.linkCount + b.linkCount, 0);
  // Both resend; the end that confirms first answers the other's next resend with its own again.
  for (let round = 0; round < 2; round++) {
    t += 2000;
    a.tick(); b.tick();
    await settle();
  }
  assert.equal(a.linkCount, 1);
  assert.equal(b.linkCount, 1);
  a.stop();
  b.stop();
});

test("transport: another channel's node is never dialled, and a replayed older announce changes nothing", async () => {
  const medium = new Medium();
  const mine = new ReticulumTransport({ host: "h", port: 1, tag: "1111111111111111", confirmKey: CK, connect: () => medium.connect() });
  const other = new ReticulumTransport({ host: "h", port: 1, tag: "2222222222222222", confirmKey: CK, connect: () => medium.connect() });
  mine.start();
  other.start();
  await settle();
  assert.equal(mine.peers.size, 0);
  assert.equal(other.peers.size, 0);
  // An older announce of a peer we know is ignored.
  const peerId = I.Identity.generate();
  const newer = I.buildAnnounce(peerId, mine.nameHash, Buffer.alloc(0), seq(0, 5), 2000);
  const older = I.buildAnnounce(peerId, mine.nameHash, Buffer.alloc(0), seq(5, 5), 1000);
  const pk = (a) => P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data });
  mine.onFrame(pk(newer));
  mine.onFrame(pk(older));
  assert.equal(mine.peers.get(newer.destination.toString("hex")).emitted, 2000);
  mine.onFrame(pk(I.buildAnnounce(mine.identity, mine.nameHash)));   // our own echo
  assert.equal(mine.peers.size, 1);
  mine.onFrame(Buffer.from([1, 2, 3]));
  mine.stop();
  other.stop();
});

test("transport: silent links are closed, the dialler redials, unproven requests time out", async () => {
  let t = 1_000_000;
  const clock = () => t;
  const medium = new Medium();
  const { a, b } = pair(medium, "3333333333333333", clock);
  a.start();
  b.start();
  await settle();
  b.reannounceAt = 1; b.tick(); a.tick();
  await settle();
  assert.equal(a.linkCount, 1);
  // A link that goes silent is dropped, at both ends.
  t += STALE_MS + 1;
  a.tick();
  await settle();
  assert.equal(a.links.size, 0);
  assert.equal(b.links.size, 0, "the close reached the far end");
  // The dialler redials after its backoff.
  t += 2000;
  a.tick();
  await settle();
  assert.equal(a.linkCount, 1);
  // A request nobody answers is given up after LINK_TIMEOUT_MS.
  b.stop();
  t += 20_000;
  a.tick();
  assert.equal(a.pending.size, 1);
  t += LINK_TIMEOUT_MS + 1;
  a.tick();
  assert.equal(a.pending.size, 0);
  a.stop();
});

test("transport: a refused or dropped connection is retried with backoff, reset only by a connection that held", async () => {
  let t = 0;
  const medium = new Medium();
  medium.refuse = true;
  const r = new ReticulumTransport({ host: "hub", port: 4242, tag: "4444444444444444", confirmKey: CK, connect: () => medium.connect(), now: () => t });
  const lines = [];
  r.on("status", (l) => lines.push(l));
  r.start();
  assert.match(lines[0], /refused; retrying in 1 s/);
  assert.equal(r.ready, false);
  assert.equal(r.send(Buffer.alloc(40)), false);
  const redial = async () => { clearTimeout(r.retryTimer); r.retryTimer = null; r.dial(); await settle(); };
  medium.refuse = false;
  await redial();
  assert.equal(r.ready, true);
  // Accepted and dropped at once (a proxy with nothing behind it): the backoff keeps growing.
  [...medium.socks][0].emit("error", new Error("reset"));
  assert.equal(r.ready, false);
  assert.match(lines.at(-1), /reset; retrying in 2 s/);
  // A connection that held for STABLE_MS starts the backoff over.
  await redial();
  t += STABLE_MS;
  [...medium.socks][0].emit("error", new Error("reset"));
  assert.match(lines.at(-1), /reset; retrying in 1 s/);
  r.stop();
  assert.equal(r.retryTimer, null);
  assert.throws(() => new ReticulumTransport({ host: "h", port: 1, tag: "4444444444444444" }), /confirmKey/);
});

test("transport: full tables make room for the crew by dropping what never proved the key, never what did", async () => {
  let t = 2_000_000;
  const medium = new Medium();
  const me = new ReticulumTransport({ host: "h", port: 1, tag: "5555555555555555", confirmKey: CK, connect: () => medium.connect(), now: () => t });
  me.gate = { allow: () => true };                                   // the flood budget has its own test
  me.start();
  await settle();
  const { MAX_PEERS, MAX_LINKS } = require("../lib/rns/transport");
  const announce = (id, at) => {
    const a = I.buildAnnounce(id, me.nameHash, Buffer.alloc(0), undefined, at);
    return P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data });
  };
  // Strangers announcing under our (public) name hash fill the peer table...
  let at = 1000;
  const first = I.Identity.generate();
  me.onFrame(announce(first, at));
  t += 10;
  for (let i = 1; i < MAX_PEERS; i++) me.onFrame(announce(I.Identity.generate(), ++at));
  assert.equal(me.peers.size, MAX_PEERS);
  // ...and the next announce still gets in, in place of the stalest.
  const late = I.Identity.generate();
  me.onFrame(announce(late, ++at));
  assert.equal(me.peers.size, MAX_PEERS);
  assert.ok(me.peers.has(I.destinationHash(me.nameHash, late.hash).toString("hex")));
  assert.ok(!me.peers.has(I.destinationHash(me.nameHash, first.hash).toString("hex")));

  // Link requests fill the link table.
  me.links.clear();
  me.pending.clear();
  const request = () => {
    const r = L.requestLink({ destination: me.destination, sigPub: me.identity.sigPub });
    me.onFrame(r.raw);
    return r.id.toString("hex");
  };
  const ids = [];
  for (let i = 0; i < MAX_LINKS; i++) ids.push(request());
  assert.equal(me.links.size, MAX_LINKS);
  // A request no link can come of (65 bytes of data: not 64, not 67) takes nobody's slot.
  const bad = L.requestLink({ destination: me.destination, sigPub: me.identity.sigPub });
  me.onFrame(Buffer.concat([bad.raw, Buffer.alloc(1)]));
  assert.deepEqual([...me.links.keys()], ids, "a malformed request evicts nothing");
  // While every link is younger than GRACE_MS none is pushed out: a new request is refused.
  const early = request();
  assert.ok(!me.links.has(early));
  assert.deepEqual([...me.links.keys()], ids);
  // Past the grace the oldest unconfirmed link makes room.
  me.links.get(ids[0]).createdAt -= 1;
  t += GRACE_MS;
  const newest = request();
  assert.equal(me.links.size, MAX_LINKS);
  assert.ok(me.links.has(newest));
  assert.ok(!me.links.has(ids[0]), "the oldest unconfirmed link made room");
  // Once every link has proved the key, nothing is evicted and a request is refused.
  for (const e of me.links.values()) e.confirmed = true;
  t += GRACE_MS;
  const refused = request();
  assert.ok(!me.links.has(refused));
  assert.equal(me.links.size, MAX_LINKS);
  // A peer behind a confirmed link is never the one forgotten...
  me.peers.clear();
  const kept = I.Identity.generate();
  me.onFrame(announce(kept, ++at));
  const keptKey = I.destinationHash(me.nameHash, kept.hash).toString("hex");
  me.peers.get(keptKey).link = [...me.links.values()][0];
  me.peers.get(keptKey).seenAt = 0;
  for (let i = 1; i < MAX_PEERS; i++) me.onFrame(announce(I.Identity.generate(), ++at));
  me.onFrame(announce(I.Identity.generate(), ++at));
  assert.ok(me.peers.has(keptKey));
  // ...and one whose link has only just dropped is as fresh as that link was, not its old announce.
  const e = me.peers.get(keptKey).link;
  e.peer = keptKey;
  e.lastIn = t;
  me.forget(e);
  assert.equal(me.peers.get(keptKey).seenAt, t);
  me.stop();
});

test("transport: announces and link requests past the flood budget cost no signature work", async () => {
  const t = 3_000_000;
  const medium = new Medium();
  const me = new ReticulumTransport({ host: "h", port: 1, tag: "8888888888888888", confirmKey: CK, connect: () => medium.connect(), now: () => t });
  me.start();
  await settle();
  const { MAX_LINKS } = require("../lib/rns/transport");
  let accepted = 0;
  const realAccept = L.acceptLink;
  L.acceptLink = (...args) => { accepted++; return realAccept(...args); };
  try {
    for (let i = 0; i < MAX_LINKS; i++) me.onFrame(L.requestLink({ destination: me.destination, sigPub: me.identity.sigPub }).raw);
  } finally {
    L.acceptLink = realAccept;
  }
  assert.equal(accepted, GATE_BURST, "the rest of the burst is refused before any key agreement");
  assert.equal(me.links.size, GATE_BURST);
  let verified = 0;
  const realParse = I.parseAnnounce;
  I.parseAnnounce = (...args) => { verified++; return realParse(...args); };
  try {
    for (let i = 0; i < 2 * GATE_BURST; i++) {
      const a = I.buildAnnounce(I.Identity.generate(), me.nameHash);
      me.onFrame(P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data }));
    }
  } finally {
    I.parseAnnounce = realParse;
  }
  assert.equal(verified, GATE_BURST, "announces have a budget of their own, and past it no signature is checked");
  me.stop();
});

test("transport: copies of a checked announce and requests with no slot spend no budget", async () => {
  let t = 8_000_000;
  const medium = new Medium();
  const me = new ReticulumTransport({ host: "h", port: 1, tag: "9999999999999999", confirmKey: CK, connect: () => medium.connect(), now: () => t });
  me.start();
  await settle();
  const { MAX_LINKS } = require("../lib/rns/transport");
  let verified = 0;
  const realParse = I.parseAnnounce;
  I.parseAnnounce = (...args) => { verified++; return realParse(...args); };
  try {
    const a = I.buildAnnounce(I.Identity.generate(), me.nameHash);
    const raw = P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data });
    for (let i = 0; i < 50; i++) me.onFrame(raw);                      // the same announce by many paths
  } finally {
    I.parseAnnounce = realParse;
  }
  assert.equal(verified, 1, "checked once");
  // A full table of links still in their grace: requests that could get no slot spend nothing...
  for (let i = 0; i < MAX_LINKS; i++) { t += 100; me.onFrame(L.requestLink({ destination: me.destination, sigPub: me.identity.sigPub }).raw); }
  assert.equal(me.links.size, MAX_LINKS);
  let accepted = 0;
  const realAccept = L.acceptLink;
  L.acceptLink = (...args) => { accepted++; return realAccept(...args); };
  try {
    for (let i = 0; i < 40; i++) me.onFrame(L.requestLink({ destination: me.destination, sigPub: me.identity.sigPub }).raw);
    assert.equal(accepted, 0);
    // ...so the moment one link is past its grace, the next request still has a token to get in.
    [...me.links.values()][0].createdAt -= GRACE_MS;
    const crew = L.requestLink({ destination: me.destination, sigPub: me.identity.sigPub });
    me.onFrame(crew.raw);
    assert.ok(me.links.has(crew.id.toString("hex")));
  } finally {
    L.acceptLink = realAccept;
  }
  me.stop();
});

test("transport: requests of ours that nobody answers never lock the crew out", async () => {
  let t = 4_000_000;
  const medium = new Medium();
  const me = new ReticulumTransport({ host: "h", port: 1, tag: "6666666666666666", confirmKey: CK, connect: () => medium.connect(), now: () => t });
  me.gate = { allow: () => true };
  me.start();
  await settle();
  const { MAX_LINKS } = require("../lib/rns/transport");
  let at = 1000;
  while (me.pending.size < MAX_LINKS) {
    const id = I.Identity.generate();
    if (!me.weDial(I.destinationHash(me.nameHash, id.hash))) continue;          // it would dial us
    const a = I.buildAnnounce(id, me.nameHash, Buffer.alloc(0), undefined, ++at);
    me.onFrame(P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data }));
  }
  assert.equal(me.links.size, 0);
  t += GRACE_MS;
  const crew = L.requestLink({ destination: me.destination, sigPub: me.identity.sigPub });
  me.onFrame(crew.raw);
  assert.deepEqual([...me.links.keys()], [crew.id.toString("hex")], "the oldest unanswered request made room");
  assert.equal(me.pending.size, MAX_LINKS - 1);
  me.stop();
});
