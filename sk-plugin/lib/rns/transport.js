// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The channel over Reticulum: an end node on one TCP connection to a Reticulum transport node
 * (the boat's rnsd, or a hub ashore), carrying the channel's sealed packets unchanged inside
 * Reticulum links. The app's fi.crewradio.transport.ReticulumTransport is the same design.
 *
 *  - Naming. Our destination is `crewradio.channel.<tag>`, the tag an HMAC of the packet key
 *    (ChannelCrypto.reticulumTag), so only a node with the channel key recognises our announces.
 *    The identity is made fresh for each start and never stored.
 *  - Discovery. We announce on every (re)connect and every ANNOUNCE_MS, or every IDLE_ANNOUNCE_MS
 *    while somebody is missing (no confirmed link at all, or fewer than the peers we know): a
 *    transport node that lost our path (its own uplink bounced, which we never see) or a peer
 *    that forgot us learns of us in minutes, not ten. Of two nodes, the one whose destination hash
 *    sorts lower dials; the other, on hearing a node it did not know, or any node while somebody
 *    is missing, announces again soon so that node learns of it and dials. One link per pair.
 *  - A silent connection. A dead TCP connection with data in flight takes Linux a quarter of an
 *    hour to give up on, and Node cannot set TCP_USER_TIMEOUT as the app does. So when every
 *    confirmed link falls silent at once and nothing at all has come from the transport node
 *    meanwhile, the connection is taken for dead and opened again.
 *  - Links, not group destinations: Reticulum does not carry group packets over more than one
 *    hop. A link carries nothing but the two ends' key proofs until the far end's has checked out
 *    (an HMAC of its role and the link id under a key from the packet key, carry.js), so a
 *    stranger who copies our public name hash and links in learns nothing but that we exist, and
 *    a sealed packet copied from elsewhere proves nothing.
 *  - Floods: the checks that cost a signature (an announce under our name, a link request to us)
 *    come out of a small budget first, and a link younger than GRACE_MS is never evicted, so a
 *    stranger cannot burn our CPU or push a crew link out before it has had time to prove itself.
 *  - Relaying: none within this transport. Every node links to every other, and Reticulum's own
 *    transport nodes do the multi-hop part; the engine relays between this and the LAN.
 *
 * Asking the boat (ask.js): a confirmed link may also carry a question for the boat's Signal K
 * server; it is put together here, a repeat of one already answered gets the same answer again
 * from `asked` without the plugin seeing it twice, and the plugin's reply goes back with answer().
 *
 * Events: 'packet' (buf, via) with `via` the link it came on, 'ask' (id, message, via) for a
 * question whole and not seen before on that link, 'status' (short line), 'state' (up: boolean).
 */

const net = require("node:net");
const { EventEmitter } = require("node:events");
const P = require("./packet");
const I = require("./identity");
const L = require("./link");
const Carry = require("./carry");
const Ask = require("./ask");
const { PeerBudget } = require("../wirelimit");

const ANNOUNCE_MS = 10 * 60_000;       // re-announce: keeps our path fresh on the transport nodes
const IDLE_ANNOUNCE_MS = 2 * 60_000;   // ... and this often while somebody is missing
const REANNOUNCE_MS = 3_000;           // answer to a newcomer, at most this often
const LINK_TIMEOUT_MS = 10_000;        // a request unproven this long is given up
const STALE_MS = 12_000;               // a link silent this long is dead (the engine sends a hello every second)
const CONFIRM_MS = 15_000;             // a link whose far end has not proved the key by then is closed
const PROOF_RESEND_MS = 2_000;         // our proof again, while the far end's has not arrived
const GRACE_MS = 5_000;                // a link or request younger than this is never evicted
const STABLE_MS = 30_000;              // a connection that lasted this long resets the backoff
const GATE_PER_S = 10;                 // announce checks and link requests we spend a signature on, a second
const GATE_BURST = 20;
const VERIFIED_MAX = 256;              // announces remembered as checked
const PEER_FORGET_MS = 3 * ANNOUNCE_MS;
const MAX_LINKS = 32;
const MAX_PEERS = 64;
const ASKED_MAX = 16;                  // questions remembered per link, with their answers, for the asker's repeats

class ReticulumTransport extends EventEmitter {
  /**
   * @param {object} o
   * @param {string} o.host  @param {number} o.port   the transport node's TCP interface
   * @param {string} o.tag   hex tag from the packet key: the channel's destination aspect
   * @param {Buffer} o.confirmKey  the key of each link's key proof (ChannelCrypto.reticulumConfirmKey)
   * @param {(host: string, port: number) => import('node:net').Socket} [o.connect]
   * @param {() => number} [o.now]
   * @param {I.Identity} [o.identity]
   */
  constructor(o) {
    super();
    this.host = o.host;
    this.port = o.port;
    this.connectFn = o.connect ?? ((host, port) => net.connect({ host, port }));
    // Monotonic: every value is compared only with another, and a server clock set from GPS or NTP
    // can step backwards, which would stop every timer here for as long as the step.
    this.now = o.now ?? (() => performance.now());
    this.identity = o.identity ?? I.Identity.generate();
    this.nameHash = I.nameHash(`crewradio.channel.${o.tag}`);
    this.destination = I.destinationHash(this.nameHash, this.identity.hash);
    if (!Buffer.isBuffer(o.confirmKey) || o.confirmKey.length !== 32) throw new Error("confirmKey must be 32 bytes");
    this.confirmKey = o.confirmKey;
    this.budget = new PeerBudget({ now: () => this.now(), perSecond: 400, burst: 800 });
    this.gate = new PeerBudget({ now: () => this.now(), perSecond: GATE_PER_S, burst: GATE_BURST });
    this.verified = new Map();  // packet hash hex of announces already checked (the same announce arrives by several paths)
    this.connectedAt = 0;
    this.sock = null;
    this.running = false;
    this.connected = false;
    this.backoffMs = 1000;
    this.retryTimer = null;
    this.ticker = null;
    this.peers = new Map();     // destination hex -> {announce, transportId, hops, seenAt, emitted, dialAt, backoffMs, link}
    this.links = new Map();     // link id hex -> entry
    this.pending = new Map();   // link id hex -> {req, peer, sentAt}
    this.lastAnnounce = 0;
    this.reannounceAt = 0;
    this.lastRx = 0;            // the last frame from the transport node, of any kind
    this.stats = { announces: 0, linksUp: 0, linksDropped: 0, tx: 0, rx: 0 };
  }

  /** True while connected to the transport node: what the hello may claim. */
  get ready() {
    return this.connected;
  }

  start() {
    if (this.running) return;
    this.running = true;
    this.ticker = setInterval(() => this.tick(), 1000);
    if (this.ticker.unref) this.ticker.unref();
    this.dial();
  }

  stop() {
    this.running = false;
    if (this.retryTimer) { clearTimeout(this.retryTimer); this.retryTimer = null; }
    if (this.ticker) { clearInterval(this.ticker); this.ticker = null; }
    for (const e of this.links.values()) this.write(e.link.closePacket());
    // end(), not destroy(): the closes just written leave first, then the socket goes.
    const s = this.sock;
    this.sock = null;
    if (s) {
      try { s.end(); } catch { /* gone */ }
      const t = setTimeout(() => { try { s.destroy(); } catch { /* gone */ } }, 500);
      if (t.unref) t.unref();
    }
    this.drop();
  }

  /** Links that carry channel traffic now: up, and their far end has proved the key. */
  get linkCount() {
    let n = 0;
    for (const e of this.links.values()) if (e.link.active && e.confirmed) n++;
    return n;
  }

  /** Sends a sealed channel packet on every confirmed link but `except`. True when it went anywhere. */
  send(buf, except = null) {
    if (!this.connected) return false;
    let sent = false;
    for (const e of this.links.values()) {
      if (e === except || !e.link.active || !e.confirmed) continue;
      for (const part of Carry.cut(buf, e.cutId++ & 0xff)) this.write(e.link.dataPacket(part));
      sent = true;
    }
    if (sent) this.stats.tx++;
    return sent;
  }

  // ---- connection ----

  dial() {
    if (!this.running) return;
    let s;
    try {
      s = this.connectFn(this.host, this.port);
    } catch (e) {
      this.lost(e.message);
      return;
    }
    this.sock = s;
    const deframer = new P.Deframer((raw) => this.onFrame(raw));
    s.setNoDelay?.(true);
    // A connection that dies without a word (a marina uplink gone, a NAT mapping dropped) is
    // noticed in seconds rather than after TCP's quarter of an hour of retransmissions.
    s.setKeepAlive?.(true, 10_000);
    s.on("connect", () => {
      if (this.sock !== s) return;
      this.connected = true;
      this.connectedAt = this.now();
      this.lastRx = this.connectedAt;
      this.emit("state", true);
      this.status(`Reticulum: connected to ${this.host}:${this.port}`);
      this.announce();
    });
    s.on("data", (chunk) => { if (this.sock === s) deframer.push(chunk); });
    s.on("error", (e) => { if (this.sock === s) this.lost(e.message); });
    s.on("close", () => { if (this.sock === s) this.lost("connection closed"); });
  }

  lost(why) {
    const was = this.connected;
    // Only a connection that held resets the backoff: one that is accepted and dropped at once
    // (a proxy with nothing behind it, rnsd restarting) must not be redialled every second.
    if (was && this.now() - this.connectedAt >= STABLE_MS) this.backoffMs = 1000;
    this.drop();
    if (was) this.emit("state", false);
    if (!this.running) return;
    this.status(`Reticulum: ${this.host}:${this.port} ${why}; retrying in ${Math.round(this.backoffMs / 1000)} s`);
    this.retryTimer = setTimeout(() => { this.retryTimer = null; this.dial(); }, this.backoffMs);
    if (this.retryTimer.unref) this.retryTimer.unref();
    this.backoffMs = Math.min(this.backoffMs * 2, 15_000);
  }

  /** Forgets the socket and every link on it; the peers stay, their paths run through the same transport node. */
  drop() {
    const s = this.sock;
    this.sock = null;
    this.connected = false;
    if (s) { try { s.destroy(); } catch { /* gone */ } }
    // A peer we just had a link with is as fresh as that link, not as its last announce: the
    // dialler redials it as soon as the connection is back (forget() does the same for one link).
    for (const e of this.links.values()) {
      const peer = e.peer ? this.peers.get(e.peer) : null;
      if (peer) peer.seenAt = Math.max(peer.seenAt, e.lastIn);
    }
    this.links.clear();
    this.pending.clear();
    for (const p of this.peers.values()) { p.link = null; p.dialAt = 0; }
  }

  write(raw) {
    if (!this.sock || !this.connected) return;
    try {
      this.sock.write(P.frame(raw));
    } catch { /* the close event follows */ }
  }

  status(line) {
    this.emit("status", line);
  }

  // ---- inbound ----

  onFrame(raw) {
    this.lastRx = this.now();
    try {
      const p = P.decode(raw);
      if (!p) return;
      switch (p.packetType) {
        case P.PacketType.ANNOUNCE: return this.onAnnounce(p);
        case P.PacketType.LINKREQUEST: return this.onLinkRequest(p);
        case P.PacketType.PROOF: return this.onProof(p);
        case P.PacketType.DATA: return this.onData(p);
        default: return undefined;
      }
    } catch (e) {
      this.status(`Reticulum: ${e.message}`);
    }
  }

  onAnnounce(p) {
    if (p.destType !== P.DestType.SINGLE || p.data.length < I.ANNOUNCE_MIN) return;
    if (!p.data.subarray(I.PUBLIC_BYTES, I.PUBLIC_BYTES + I.NAME_HASH_BYTES).equals(this.nameHash)) return;   // not our channel: no signature check spent
    if (p.destination.equals(this.destination)) return;                                                          // our own, echoed back
    const hash = P.packetHash(p.raw).toString("hex");
    if (this.verified.has(hash)) return;                                                                          // a copy of one already checked: no budget spent on it
    if (!this.gate.allow("announce")) return;                                                                     // a flood under our public name: no signature check for it
    const key = p.destination.toString("hex");
    let peer = this.peers.get(key);
    const a = I.parseAnnounce(p);
    if (!a) return;
    this.verified.set(hash, true);
    if (this.verified.size > VERIFIED_MAX) this.verified.delete(this.verified.keys().next().value);
    if (peer && a.emitted < peer.emitted) return;                                                                 // an older announce replayed
    const fresh = !peer;
    if (fresh && this.peers.size >= MAX_PEERS && !this.evictPeer()) return;
    if (fresh) {
      peer = { announce: a, transportId: null, hops: 0, seenAt: 0, emitted: 0, dialAt: 0, backoffMs: 1000, link: null };
      this.peers.set(key, peer);
    }
    peer.announce = a;
    peer.emitted = a.emitted;
    peer.seenAt = this.now();
    peer.transportId = p.headerType === P.HeaderType.TWO ? p.transportId : null;
    peer.hops = p.hops + 1;
    this.stats.announces++;
    if (this.weDial(a.destination)) {
      // A newer announce while our link to it has gone quiet: it reconnected, the old link is dead.
      if (peer.link && this.now() - peer.link.lastIn > 3000) this.close(peer.link);
      if (!peer.link && !this.pendingFor(key)) { peer.dialAt = 0; this.linkTo(key, peer); }
    } else if (fresh || this.missing()) {
      // It dials us, but first it has to hear of us: announce again, soon, not on every newcomer.
      // A node we already knew too, while somebody is missing: it may be the one that forgot us
      // (an announce only goes to the side that dials, so the order it happens in cannot tell us).
      this.reannounceAt = Math.max(this.reannounceAt, this.lastAnnounce + REANNOUNCE_MS, this.now() + 500 + Math.random() * 1500);
    }
  }

  onLinkRequest(p) {
    if (p.destType !== P.DestType.SINGLE || !p.destination.equals(this.destination)) return;
    const id = L.linkIdOf(p.raw, p.data.length).toString("hex");
    if (this.links.has(id)) return;                                     // a copy of one we already answered
    if (!this.canMakeRoom()) return;                                    // no slot it could have: not worth a token
    if (!this.gate.allow("request")) return;                            // a flood of requests: no key agreement for it
    const r = L.acceptLink(this.identity, p);
    if (!r) return;                                                     // only a request a link can come of may cost another its slot
    if (this.links.size + this.pending.size >= MAX_LINKS && !this.evictLink()) return;
    this.addLink(r.link, null);
    this.write(r.proof);
  }

  onProof(p) {
    if (p.destType !== P.DestType.LINK || p.context !== P.Context.LRPROOF) return;
    const key = p.destination.toString("hex");
    const pend = this.pending.get(key);
    if (!pend) return;
    const link = pend.req.complete(p);
    if (!link) return;
    this.pending.delete(key);
    const peer = this.peers.get(pend.peer);
    const e = this.addLink(link, pend.peer);
    if (peer) { peer.link = e; peer.backoffMs = 1000; }
    this.write(link.rttPacket((this.now() - pend.sentAt) / 1000));
    this.sendProof(e);                                                  // after the RTT, which makes it active at the far end
  }

  onData(p) {
    if (p.destType !== P.DestType.LINK) return;
    const e = this.links.get(p.destination.toString("hex"));
    if (!e) return;
    const wasActive = e.link.active;
    const r = e.link.handle(p);
    if (!r) return;
    e.lastIn = this.now();
    if (!wasActive && e.link.active) this.sendProof(e);                // the far end's RTT: the link is up on our side too
    if (r.kind === "keepalive" && r.reply) this.write(r.reply);
    else if (r.kind === "close") this.forget(e);
    else if (r.kind === "data") {
      if (Carry.isKeyProof(r.plain)) return this.onKeyProof(e, r.plain);
      if (!e.confirmed) return;                                          // nothing counts before the far end has proved the key
      if (Ask.isAsk(r.plain)) return this.onAsk(e, r.plain);
      const packet = e.joiner.push(r.plain);
      if (!packet || !this.budget.allow(e.key)) return;
      this.stats.rx++;
      this.emit("packet", packet, e);
    }
  }

  /**
   * A part of a question on a confirmed link. Answers are the app's business, not ours: only
   * requests are put together. A question already answered is answered again from memory (the
   * asker lost the answer and repeated itself), one still being worked on is left to finish.
   */
  onAsk(e, payload) {
    if (payload[0] !== Ask.REQUEST || !this.budget.allow(e.key)) return;
    const whole = e.questions.push(payload);
    if (!whole) return;
    if (e.asked.has(whole.id)) {
      const answer = e.asked.get(whole.id);
      if (answer) this.writeAnswer(e, whole.id, answer);
      return;
    }
    e.asked.set(whole.id, null);
    if (e.asked.size > ASKED_MAX) e.asked.delete(e.asked.keys().next().value);
    this.emit("ask", whole.id, whole.message, e);
  }

  /**
   * The answer to question `id` that came on the link `via`, remembered for a repeat. False when
   * the link has gone meanwhile (the asker will ask again on its next one) or the answer is too
   * large to carry, in which case a short failure goes instead.
   */
  answer(via, id, message) {
    if (this.links.get(via.key) !== via || !via.link.active) return false;
    let parts;
    try {
      parts = Ask.cut(Ask.ANSWER, id, message);
    } catch {
      message = Ask.answer(Ask.Status.FAILED, Buffer.from("answer too large to carry"));
      parts = Ask.cut(Ask.ANSWER, id, message);
    }
    if (via.asked.has(id)) via.asked.set(id, message);
    for (const part of parts) this.write(via.link.dataPacket(part));
    return true;
  }

  writeAnswer(e, id, message) {
    for (const part of Ask.cut(Ask.ANSWER, id, message)) this.write(e.link.dataPacket(part));
  }

  /** The far end's key proof: the link is confirmed, and ours goes again if it seems to have missed it. */
  onKeyProof(e, payload) {
    if (!e.link.active || !Carry.proofMatches(payload, this.confirmKey, e.link.id, !e.link.initiator)) return;
    if (e.confirmed) {
      // It keeps sending its proof, so it has not had ours: once a second at most, again.
      if (this.now() - e.proofAt >= 1000) this.sendProof(e);
      return;
    }
    e.confirmed = true;
    this.linksChanged();
  }

  sendProof(e) {
    if (!e.link.active) return;
    this.write(e.link.dataPacket(Carry.keyProof(this.confirmKey, e.link.id, e.link.initiator)));
    e.proofAt = this.now();
  }

  // ---- links ----

  weDial(peerDestination) {
    return Buffer.compare(this.destination, peerDestination) < 0;
  }

  pendingFor(peerKey) {
    for (const p of this.pending.values()) if (p.peer === peerKey) return true;
    return false;
  }

  // Our name hash is public: anyone can announce under it or link to us, and with a hard cap
  // alone a stranger who filled the tables first would keep the crew out. So a full table makes
  // room - the link waiting longest without proving the key, the peer heard from longest ago
  // with no confirmed link - and a link that has proved the key is never the one to go. Nor is
  // one younger than GRACE_MS: a crew link needs a round trip to prove itself, and a flood of
  // requests would otherwise push every new one out before it could.

  /** True when there is a free slot, or one evictLink() may make. */
  canMakeRoom() {
    if (this.links.size + this.pending.size < MAX_LINKS) return true;
    const now = this.now();
    for (const e of this.links.values()) if (!e.confirmed && now - e.createdAt >= GRACE_MS) return true;
    for (const p of this.pending.values()) if (now - p.sentAt >= GRACE_MS) return true;
    return false;
  }

  /**
   * Makes one slot: closes the oldest unconfirmed link past its grace, else drops the oldest
   * request of ours past it (strangers' announces can fill the table with those just as well);
   * false when there is neither.
   */
  evictLink() {
    const now = this.now();
    let victim = null;
    for (const e of this.links.values()) {
      if (!e.confirmed && now - e.createdAt >= GRACE_MS && (!victim || e.createdAt < victim.createdAt)) victim = e;
    }
    if (victim) {
      this.close(victim);
      return true;
    }
    let oldest = null;
    for (const [id, p] of this.pending) if (now - p.sentAt >= GRACE_MS && (!oldest || p.sentAt < oldest[1].sentAt)) oldest = [id, p];
    if (!oldest) return false;
    this.pending.delete(oldest[0]);
    const peer = this.peers.get(oldest[1].peer);
    if (peer) { peer.dialAt = this.now() + peer.backoffMs; peer.backoffMs = Math.min(peer.backoffMs * 2, 15_000); }
    return true;
  }

  /** Forgets the stalest peer without a confirmed link, and its request or link; false when there is none. */
  evictPeer() {
    let key = null;
    let victim = null;
    for (const [k, p] of this.peers) {
      if (p.link?.confirmed) continue;
      if (!victim || p.seenAt < victim.seenAt) { key = k; victim = p; }
    }
    if (!victim) return false;
    for (const [id, pend] of this.pending) if (pend.peer === key) this.pending.delete(id);
    if (victim.link) this.close(victim.link);
    this.peers.delete(key);
    return true;
  }

  linkTo(key, peer) {
    if (!this.connected || (this.links.size + this.pending.size >= MAX_LINKS && !this.evictLink())) return;
    const transportId = peer.hops > 1 ? peer.transportId : null;
    const req = L.requestLink(peer.announce, transportId);
    this.pending.set(req.id.toString("hex"), { req, peer: key, sentAt: this.now() });
    this.write(req.raw);
  }

  addLink(link, peerKey) {
    const e = { key: link.id.toString("hex"), link, peer: peerKey, createdAt: this.now(), lastIn: this.now(), confirmed: false, proofAt: 0, joiner: new Carry.Joiner(), cutId: 0, questions: new Ask.Assembler(Ask.REQUEST), asked: new Map() };
    this.links.set(e.key, e);
    this.stats.linksUp++;
    return e;
  }

  close(e) {
    this.write(e.link.closePacket());
    this.forget(e);
  }

  forget(e) {
    if (this.links.get(e.key) !== e) return;
    this.links.delete(e.key);
    this.stats.linksDropped++;
    if (e.link.active && e.confirmed) this.linksChanged();
    const peer = e.peer ? this.peers.get(e.peer) : null;
    // A peer we just had a link with is fresher than its last announce says: a flood of strangers'
    // announces must not make it the stalest, and forget it, the moment its link drops.
    if (peer) peer.seenAt = Math.max(peer.seenAt, e.lastIn);
    if (peer && peer.link === e) {
      peer.link = null;
      peer.dialAt = this.now() + peer.backoffMs;
      peer.backoffMs = Math.min(peer.backoffMs * 2, 15_000);
    }
  }

  linksChanged() {
    const n = this.linkCount;
    this.status(`Reticulum: ${n} link${n === 1 ? "" : "s"}`);
  }

  announce() {
    const a = I.buildAnnounce(this.identity, this.nameHash);
    this.write(P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data }));
    this.lastAnnounce = this.now();
    this.reannounceAt = 0;
  }

  /**
   * Somebody is missing: no confirmed link at all, or fewer than the peers we know. A link we
   * answered does not say whose it is, so the count is all there is to go on; a peer that left
   * counts as missing until it is forgotten.
   */
  missing() {
    const n = this.linkCount;
    return n === 0 || n < this.peers.size;
  }

  /** Once a second: announces due, requests and links timed out, redials, forgotten peers. */
  tick() {
    if (!this.connected) return;
    const now = this.now();
    const every = this.missing() ? IDLE_ANNOUNCE_MS : ANNOUNCE_MS;
    if (now - this.lastAnnounce >= every || (this.reannounceAt && now >= this.reannounceAt)) this.announce();
    for (const [id, p] of this.pending) {
      if (now - p.sentAt < LINK_TIMEOUT_MS) continue;
      this.pending.delete(id);
      const peer = this.peers.get(p.peer);
      if (peer) { peer.dialAt = now + peer.backoffMs; peer.backoffMs = Math.min(peer.backoffMs * 2, 15_000); }
    }
    let silent = 0;
    for (const e of [...this.links.values()]) {
      if (now - e.lastIn > STALE_MS || (!e.confirmed && now - e.createdAt > CONFIRM_MS)) {
        if (e.confirmed && now - e.lastIn > STALE_MS) silent++;
        this.close(e);
        continue;
      }
      if (!e.confirmed && e.link.active && now - e.proofAt >= PROOF_RESEND_MS) this.sendProof(e);
    }
    // Every crew link silent and not a byte from the transport node either: the connection is
    // dead, not the crew. Open it again rather than wait out TCP's retransmissions.
    if (silent > 0 && this.linkCount === 0 && now - this.lastRx > STALE_MS) {
      this.lost("silent: nothing from the transport node");
      return;
    }
    for (const [key, peer] of this.peers) {
      if (!peer.link && now - peer.seenAt > PEER_FORGET_MS) { this.peers.delete(key); continue; }
      // Redial while it is still announcing (every ANNOUNCE_MS); after that its next announce does it.
      if (!peer.link && this.weDial(peer.announce.destination) && !this.pendingFor(key) && now >= peer.dialAt && now - peer.seenAt < ANNOUNCE_MS + 60_000) this.linkTo(key, peer);
    }
  }
}

module.exports = { ReticulumTransport, ANNOUNCE_MS, IDLE_ANNOUNCE_MS, REANNOUNCE_MS, LINK_TIMEOUT_MS, STALE_MS, CONFIRM_MS, GRACE_MS, STABLE_MS, GATE_BURST, MAX_LINKS, MAX_PEERS };
