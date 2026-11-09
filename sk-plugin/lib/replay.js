// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Replay and duplicate suppression for authenticated packets, the plugin's counterpart of the
 * app's seen-caches and SeqTracker. Two rules, both per sender and per kind (audio frames and
 * hellos number themselves independently):
 *
 *  - a seen-cache of (sender, seq): the second WLAN copy of every packet (multicast and
 *    broadcast) and relay echoes are "seen" and dropped. The cache keeps the ttl each packet
 *    was forwarded with, and a later copy that would be forwarded further is "relay": relayed,
 *    not played or counted again (the app's Ingress.RelayOnly). The ttl is the one header byte
 *    a relay rewrites, outside the AAD, so a copy with a lowered ttl that arrives first - a
 *    bridge's copy with its hops spent, or a replay - must not silence the far side;
 *  - a wrap-aware high-water mark: a sequence number at or below the last one admitted is
 *    "late" and dropped, so a recorded packet cannot come back once the sender moved on, and
 *    a node's address can only be learnt from a packet that advances its sequence.
 *
 * The caches are sized for the 60 s replay window (a talker sends 50 frames a second, a node
 * one hello) and the marks are kept for the last 256 senders, for the guard's whole life: the
 * owner keeps one guard across link reopens, since forgetting on a reconnect is exactly the
 * window a replay would use.
 */

const AUDIO_CAPACITY = 16_384;
const HELLO_CAPACITY = 2048;
const SENDERS = 256;

class ReplayGuard {
  /** @param {{audioCapacity?: number, helloCapacity?: number, senders?: number}} [opts] */
  constructor(opts = {}) {
    this.audio = new Map();                       // "sender:seq" -> the ttl it was forwarded with, insertion ordered
    this.hello = new Map();
    this.audioCapacity = opts.audioCapacity ?? AUDIO_CAPACITY;
    this.helloCapacity = opts.helloCapacity ?? HELLO_CAPACITY;
    this.senders = opts.senders ?? SENDERS;
    this.marks = new Map();                       // senderId -> {audio: seq|null, hello: seq|null}, LRU
  }

  /**
   * Admits `seq` of `kind` ("audio" or "hello") from `senderId`, a copy to be forwarded with
   * `relayTtl` (0: not forwarded): "new" when it advances the sender's sequence (and is now
   * remembered), "seen" for a copy of a packet already admitted, "relay" for such a copy that
   * would be forwarded further than any before it (forward it, nothing more), "late" for a
   * number the sender has moved past. Each "relay" must beat the last, and packet.relayTtl
   * caps the ttl at the sender's signed budget and at our own hop limit, so a packet is
   * forwarded at most that limit of extra times. `allow`, when given, is the sender's budget,
   * asked only for a new packet, between the look and the write, as in the app's Ingress: a
   * packet it refuses is "rejected" and leaves nothing behind (neither the cache nor the
   * mark), so a later copy is judged afresh rather than relayed uncharged.
   */
  admit(senderId, seq, kind, relayTtl = 0, allow = null) {
    const cache = kind === "hello" ? this.hello : this.audio;
    const capacity = kind === "hello" ? this.helloCapacity : this.audioCapacity;
    const key = `${senderId}:${seq}`;
    const forwarded = cache.get(key);
    if (forwarded !== undefined) {
      if (relayTtl <= forwarded) return "seen";
      cache.set(key, relayTtl);
      return "relay";
    }
    let m = this.marks.get(senderId);
    const prev = m ? m[kind] : null;
    if (prev !== null && ((seq - prev) | 0) <= 0) {   // signed 32-bit distance: wraps with the counter
      this.touch(senderId, m);
      return "late";
    }
    // Asked before the mark table changes: a refused packet from a new sender must not make room
    // for itself by evicting another sender's mark, which would reopen that sender's old numbers.
    if (allow && !allow()) return "rejected";
    if (!m) {
      m = { audio: null, hello: null };
      if (this.marks.size >= this.senders) this.marks.delete(this.marks.keys().next().value);
    }
    this.touch(senderId, m);
    m[kind] = seq | 0;
    cache.set(key, relayTtl);
    if (cache.size > capacity) cache.delete(cache.keys().next().value);
    return "new";
  }

  /** Makes `senderId`'s mark the most recently used (last in the map's order). */
  touch(senderId, m) {
    this.marks.delete(senderId);
    this.marks.set(senderId, m);
  }
}

module.exports = { ReplayGuard, AUDIO_CAPACITY, HELLO_CAPACITY, SENDERS };
