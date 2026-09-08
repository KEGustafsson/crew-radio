// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Signal K notifications to spoken announcements. A notification at or above the configured
 * state, and (optionally) only one that asks for sound, is said through say() when it appears,
 * when its message or state changes, and again every `repeatSec` while it stays raised.
 * Emergency (configurable) is said as urgent, which goes to the front of the queue and cuts a
 * normal announcement short. It stops when the state drops back to normal or the notification
 * is cleared.
 *
 * A say() that fails (the queue full, the rate limit, the engine down) is tried once more
 * `retryMs` later by its own timer, whatever the repeat setting: an alarm said once must still
 * be said. say() truncates an over-long text rather than refusing it, and reports so.
 */

const { EventEmitter } = require("node:events");

const RANK = Object.freeze({ nominal: 0, normal: 0, alert: 1, warn: 2, alarm: 3, emergency: 4 });
const RETRY_MS = 5000;

/** `table[key]` for the table's own keys only: a state named like an Object.prototype member is unknown. */
const lookup = (table, key) => (typeof key === "string" && Object.hasOwn(table, key) ? table[key] : undefined);

class NotificationBridge extends EventEmitter {
  /**
   * @param {object} opts
   * @param {(o:{text:string,priority?:string}) => Promise<any>} opts.say  resolves on enqueue; rejects when nothing could be queued
   * @param {object} opts.rules
   * @param {string} [opts.rules.minState="alarm"]
   * @param {boolean} [opts.rules.soundOnly=true]   only notifications whose method includes "sound"
   * @param {number} [opts.rules.repeatSec=30]      0 = say once
   * @param {string[]} [opts.rules.urgentStates=["emergency"]]
   * @param {string[]} [opts.rules.include=[]]      path globs under notifications., empty = all
   * @param {string[]} [opts.rules.exclude=[]]
   * @param {boolean} [opts.rules.sayPath=true]     say the state and the path before the message
   * @param {number} [opts.retryMs=5000]            how long after a failed say() it is tried again
   * @param {(msg:string)=>void} [opts.log]
   * @param {() => number} [opts.now]
   */
  constructor(opts) {
    super();
    this.say = opts.say;
    const r = opts.rules ?? {};
    this.minRank = lookup(RANK, r.minState) ?? RANK.alarm;
    this.soundOnly = r.soundOnly ?? true;
    this.repeatMs = Math.max(0, (r.repeatSec ?? 30) * 1000);
    this.urgent = new Set(r.urgentStates ?? ["emergency"]);
    this.include = (r.include ?? []).map(globToRegExp);
    this.exclude = (r.exclude ?? []).map(globToRegExp);
    this.sayPath = r.sayPath ?? true;
    this.retryMs = opts.retryMs ?? RETRY_MS;
    this.log = opts.log ?? (() => {});
    this.now = opts.now ?? Date.now;
    this.active = new Map(); // path -> {state, message, lastSaidAt, count, retry}
    this.timer = null;
    this.stopped = false;
  }

  start() {
    this.stopped = false;
    this.timer = setInterval(() => this.repeatDue(), 1000);
    if (this.timer.unref) this.timer.unref();
  }

  stop() {
    this.stopped = true;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    for (const entry of this.active.values()) this.clearRetry(entry);
    this.active.clear();
  }

  /** One Signal K delta, as the subscription manager delivers it. */
  onDelta(delta) {
    for (const u of delta?.updates ?? []) {
      for (const v of u.values ?? []) {
        if (typeof v.path === "string" && v.path.startsWith("notifications.")) this.handle(v.path, v.value);
      }
    }
  }

  /** A notification value for `path`: raised, changed, or cleared. */
  handle(path, value) {
    const state = value && typeof value === "object" ? String(value.state ?? "normal") : "normal";
    const rank = lookup(RANK, state) ?? RANK.normal;
    if (!value || rank === 0) {
      if (this.forget(path)) {
        this.log(`cleared: ${path}`);
        this.emit("cleared", path);
      }
      return;
    }
    if (rank < this.minRank) { this.forget(path); return; }
    if (this.soundOnly && !methodsOf(value).includes("sound")) return;
    if (!this.matches(path)) return;
    const message = typeof value.message === "string" && value.message.trim() ? value.message.trim() : humanise(path);
    const cur = this.active.get(path);
    if (cur && cur.state === state && cur.message === message) return; // unchanged: the repeat timer owns it
    if (cur) this.clearRetry(cur);
    const entry = { state, message, lastSaidAt: 0, count: 0, retry: null };
    this.active.set(path, entry);
    this.announce(path, entry);
  }

  /** Drops a path from the active set, with any retry pending for it; true when it was there. */
  forget(path) {
    const entry = this.active.get(path);
    if (!entry) return false;
    this.clearRetry(entry);
    this.active.delete(path);
    return true;
  }

  clearRetry(entry) {
    if (entry.retry) { clearTimeout(entry.retry); entry.retry = null; }
  }

  matches(path) {
    const rel = path.slice("notifications.".length);
    if (this.exclude.some((re) => re.test(rel))) return false;
    return this.include.length === 0 || this.include.some((re) => re.test(rel));
  }

  repeatDue() {
    if (this.repeatMs === 0) return;
    const now = this.now();
    for (const [path, entry] of this.active) {
      if (!entry.retry && now - entry.lastSaidAt >= this.repeatMs) this.announce(path, entry);
    }
  }

  announce(path, entry) {
    this.clearRetry(entry);
    entry.lastSaidAt = this.now();
    entry.count++;
    const priority = this.urgent.has(entry.state) ? "urgent" : "normal";
    const opts = { text: this.sayPath ? spoken(path, entry.state, entry.message) : entry.message, priority };
    this.emit("announce", { path, state: entry.state, message: entry.message, count: entry.count, priority });
    Promise.resolve()
      .then(() => this.say(opts))
      .then((result) => {
        if (result && result.truncated) this.log(`say for ${path}: the text was cut to fit`);
        if (result && result.ok === false) this.log(`say for ${path}: ${JSON.stringify(result)}`);
      })
      .catch((e) => {
        this.log(`say for ${path} failed: ${e.message}`);
        // One more go on its own timer, so an alarm set to be said once is still said.
        if (this.stopped || this.active.get(path) !== entry) return;
        entry.retry = setTimeout(() => {
          entry.retry = null;
          if (!this.stopped && this.active.get(path) === entry) this.announce(path, entry);
        }, this.retryMs);
        if (entry.retry.unref) entry.retry.unref();
      });
  }
}

function methodsOf(value) {
  const m = value.method;
  if (Array.isArray(m)) return m.map(String);
  if (typeof m === "string") return [m];
  return [];
}

const STATE_WORD = Object.freeze({ alert: "Alert", warn: "Warning", alarm: "Alarm", emergency: "Emergency" });

/**
 * What is said for a notification: the state, the path in words, then the message, so the crew
 * hears where an alarm comes from ("Alarm, navigation position: no contact with sensor for 70
 * seconds"). A notification without a message is the state and the path alone.
 */
function spoken(path, state, message) {
  const head = `${lookup(STATE_WORD, state) ?? state}, ${humanise(path)}`;
  return message && message !== humanise(path) ? `${head}: ${message}` : head;
}

/** "navigation.anchor.currentRadius" -> "navigation anchor current radius" */
function humanise(path) {
  return path
    .replace(/^notifications\./, "")
    .split(".")
    .map((s) => s.replace(/([a-z0-9])([A-Z])/g, "$1 $2").toLowerCase())
    .join(" ");
}

/** `*` matches within one segment, `**` across segments; everything else is literal. */
function globToRegExp(glob) {
  const esc = String(glob).replace(/[.+?^${}()|[\]\\]/g, "\\$&").replace(/\*\*/g, "__DOUBLESTAR__").replace(/\*/g, "[^.]*").replace(/__DOUBLESTAR__/g, ".*");
  return new RegExp(`^${esc}$`);
}

module.exports = { NotificationBridge, RANK, RETRY_MS, humanise, spoken, globToRegExp };
