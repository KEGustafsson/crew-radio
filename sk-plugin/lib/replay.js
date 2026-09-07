// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Replay and duplicate suppression for authenticated packets, the plugin's counterpart of the
 * app's seen-caches and SeqTracker. Two rules, both per sender and per kind (audio frames and
 * hellos number themselves independently):
 *
 *  - a seen-cache of (sender, seq): the second WLAN copy of every packet (multicast and
 *    broadcast) and relay echoes are "seen" and dropped;
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
    this.audio = new Map();                       // "sender:seq" -> true, insertion ordered
    this.hello = new Map();
    this.audioCapacity = opts.audioCapacity ?? AUDIO_CAPACITY;
    this.helloCapacity = opts.helloCapacity ?? HELLO_CAPACITY;
    this.senders = opts.senders ?? SENDERS;
    this.marks = new Map();                       // senderId -> {audio: seq|null, hello: seq|null}, LRU
  }

  /**
   * Admits `seq` of `kind` ("audio" or "hello") from `senderId`: "new" when it advances the
   * sender's sequence (and is now remembered), "seen" for a copy of a packet already admitted,
   * "late" for a number the sender has moved past.
   */
  admit(senderId, seq, kind) {
    const cache = kind === "hello" ? this.hello : this.audio;
    const capacity = kind === "hello" ? this.helloCapacity : this.audioCapacity;
    const key = `${senderId}:${seq}`;
    if (cache.has(key)) return "seen";
    let m = this.marks.get(senderId);
    if (m) {
      this.marks.delete(senderId);                // most recently used last
    } else {
      m = { audio: null, hello: null };
      if (this.marks.size >= this.senders) this.marks.delete(this.marks.keys().next().value);
    }
    this.marks.set(senderId, m);
    const prev = m[kind];
    if (prev !== null && ((seq - prev) | 0) <= 0) return "late";   // signed 32-bit distance: wraps with the counter
    m[kind] = seq | 0;
    cache.set(key, true);
    if (cache.size > capacity) cache.delete(cache.keys().next().value);
    return "new";
  }
}

module.exports = { ReplayGuard, AUDIO_CAPACITY, HELLO_CAPACITY, SENDERS };
