// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Who carries a boat's voice between its local transports and Reticulum, and where a frame goes:
 * the rules every client keeps the same (the app's, this plugin's and the ESP32's), so that a boat
 * with several bridges puts one copy of each frame through the hub instead of one per bridge.
 * Nothing here is a message: every node works it out from the hellos it hears, and the rules are
 * written so that two nodes that briefly see it differently make a duplicate (which the seen-cache
 * drops), never a gap.
 *
 *  - Local peers. A sender is local when, within LOCAL_MS, a hello of its own came over a local
 *    transport (the LAN, here) unrelayed: hops == ttl in its header, so nobody forwarded it.
 *  - The forwarder. Candidates are this node, when it is on the Reticulum hub right now (RNS_UP),
 *    and every local sender whose last local hello said RNS_UP. The highest rank wins (FWD_PREFER
 *    2, neither 1, FWD_AVOID 0), ties to the lowest senderId compared as unsigned 32-bit: the id is
 *    an int32 on the wire, and a signed compare would make 0x80000000 the lowest of all.
 *  - Audio (hellos keep their old relay rules, in node.js): our own voice goes everywhere; a frame
 *    first heard locally goes to Reticulum only from the forwarder, and only for a talker that is
 *    not on Reticulum itself (it sends its own voice there); a frame first heard over Reticulum
 *    goes to the local transports only from the forwarder; Reticulum to Reticulum, never.
 *  - Split horizon: on Reticulum, a link whose far end is a local sender gets no audio (that node
 *    hears it locally), nor does the talker's own link; a link whose far end is not known yet does.
 */

const { Transports } = require("./packet");

const LOCAL_MS = 15_000;   // a local hello counts this long
const MAX_LOCAL = 64;      // local senders remembered at most (the roster's own ceiling)

/** A hello's transports byte as a forwarder rank: FWD_PREFER 2, neither 1, FWD_AVOID 0. */
function rank(transports) {
  if (transports & Transports.FWD_PREFER) return 2;
  if (transports & Transports.FWD_AVOID) return 0;
  return 1;
}

/**
 * The forwarder among `candidates` ({senderId, transports} each): the highest rank, ties to the
 * lowest senderId as unsigned 32-bit. Null when there is no candidate.
 */
function elect(candidates) {
  let best = null;
  for (const c of candidates) {
    if (best === null) { best = c; continue; }
    const r = rank(c.transports);
    const rb = rank(best.transports);
    if (r > rb || (r === rb && (c.senderId >>> 0) < (best.senderId >>> 0))) best = c;
  }
  return best === null ? null : best.senderId;
}

/**
 * Where an audio frame goes (D1-D4). `origin` is "own" (our voice), "local" (first heard on a
 * local transport) or "rns" (first heard over Reticulum); `forwarder` whether we are the boat's
 * forwarder; `senderRnsUp` whether the talker's last local hello said RNS_UP. Returns
 * {local, rns}: whether it goes to the local transports and to Reticulum (each Reticulum link
 * then subject to linkTakes()).
 */
function route({ origin, forwarder, senderRnsUp }) {
  if (origin === "own") return { local: true, rns: true };
  if (origin === "local") return { local: false, rns: !!forwarder && !senderRnsUp };
  if (origin === "rns") return { local: !!forwarder, rns: false };
  return { local: false, rns: false };
}

/**
 * Split horizon for one Reticulum link: whether the link whose far end is `farEnd` (null: not
 * learnt yet) gets an audio frame from `sender`. Not when its far end is local (it hears the boat
 * itself), nor when it is the talker (it has its own frame); an unknown far end gets it.
 */
function linkTakes(farEnd, sender, isLocal) {
  if (farEnd === null || farEnd === undefined) return true;
  if ((farEnd | 0) === (sender | 0)) return false;
  return !isLocal(farEnd);
}

/** The senders heard locally: for each, when its last local unrelayed hello came, and its transports byte. */
class LocalPeers {
  /** @param {{now?: () => number, localMs?: number, max?: number}} [o] */
  constructor(o = {}) {
    this.now = o.now ?? Date.now;
    this.localMs = o.localMs ?? LOCAL_MS;
    this.max = o.max ?? MAX_LOCAL;
    this.peers = new Map();   // senderId -> {at, transports}, oldest first
  }

  /** A hello from `senderId` came over a local transport, unrelayed. */
  heard(senderId, transports) {
    const id = senderId | 0;
    this.peers.delete(id);
    this.peers.set(id, { at: this.now(), transports: transports & 0xff });
    while (this.peers.size > this.max) this.peers.delete(this.peers.keys().next().value);
  }

  /** The entry while it is fresh, else null. */
  get(senderId) {
    const p = this.peers.get(senderId | 0);
    return p && this.now() - p.at <= this.localMs ? p : null;
  }

  isLocal(senderId) {
    return this.get(senderId) !== null;
  }

  /** True when the sender is local and its last local hello said RNS_UP. */
  rnsUp(senderId) {
    const p = this.get(senderId);
    return !!p && (p.transports & Transports.RNS_UP) !== 0;
  }

  /** Forgets the entries past LOCAL_MS. */
  sweep() {
    for (const [id, p] of this.peers) if (this.now() - p.at > this.localMs) this.peers.delete(id);
  }

  /**
   * The boat's forwarder as this node sees it, `me` being {senderId, transports} with our own
   * hello's transports byte: us when RNS_UP, and every local sender whose hello said RNS_UP.
   */
  forwarder(me) {
    const candidates = [];
    if (me.transports & Transports.RNS_UP) candidates.push(me);
    for (const [senderId, p] of this.peers) {
      if (this.now() - p.at <= this.localMs && (p.transports & Transports.RNS_UP)) candidates.push({ senderId, transports: p.transports });
    }
    return elect(candidates);
  }

  /** The local senders now, for the status page. */
  list() {
    const out = [];
    for (const [senderId, p] of this.peers) if (this.now() - p.at <= this.localMs) out.push({ senderId, transports: p.transports });
    return out;
  }
}

module.exports = { LocalPeers, elect, rank, route, linkTakes, LOCAL_MS, MAX_LOCAL };
