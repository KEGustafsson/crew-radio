// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The announcement queue: one thing on the channel at a time, in the order asked, except that
 * an urgent announcement goes to the front and cuts short a normal one that is playing. Items
 * are whatever `play` understands; `play(item, cancelled)` must resolve when the item is done
 * and check `cancelled()` (or be interrupted through `onCancel`).
 *
 * Both kinds are bounded: `max` normal items may wait, `maxUrgent` urgent ones (a smaller
 * number, since each urgent item cuts whatever plays). hasRoom() lets the caller ask before it
 * spends the work of making the item, since speech is synthesised before it is queued.
 */

const { EventEmitter } = require("node:events");

class AnnouncementQueue extends EventEmitter {
  /**
   * @param {object} opts
   * @param {(item: any, cancelled: () => boolean) => Promise<void>} opts.play
   * @param {() => void} [opts.onCancel]   asked to interrupt the item playing now
   * @param {number} [opts.max=20]         normal items waiting beyond this are refused
   * @param {number} [opts.maxUrgent=5]    urgent items waiting beyond this are refused
   * @param {(msg: string) => void} [opts.log]
   */
  constructor(opts) {
    super();
    this.play = opts.play;
    this.onCancel = opts.onCancel ?? (() => {});
    this.max = opts.max ?? 20;
    this.maxUrgent = opts.maxUrgent ?? 5;
    this.log = opts.log ?? (() => {});
    this.items = [];
    this.reserved = { normal: 0, urgent: 0 };
    this.current = null;
    this.pumping = false;
    this.stopped = false;
  }

  get size() { return this.items.length; }

  /** How many items of `priority` are waiting. */
  waiting(priority = "normal") {
    const p = priority === "urgent" ? "urgent" : "normal";
    return this.items.reduce((n, e) => n + (e.priority === p ? 1 : 0), 0);
  }

  /** True when an item of `priority` would be accepted right now, reservations counted. */
  hasRoom(priority = "normal") {
    if (this.stopped) return false;
    const p = priority === "urgent" ? "urgent" : "normal";
    return this.waiting(p) + this.reserved[p] < (p === "urgent" ? this.maxUrgent : this.max);
  }

  /**
   * Takes a slot for `priority` and returns the function that gives it back, or null when the
   * queue is full.
   *
   * hasRoom() alone is not enough for a caller that makes its item before queueing it: the speech
   * is synthesised across an await, and another caller can take the last slot in between, so the
   * first one would find the queue full only after spending the work. The reservation holds the
   * slot across that await. Release it and enqueue in the same synchronous step, so nothing can
   * slip in between; releasing twice, or after the queue has stopped, does nothing.
   */
  reserve(priority = "normal") {
    if (this.stopped) return null;
    const p = priority === "urgent" ? "urgent" : "normal";
    if (!this.hasRoom(p)) return null;
    this.reserved[p]++;
    let done = false;
    return () => {
      if (done) return;
      done = true;
      this.reserved[p] = Math.max(0, this.reserved[p] - 1);
    };
  }

  /**
   * Adds an item. Returns its position: 0 = playing now (or next, when nothing plays), else the
   * number of items ahead of it. Throws when the queue is full for that priority.
   */
  enqueue(item, priority = "normal") {
    if (this.stopped) throw new Error("queue stopped");
    const entry = { item, priority: priority === "urgent" ? "urgent" : "normal", cancelled: false };
    if (entry.priority === "urgent") {
      if (this.waiting("urgent") >= this.maxUrgent) throw new Error(`queue full (${this.maxUrgent} urgent waiting)`);
      const firstNormal = this.items.findIndex((e) => e.priority === "normal");
      const at = firstNormal < 0 ? this.items.length : firstNormal;
      this.items.splice(at, 0, entry);
      if (this.current && this.current.priority === "normal" && !this.current.cancelled) {
        this.log("urgent announcement: interrupting the one playing");
        this.current.cancelled = true;
        this.onCancel();
      }
      this.pump();
      return at;
    }
    if (this.waiting("normal") >= this.max) throw new Error(`queue full (${this.max} waiting)`);
    this.items.push(entry);
    this.pump();
    return this.items.length - 1 + (this.current ? 1 : 0);
  }

  /** Drops everything waiting and interrupts what plays. */
  clear() {
    this.items.length = 0;
    if (this.current) { this.current.cancelled = true; this.onCancel(); }
  }

  stop() {
    this.stopped = true;
    this.reserved.normal = 0;
    this.reserved.urgent = 0;                      // the syntheses still in flight will not queue
    this.clear();
  }

  async pump() {
    if (this.pumping) return;
    this.pumping = true;
    try {
      while (this.items.length > 0 && !this.stopped) {
        const entry = this.items.shift();
        this.current = entry;
        this.emit("started", entry.item, entry.priority);
        try {
          await this.play(entry.item, () => entry.cancelled);
        } catch (e) {
          this.log(`announcement failed: ${e.message}`);
        } finally {
          this.current = null;
          this.emit("done", entry.item, entry.priority, entry.cancelled);
        }
      }
    } finally {
      this.pumping = false;
    }
  }
}

module.exports = { AnnouncementQueue };
