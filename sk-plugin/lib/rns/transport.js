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
 *  - Discovery, within the transport nodes' announce budget. rnsd with transport on passes a
 *    destination's announces on at most about once an hour after the first six (AnnounceBudget,
 *    below); the rest update its own path table and go no further, however often they are sent.
 *    So an announce is what a newcomer makes, once, on connecting (its identity is new, so it
 *    always has budget), and what a node with no link at all repeats every IDLE_ANNOUNCE_MS while
 *    the budget has room to spare; never an answer to somebody else's, and never a refresh while
 *    linked. Of two nodes the one whose destination hash sorts lower dials. The other, on hearing
 *    a node it did not know (or any node while it has no link at all), cannot count on that node
 *    ever hearing it: when no link from it has come in FALLBACK_MS later, it dials the newcomer
 *    itself (`takeover`), and redials it from then on as its dialler would. An answering announce
 *    was the first design; rnsd blocks it once a node has announced six times in an hour, and a
 *    plugin left alone on a hub for twelve minutes could then never be found until restarted
 *    (reproduced against rnsd 1.5.4, whose defaults these are).
 *  - A silent connection. A dead TCP connection with data in flight takes Linux a quarter of an
 *    hour to give up on, and Node cannot set TCP_USER_TIMEOUT as the app does. So when every
 *    confirmed link falls silent at once and nothing at all has come from the transport node
 *    meanwhile, the connection is taken for dead and opened again. With no link at all there is
 *    nothing to fall silent, but the transport node sends each announce it passes on back to us
 *    as well (rnsd 1.5.4 does, within a second): on a connection that has done so, an announce the
 *    budget says will pass, followed by STALE_MS without a single frame, is the same verdict. Only
 *    after a first echo, so a node that never echoes (or is stricter than its defaults) costs at
 *    most one reconnect, not a loop.
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
 * question whole and not seen before on that link, 'status' (short line), 'state' (up: boolean),
 * 'debug' (a line for the server's debug log: peers heard, dials, links up and down, reconnects;
 * a few a minute at most, never one per packet). diagnostics() is the same picture on demand.
 */

const net = require("node:net");
const { EventEmitter } = require("node:events");
const P = require("./packet");
const I = require("./identity");
const L = require("./link");
const Carry = require("./carry");
const Ask = require("./ask");
const { PeerBudget } = require("../wirelimit");

const IDLE_ANNOUNCE_MS = 5 * 60_000;   // again while we have no link at all, when the budget has room to spare
const FALLBACK_MS = 5_000;             // a newcomer that has not dialled us by then is dialled by us
const REDIAL_MS = 11 * 60_000;         // a peer whose link dropped is redialled this long after it was last heard
const RATE_TARGET_MS = 3600_000;       // rnsd 1.5.4 with transport on: Interface.DEFAULT_AR_TARGET (s) ...
const RATE_GRACE = 5;                  // ... and DEFAULT_AR_GRACE; DEFAULT_AR_PENALTY is 0
const RATE_RESERVE = 2;                // announces the idle repeat leaves unspent, for reconnects
const LINK_TIMEOUT_MS = 10_000;        // a request unproven this long is given up
const STALE_MS = 12_000;               // a link silent this long is dead (the engine sends a hello every second)
const CONFIRM_MS = 15_000;             // a link whose far end has not proved the key by then is closed
const PROOF_RESEND_MS = 2_000;         // our proof again, while the far end's has not arrived
const GRACE_MS = 5_000;                // a link or request younger than this is never evicted
const STABLE_MS = 30_000;              // a connection that lasted this long resets the backoff
const GATE_PER_S = 10;                 // announce checks and link requests we spend a signature on, a second
const GATE_BURST = 20;
const VERIFIED_MAX = 256;              // announces remembered as checked
const PEER_FORGET_MS = 30 * 60_000;
const MAX_LINKS = 32;
const MAX_PEERS = 64;
const ASKED_MAX = 16;                  // questions remembered per link, with their answers, for the asker's repeats

/**
 * rnsd's announce rate rule for one destination (Transport.inbound, rnsd 1.5.4), kept here so we
 * know which of our announces a transport node will pass on. Its first announce opens the record;
 * each later one sooner than `targetMs` after the last one passed is a violation, one later than
 * that forgives one, and past `grace` violations an announce is not rebroadcast (the node's own
 * path table still takes it) until `targetMs` after the last one passed. The limit cannot be
 * switched off in rnsd, only moved (`announce_rate_target` per interface, `default_ar_target`).
 */
class AnnounceBudget {
  constructor(targetMs = RATE_TARGET_MS, grace = RATE_GRACE) {
    this.targetMs = targetMs;
    this.grace = grace;
    this.last = null;
    this.violations = 0;
    this.blockedUntil = -Infinity;
  }

  /** What an announce at `now` would meet: {passes, violations} after it, nothing recorded. */
  peek(now) {
    if (this.last === null) return { passes: true, violations: 0 };
    if (now <= this.blockedUntil) return { passes: false, violations: this.violations };
    const violations = now - this.last < this.targetMs ? this.violations + 1 : Math.max(0, this.violations - 1);
    return { passes: violations <= this.grace, violations };
  }

  /** Records an announce sent at `now`; true when the transport node passes it on. */
  record(now) {
    if (this.last === null) { this.last = now; return true; }
    if (now <= this.blockedUntil) return false;
    const { passes, violations } = this.peek(now);
    this.violations = violations;
    if (passes) this.last = now;
    else this.blockedUntil = this.last + this.targetMs;
    return passes;
  }
}

/** The first 8 hex digits of a destination or link id: enough to tell them apart in a log. */
const short = (hex) => hex.slice(0, 8);

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
    this.announces = new AnnounceBudget();
    this.expectEcho = false;    // our last announce is one the transport node passes on, so it comes back
    this.acceptedConfirmed = 0; // links dialled by others that proved the key: a newcomer that dialled us shows here
    this.lastRx = 0;            // the last frame from the transport node, of any kind
    this.echoes = false;        // this connection has sent one of our announces back to us
    this.stats = { announces: 0, older: 0, echoes: 0, linksUp: 0, linksDropped: 0, tx: 0, rx: 0, reconnects: 0, unproven: 0, unanswered: 0 };
    this.lastAnnounceWall = 0;  // our last announce's emission time, seconds of wall clock, as it went out
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
      this.echoes = false;
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
    if (was) { this.stats.reconnects++; this.debug(`Reticulum: connection lost (${why})`); }
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
    if (p.destination.equals(this.destination)) { this.echoes = true; this.stats.echoes++; return; }                                  // our own, echoed back: the connection works
    const hash = P.packetHash(p.raw).toString("hex");
    if (this.verified.has(hash)) return;                                                                          // a copy of one already checked: no budget spent on it
    if (!this.gate.allow("announce")) return;                                                                     // a flood under our public name: no signature check for it
    const key = p.destination.toString("hex");
    let peer = this.peers.get(key);
    const a = I.parseAnnounce(p);
    if (!a) return;
    this.verified.set(hash, true);
    if (this.verified.size > VERIFIED_MAX) this.verified.delete(this.verified.keys().next().value);
    if (peer && a.emitted < peer.emitted) {                                                                       // an older announce replayed
      this.stats.older++;
      this.debug(`Reticulum: ignored an announce of ${short(key)} emitted ${peer.emitted - a.emitted} s before one already heard`);
      return;
    }
    const fresh = !peer;
    if (fresh && this.peers.size >= MAX_PEERS && !this.evictPeer()) return;
    if (fresh) {
      peer = { announce: a, transportId: null, hops: 0, seenAt: 0, emitted: 0, dialAt: 0, backoffMs: 1000, link: null, takeover: false, fallbackAt: 0, acceptedMark: 0 };
      this.peers.set(key, peer);
    }
    peer.announce = a;
    peer.emitted = a.emitted;
    peer.seenAt = this.now();
    peer.transportId = p.headerType === P.HeaderType.TWO ? p.transportId : null;
    peer.hops = p.hops + 1;
    this.stats.announces++;
    if (fresh) this.debug(`Reticulum: heard ${short(key)}, ${peer.hops} hop${peer.hops === 1 ? "" : "s"} away; ${this.weDial(a.destination) ? "we dial" : "it dials"}`);
    if (this.weDial(a.destination)) {
      // A newer announce while our link to it has gone quiet: it reconnected, the old link is dead.
      if (peer.link && this.now() - peer.link.lastIn > 3000) this.close(peer.link);
      if (!peer.link && !this.pendingFor(key)) { peer.dialAt = 0; this.linkTo(key, peer); }
    } else if ((fresh || this.linkCount === 0) && !peer.link && !this.pendingFor(key)) {
      // It dials us only if it has heard us, and a transport node may have stopped passing our
      // announces on. Give it FALLBACK_MS; a link that proves the key meanwhile is taken for its.
      peer.fallbackAt = this.now() + FALLBACK_MS;
      peer.acceptedMark = this.acceptedConfirmed;
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
    if (!e.peer) this.acceptedConfirmed++;
    this.debug(`Reticulum: link ${short(e.key)} to ${e.peer ? short(e.peer) : "a node that dialled us"} confirmed`);
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
    this.debug(`Reticulum: dialling ${short(key)}${transportId ? ` via ${short(transportId.toString("hex"))}` : ""}`);
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
    // Links a stranger opened and never proved are counted, not logged: they can come ten a second.
    if (e.confirmed || e.peer) this.debug(`Reticulum: link ${short(e.key)} ${e.confirmed ? "closed" : "given up: the far end never proved the key"}`);
    else this.stats.unproven++;
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

  debug(line) {
    this.emit("debug", line);
  }

  /**
   * What this transport sees, for the status page: enough to tell a dead connection, a peer never
   * heard, a peer heard but not linked, and a clock that went backwards apart. Times in seconds.
   */
  diagnostics() {
    const now = this.now();
    const ago = (t) => (t ? Math.round((now - t) / 1000) : null);
    const wall = Math.floor(Date.now() / 1000);
    return {
      destination: short(this.destination.toString("hex")),
      connected: this.connected,
      echoes: this.echoes,
      missing: this.connected ? this.missing() : null,
      lastRxAgo: this.connected ? ago(this.lastRx) : null,
      lastAnnounceAgo: ago(this.lastAnnounce),
      lastAnnounceEmittedAgo: this.lastAnnounceWall ? wall - this.lastAnnounceWall : null,
      pending: this.pending.size,
      budget: { violations: this.announces.violations, nextPasses: this.announces.peek(now).passes },
      stats: { ...this.stats },
      peers: [...this.peers.entries()].map(([k, p]) => ({
        destination: short(k), hops: p.hops, heardAgo: ago(p.seenAt), emittedAgo: wall - p.emitted,
        weDial: this.weDial(p.announce.destination), takeover: p.takeover, linked: !!p.link?.confirmed,
      })),
      links: [...this.links.values()].map((e) => ({
        id: short(e.key), peer: e.peer ? short(e.peer) : null, confirmed: e.confirmed,
        ageS: ago(e.createdAt), lastInAgo: ago(e.lastIn),
      })),
    };
  }

  linksChanged() {
    const n = this.linkCount;
    this.status(`Reticulum: ${n} link${n === 1 ? "" : "s"}`);
  }

  /** Announces; always on connecting (the transport node's path table needs it even when it passes it on no further). */
  announce() {
    const a = I.buildAnnounce(this.identity, this.nameHash);
    this.lastAnnounceWall = Math.floor(Date.now() / 1000);
    this.write(P.encode({ packetType: P.PacketType.ANNOUNCE, destType: P.DestType.SINGLE, destination: a.destination, data: a.data }));
    this.lastAnnounce = this.now();
    this.expectEcho = this.announces.record(this.lastAnnounce);
    if (!this.expectEcho) this.debug("Reticulum: announced, but past the transport node's announce budget: it goes no further than the node itself");
  }

  /**
   * Somebody is missing: no confirmed link at all, or fewer than the peers we know. A link we
   * answered does not say whose it is, so the count is all there is to go on; a peer that left
   * counts as missing until it is forgotten. For the status page only.
   */
  missing() {
    const n = this.linkCount;
    return n === 0 || n < this.peers.size;
  }

  /** Once a second: announces due, requests and links timed out, redials, forgotten peers. */
  tick() {
    if (!this.connected) return;
    const now = this.now();
    // Our last announce went out STALE_MS ago on a connection that echoes them, and nothing at all
    // has come back since: dead, whether or not any link was there to notice.
    if (this.echoes && this.expectEcho && this.lastRx < this.lastAnnounce && now - this.lastAnnounce > STALE_MS) {
      this.lost("silent: our announce was not echoed");
      return;
    }
    // Alone: say so again now and then, while the transport node would still pass it on with some
    // to spare. Linked: nothing, the links say all there is to say.
    if (this.linkCount === 0 && now - this.lastAnnounce >= IDLE_ANNOUNCE_MS) {
      const next = this.announces.peek(now);
      if (next.passes && next.violations <= this.announces.grace - RATE_RESERVE) this.announce();
    }
    for (const [id, p] of this.pending) {
      if (now - p.sentAt < LINK_TIMEOUT_MS) continue;
      this.pending.delete(id);
      this.stats.unanswered++;
      this.debug(`Reticulum: no answer from ${short(p.peer)} to our link request`);
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
      if (peer.link || this.pendingFor(key)) { peer.fallbackAt = 0; continue; }
      if (peer.fallbackAt && now >= peer.fallbackAt) {
        peer.fallbackAt = 0;
        // Nothing has dialled us since we heard it: it has not heard us, so we dial, now and on every redial.
        if (this.acceptedConfirmed === peer.acceptedMark) {
          peer.takeover = true;
          peer.dialAt = 0;
          this.debug(`Reticulum: ${short(key)} has not dialled us; dialling it instead`);
        }
      }
      // Redial for a while after it was last heard; after that its next announce does it.
      const dialler = this.weDial(peer.announce.destination) || peer.takeover;
      if (dialler && now >= peer.dialAt && now - peer.seenAt < REDIAL_MS) this.linkTo(key, peer);
    }
  }
}

module.exports = { ReticulumTransport, AnnounceBudget, IDLE_ANNOUNCE_MS, FALLBACK_MS, REDIAL_MS, RATE_TARGET_MS, RATE_GRACE, LINK_TIMEOUT_MS, STALE_MS, CONFIRM_MS, GRACE_MS, STABLE_MS, GATE_BURST, MAX_LINKS, MAX_PEERS };
