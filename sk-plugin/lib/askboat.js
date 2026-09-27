// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Answers "Ask boat data" questions that arrive over Reticulum (lib/rns/ask.js has the frames): a
 * phone ashore cannot reach the Signal K server's HTTP port on the boat's LAN, so it asks the
 * plugin on the link it already holds, and the plugin reads the server's own tree in-process.
 *
 * Two questions, the two things the app's SignalKClient does over HTTP: read some top-level
 * branches of vessels.self (each leaf cut to value, timestamp and $source, which is all the app's
 * SignalKTree reads, then deflated), and say a sentence to the whole crew through the plugin's
 * own say(). Nothing else of the server is reachable this way.
 *
 * Off unless the plugin's setting turns it on: over HTTP the server's own security decides who
 * reads the boat's data, and over Reticulum it is anyone holding the channel key. Each link has a
 * budget of its own on top of say()'s.
 */

const zlib = require("node:zlib");
const Ask = require("./rns/ask");
const { SourceLimiter } = require("./ratelimit");

/** A top-level branch name as Signal K writes them: navigation, environment, electrical... */
const BRANCH = /^[A-Za-z][A-Za-z0-9]{0,63}$/;
const MAX_BRANCHES = 16;
/** Deeper than any Signal K tree goes: a cycle or a pathological value stops here. */
const MAX_DEPTH = 16;
const PER_MINUTE = 30;
const LIMITER_SOURCES = 64;
const MAX_REASON = 200;

/**
 * A branch of the full model with only what an answer needs. A leaf is a node with a `value`
 * (kept whole: a position is an object), its timestamp and source beside it; `meta` and the
 * per-source `values` go, and so does anything that is not a branch or a leaf.
 */
function prune(node, depth = 0) {
  if (!node || typeof node !== "object" || Array.isArray(node) || depth > MAX_DEPTH) return undefined;
  if (Object.hasOwn(node, "value")) {
    const leaf = { value: node.value };
    if (typeof node.timestamp === "string") leaf.timestamp = node.timestamp;
    if (typeof node.$source === "string") leaf.$source = node.$source;
    return leaf;
  }
  const out = {};
  for (const [key, child] of Object.entries(node)) {
    if (key === "meta" || key === "values") continue;
    const p = prune(child, depth + 1);
    if (p !== undefined) out[key] = p;
  }
  return Object.keys(out).length ? out : undefined;
}

/** The branches a read asks for, or null when the request is not one this plugin will read. */
function branchesOf(body) {
  const names = body.toString("utf8").split("\n").map((s) => s.trim()).filter(Boolean);
  if (names.length === 0 || names.length > MAX_BRANCHES || !names.every((n) => BRANCH.test(n))) return null;
  return [...new Set(names)];
}

class BoatAnswers {
  /**
   * @param {object} o
   * @param {() => boolean} o.enabled         the plugin setting, read on every question
   * @param {(path: string) => any} o.getSelfPath   the server's app.getSelfPath
   * @param {(opts: {text: string, priority: string}) => Promise<any>} o.say   the plugin's say(), source already bound
   * @param {() => number} [o.now]
   */
  constructor(o) {
    this.enabled = o.enabled;
    this.getSelfPath = o.getSelfPath;
    this.say = o.say;
    this.limiter = new SourceLimiter({ perMinute: PER_MINUTE, now: o.now });
    this.stats = { reads: 0, says: 0, refused: 0 };
  }

  /** The answer message for one request message from the link `source` (its key). Never rejects. */
  async handle(message, source) {
    if (!this.enabled()) { this.stats.refused++; return Ask.answer(Ask.Status.OFF); }
    if (!this.allow(source)) return Ask.answer(Ask.Status.BUSY);
    if (!Buffer.isBuffer(message) || message.length < 1) return failed("empty question");
    const body = message.subarray(1);
    try {
      switch (message[0]) {
        case Ask.Op.READ: return this.read(body);
        case Ask.Op.SAY: return await this.speak(body);
        default: return failed(`unknown question ${message[0]}`);
      }
    } catch (e) {
      return failed(e && e.message ? e.message : String(e));
    }
  }

  read(body) {
    const branches = branchesOf(body);
    if (!branches) return failed("not a list of Signal K branches");
    const tree = {};
    for (const b of branches) {
      const p = prune(this.getSelfPath(b));
      if (p !== undefined) tree[b] = p;
    }
    this.stats.reads++;
    return Ask.answer(Ask.Status.OK, zlib.deflateSync(Buffer.from(JSON.stringify(tree), "utf8")));
  }

  async speak(body) {
    const text = body.toString("utf8");
    try {
      await this.say({ text, priority: "normal" });
    } catch (e) {
      const why = e && e.message ? e.message : String(e);
      // say() refuses for its own rate budget or a full queue: worth asking again later, not a fault.
      if (/rate limit|queue full/.test(why)) return Ask.answer(Ask.Status.BUSY);
      return failed(why);
    }
    this.stats.says++;
    return Ask.answer(Ask.Status.OK);
  }

  allow(source) {
    const buckets = this.limiter.buckets;
    // Links come and go (a fresh id each time); a bucket for every one ever seen would grow for ever.
    if (!buckets.has(source) && buckets.size >= LIMITER_SOURCES) buckets.delete(buckets.keys().next().value);
    return this.limiter.allow(source);
  }
}

function failed(why) {
  return Ask.answer(Ask.Status.FAILED, Buffer.from(String(why).slice(0, MAX_REASON), "utf8"));
}

module.exports = { BoatAnswers, prune, branchesOf, PER_MINUTE, MAX_BRANCHES };
