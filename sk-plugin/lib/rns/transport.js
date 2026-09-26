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
 *  - Discovery. We announce on every (re)connect and every ANNOUNCE_MS. Of two nodes, the one
 *    whose destination hash sorts lower dials; the other, on hearing a node it did not know,
 *    announces again soon so the newcomer learns of it and dials. One link per pair.
 *  - Links, not group destinations: Reticulum does not carry group packets over more than one
 *    hop. A link carries hellos from the start and everything else once the far end has sent a
 *    packet that opened with the channel key (confirm()), so a stranger who copies our public
 *    name hash and links in learns nothing but that we exist.
 *  - Relaying: none within this transport. Every node links to every other, and Reticulum's own
 *    transport nodes do the multi-hop part; the engine relays between this and the LAN.
 *
 * Events: 'packet' (buf, via) with `via` the link it came on, 'status' (short line), 'state' (up: boolean).
 */

const net = require("node:net");
const { EventEmitter } = require("node:events");
const P = require("./packet");
const I = require("./identity");
const L = require("./link");
const Carry = require("./carry");
const { PeerBudget } = require("../wirelimit");

const ANNOUNCE_MS = 10 * 60_000;       // re-announce: keeps our path fresh on the transport nodes
const REANNOUNCE_MS = 3_000;           // answer to a newcomer, at most this often
const LINK_TIMEOUT_MS = 10_000;        // a request unproven this long is given up
const STALE_MS = 12_000;               // a link silent this long is dead (the engine sends a hello every second)
const CONFIRM_MS = 15_000;             // a link that has sent nothing sealed with the channel key by then is closed
const PEER_FORGET_MS = 3 * ANNOUNCE_MS;
const MAX_LINKS = 32;
const MAX_PEERS = 64;
const HELLO_CODEC = 2;

class ReticulumTransport extends EventEmitter {
  /**
   * @param {object} o
   * @param {string} o.host  @param {number} o.port   the transport node's TCP interface
   * @param {string} o.tag   hex tag from the packet key: the channel's destination aspect
   * @param {(host: string, port: number) => import('node:net').Socket} [o.connect]
   * @param {() => number} [o.now]
   * @param {I.Identity} [o.identity]
   */
  constructor(o) {
    super();
    this.host = o.host;
    this.port = o.port;
    this.connectFn = o.connect ?? ((host, port) => net.connect({ host, port }));
    this.now = o.now ?? Date.now;
    this.identity = o.identity ?? I.Identity.generate();
    this.nameHash = I.nameHash(`crewradio.channel.${o.tag}`);
    this.destination = I.destinationHash(this.nameHash, this.identity.hash);
    this.budget = new PeerBudget({ now: () => this.now(), perSecond: 400, burst: 800 });
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

  /** Links that carry channel traffic now. */
  get linkCount() {
    let n = 0;
    for (const e of this.links.values()) if (e.link.active) n++;
    return n;
  }

  /**
   * Sends a sealed channel packet on every active link but `except`; hellos go to links not yet
   * confirmed as well, so that each end can prove it holds the key. True when it went anywhere.
   */
  send(buf, except = null) {
    if (!this.connected) return false;
    const hello = buf.length > 3 && buf[3] === HELLO_CODEC;
    let sent = false;
    for (const e of this.links.values()) {
      if (e === except || !e.link.active || (!e.confirmed && !hello)) continue;
      for (const part of Carry.cut(buf, e.cutId++ & 0xff)) this.write(e.link.dataPacket(part));
      sent = true;
    }
    if (sent) this.stats.tx++;
    return sent;
  }

  /** Called by the engine once a packet from `via` opened with the channel key. */
  confirm(via) {
    if (via && this.links.get(via.key) === via) via.confirmed = true;
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
    s.on("connect", () => {
      if (this.sock !== s) return;
      this.connected = true;
      this.backoffMs = 1000;
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
    const key = p.destination.toString("hex");
    let peer = this.peers.get(key);
    if (!peer && this.peers.size >= MAX_PEERS) return;
    const a = I.parseAnnounce(p);
    if (!a) return;
    if (peer && a.emitted < peer.emitted) return;                                                                 // an older announce replayed
    const fresh = !peer;
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
    } else if (fresh) {
      // It dials us, but first it has to hear of us: announce again, soon, not on every newcomer.
      this.reannounceAt = Math.max(this.reannounceAt, this.lastAnnounce + REANNOUNCE_MS, this.now() + 500 + Math.random() * 1500);
    }
  }

  onLinkRequest(p) {
    if (p.destType !== P.DestType.SINGLE || !p.destination.equals(this.destination)) return;
    const id = L.linkIdOf(p.raw, p.data.length).toString("hex");
    if (this.links.has(id)) return;                                     // a copy of one we already answered
    if (this.links.size >= MAX_LINKS) return;
    const r = L.acceptLink(this.identity, p);
    if (!r) return;
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
  }

  onData(p) {
    if (p.destType !== P.DestType.LINK) return;
    const e = this.links.get(p.destination.toString("hex"));
    if (!e) return;
    const wasActive = e.link.active;
    const r = e.link.handle(p);
    if (!r) return;
    e.lastIn = this.now();
    if (!wasActive && e.link.active) this.linksChanged();
    if (r.kind === "keepalive" && r.reply) this.write(r.reply);
    else if (r.kind === "close") this.forget(e);
    else if (r.kind === "data") {
      const packet = e.joiner.push(r.plain);
      if (!packet || !this.budget.allow(e.key)) return;
      this.stats.rx++;
      this.emit("packet", packet, e);
    }
  }

  // ---- links ----

  weDial(peerDestination) {
    return Buffer.compare(this.destination, peerDestination) < 0;
  }

  pendingFor(peerKey) {
    for (const p of this.pending.values()) if (p.peer === peerKey) return true;
    return false;
  }

  linkTo(key, peer) {
    if (!this.connected || this.links.size + this.pending.size >= MAX_LINKS) return;
    const transportId = peer.hops > 1 ? peer.transportId : null;
    const req = L.requestLink(peer.announce, transportId);
    this.pending.set(req.id.toString("hex"), { req, peer: key, sentAt: this.now() });
    this.write(req.raw);
  }

  addLink(link, peerKey) {
    const e = { key: link.id.toString("hex"), link, peer: peerKey, createdAt: this.now(), lastIn: this.now(), confirmed: false, joiner: new Carry.Joiner(), cutId: 0 };
    this.links.set(e.key, e);
    this.stats.linksUp++;
    if (link.active) this.linksChanged();
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
    if (e.link.active) this.linksChanged();
    const peer = e.peer ? this.peers.get(e.peer) : null;
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

  /** Once a second: announces due, requests and links timed out, redials, forgotten peers. */
  tick() {
    if (!this.connected) return;
    const now = this.now();
    if (now - this.lastAnnounce >= ANNOUNCE_MS || (this.reannounceAt && now >= this.reannounceAt)) this.announce();
    for (const [id, p] of this.pending) {
      if (now - p.sentAt < LINK_TIMEOUT_MS) continue;
      this.pending.delete(id);
      const peer = this.peers.get(p.peer);
      if (peer) { peer.dialAt = now + peer.backoffMs; peer.backoffMs = Math.min(peer.backoffMs * 2, 15_000); }
    }
    for (const e of [...this.links.values()]) {
      if (now - e.lastIn > STALE_MS || (!e.confirmed && now - e.createdAt > CONFIRM_MS)) this.close(e);
    }
    for (const [key, peer] of this.peers) {
      if (!peer.link && now - peer.seenAt > PEER_FORGET_MS) { this.peers.delete(key); continue; }
      // Redial while it is still announcing (every ANNOUNCE_MS); after that its next announce does it.
      if (!peer.link && this.weDial(peer.announce.destination) && !this.pendingFor(key) && now >= peer.dialAt && now - peer.seenAt < ANNOUNCE_MS + 60_000) this.linkTo(key, peer);
    }
  }
}

module.exports = { ReticulumTransport, ANNOUNCE_MS, REANNOUNCE_MS, LINK_TIMEOUT_MS, STALE_MS, CONFIRM_MS, MAX_LINKS, MAX_PEERS };
