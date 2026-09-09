// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The ingress budgets for packets off the wire, mirroring the app's RateLimiter and
 * SourceLimiter. The plugin had neither, and it is the one node that runs inside somebody
 * else's process: a Signal K server is single-threaded and carries the boat's NMEA, AIS and
 * autopilot deltas, so a flood that costs nothing to send costs the whole vessel its data.
 * No channel key is needed for it - a well-formed header and 29 bytes of garbage are enough to
 * make the server attempt an AES-GCM open per packet.
 *
 * Two classes, both pure, both with an injectable clock:
 *
 * `WireLimiter` is the app's three buckets. A global one bounds what is looked at in total,
 * charged before the packet is opened, because the sender id it claims is not to be trusted. A
 * junk one is charged only when the open *fails*, so unreadable traffic is bounded without
 * starving the crew. A per-sender one is charged after the AEAD and after the duplicate look,
 * so only authenticated first copies cost a sender anything and a forged id can neither buy
 * more nor starve a real one.
 *
 * `PeerBudget` is one bucket per source address, charged on the socket before any of that: the
 * global bucket is blind to who sent what, so without this one host that simply asks faster
 * takes the whole budget. It evicts the least recently used rather than refusing a newcomer, so
 * a flood that rotates its address cannot lock the crew out by filling the table.
 */

class Bucket {
  constructor(tokens, at) { this.tokens = tokens; this.at = at; }
}

function take(b, now, perSecond, burst) {
  const elapsed = Math.max(0, now - b.at);
  b.tokens = Math.min(burst, b.tokens + (elapsed * perSecond) / 1000);
  b.at = now;
  if (b.tokens < 1) return false;
  b.tokens -= 1;
  return true;
}

class WireLimiter {
  /**
   * @param {object} [opts]
   * @param {number} [opts.globalPerSecond=5000] @param {number} [opts.globalBurst=2000]
   * @param {number} [opts.junkPerSecond=200]    @param {number} [opts.junkBurst=400]
   * @param {number} [opts.perSecond=75]         @param {number} [opts.burst=150]
   * @param {number} [opts.maxSenders=128]       @param {number} [opts.forgetMs=10000]
   * @param {() => number} [opts.now]            milliseconds
   */
  constructor(opts = {}) {
    this.globalPerSecond = opts.globalPerSecond ?? 5000;
    this.globalBurst = opts.globalBurst ?? 2000;
    this.junkPerSecond = opts.junkPerSecond ?? 200;
    this.junkBurst = opts.junkBurst ?? 400;
    this.perSecond = opts.perSecond ?? 75;
    this.burst = opts.burst ?? 150;
    this.maxSenders = opts.maxSenders ?? 128;
    this.forgetMs = opts.forgetMs ?? 10_000;
    this.now = opts.now ?? Date.now;
    this.global = new Bucket(this.globalBurst, 0);
    this.junk = new Bucket(this.junkBurst, 0);
    this.senders = new Map();
    this.sweptAt = 0;
  }

  /** The global budget, for a packet that has not been opened yet. */
  allowGlobal() {
    return take(this.global, this.now(), this.globalPerSecond, this.globalBurst);
  }

  /** Charges the junk budget for a packet that did not open; false once it is exhausted. */
  allowJunk() {
    return take(this.junk, this.now(), this.junkPerSecond, this.junkBurst);
  }

  /** The per-sender budget, for a sender id the AEAD has authenticated. */
  allowSender(senderId) {
    const now = this.now();
    if (now - this.sweptAt > this.forgetMs) {
      for (const [id, b] of this.senders) if (now - b.at > this.forgetMs) this.senders.delete(id);
      this.sweptAt = now;
    }
    let b = this.senders.get(senderId);
    if (!b) {
      if (this.senders.size >= this.maxSenders) return false;   // table full: no state for a newcomer
      b = new Bucket(this.burst, now);
      this.senders.set(senderId, b);
    }
    return take(b, now, this.perSecond, this.burst);
  }
}

class PeerBudget {
  /**
   * @param {object} [opts]
   * @param {number} [opts.perSecond=500] @param {number} [opts.burst=1000]
   * @param {number} [opts.maxSources=64] @param {() => number} [opts.now]
   */
  constructor(opts = {}) {
    this.perSecond = opts.perSecond ?? 500;
    this.burst = opts.burst ?? 1000;
    this.maxSources = opts.maxSources ?? 64;
    this.now = opts.now ?? Date.now;
    this.buckets = new Map();   // Map keeps insertion order: re-inserting on use makes it an LRU
  }

  /** Takes one token for `source`; false when that source is over its own budget. */
  allow(source) {
    const now = this.now();
    let b = this.buckets.get(source);
    if (b) {
      this.buckets.delete(source);                 // move to the young end
    } else {
      b = new Bucket(this.burst, now);
      if (this.buckets.size >= this.maxSources) this.buckets.delete(this.buckets.keys().next().value);
    }
    this.buckets.set(source, b);
    return take(b, now, this.perSecond, this.burst);
  }

  /** Forgets every source; the link calls this when it re-opens. */
  clear() { this.buckets.clear(); }
}

module.exports = { WireLimiter, PeerBudget };
