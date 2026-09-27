// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Asking the boat over a Reticulum link: a phone ashore puts the question "Ask boat data" asks
 * over HTTP on the boat's LAN to this plugin instead, on the link it already holds to it. Beside
 * the channel's own frames (carry.js: 0x01 whole, 2-3 parts, 0x80 key proof) a link carries two
 * more kinds, cut the same way both ways:
 *
 *   request part: 0x81 | id u16 | index u8 | count u8 | bytes
 *   answer part:  0x82 | id u16 | index u8 | count u8 | bytes
 *
 * The parts of one message, joined in index order, are:
 *
 *   request: op u8 | body     op 1 = read: the top-level branches of vessels.self wanted, UTF-8,
 *                                     one per line ("navigation\nenvironment")
 *                             op 2 = say: the text to announce on the channel, UTF-8
 *   answer:  status u8 | body status 0 = ok: for a read, zlib-deflated UTF-8 JSON {branch: tree},
 *                                     each leaf cut to value, timestamp and $source; for a say, empty
 *                             status 1 = this boat does not answer questions over Reticulum (setting)
 *                             status 2 = busy: over the rate budget, or the announcement queue full
 *                             status 3 = failed, body a UTF-8 reason
 *
 * Only a confirmed link carries either (the far end has proved the channel key), so a question is
 * the crew's. The asker sends on every confirmed link and takes the first "ok"; a phone ignores
 * requests, the plugin answers them. A lost part loses the message, so the asker sends the same
 * request again, same id, and the plugin answers a repeat from its memory of recent answers rather
 * than doing it twice (a say must not be announced twice). Parts may arrive in any order.
 * The same format as the app's fi.crewradio.rns.AskCarry.
 */

const { MDU } = require("./link");

const REQUEST = 0x81;
const ANSWER = 0x82;
const HEAD = 5;
const ROOM = MDU - HEAD;
/** The most parts a message of each kind may have: a say of 500 characters, a read of a few branches. */
const MAX_REQUEST_PARTS = 4;
const MAX_ANSWER_PARTS = 64;
/** Messages being put together on one link at a time; the oldest goes when a new one needs room. */
const IN_FLIGHT = 4;

const Op = Object.freeze({ READ: 1, SAY: 2 });
const Status = Object.freeze({ OK: 0, OFF: 1, BUSY: 2, FAILED: 3 });

function maxParts(kind) {
  return kind === REQUEST ? MAX_REQUEST_PARTS : MAX_ANSWER_PARTS;
}

/** True when a link payload is a part of a request or an answer. */
function isAsk(payload) {
  return Buffer.isBuffer(payload) && payload.length > HEAD && (payload[0] === REQUEST || payload[0] === ANSWER);
}

/** The link payloads for one message of `kind` (REQUEST or ANSWER) with the id `id`. Throws when it is too large. */
function cut(kind, id, message) {
  const count = Math.max(1, Math.ceil(message.length / ROOM));
  if (count > maxParts(kind)) throw new Error(`message of ${message.length} bytes is too large to carry`);
  const out = [];
  for (let i = 0; i < count; i++) {
    const head = Buffer.from([kind, (id >> 8) & 0xff, id & 0xff, i, count]);
    out.push(Buffer.concat([head, message.subarray(i * ROOM, Math.min(message.length, (i + 1) * ROOM))]));
  }
  return out;
}

/**
 * Puts one link's messages of one kind back together, parts in any order. `push` returns
 * {id, message} once the last part is in, null meanwhile and for anything malformed.
 */
class Assembler {
  constructor(kind) {
    this.kind = kind;
    this.pending = new Map();   // id -> {count, parts: Buffer[], have}
  }

  push(payload) {
    if (!isAsk(payload) || payload[0] !== this.kind) return null;
    const id = payload.readUInt16BE(1);
    const index = payload[3];
    const count = payload[4];
    if (count < 1 || count > maxParts(this.kind) || index >= count) return null;
    const body = Buffer.from(payload.subarray(HEAD));
    if (count === 1) {
      this.pending.delete(id);
      return { id, message: body };
    }
    let m = this.pending.get(id);
    if (m && m.count !== count) { this.pending.delete(id); m = null; }
    if (!m) {
      if (this.pending.size >= IN_FLIGHT) this.pending.delete(this.pending.keys().next().value);
      m = { count, parts: new Array(count).fill(null), have: 0 };
      this.pending.set(id, m);
    }
    if (m.parts[index]) return null;                  // a copy of a part already in
    m.parts[index] = body;
    if (++m.have < count) return null;
    this.pending.delete(id);
    return { id, message: Buffer.concat(m.parts) };
  }
}

/** A request message: op and body. */
function request(op, body) {
  return Buffer.concat([Buffer.from([op]), body]);
}

/** An answer message: status and body. */
function answer(status, body = Buffer.alloc(0)) {
  return Buffer.concat([Buffer.from([status]), body]);
}

module.exports = { REQUEST, ANSWER, HEAD, ROOM, MAX_REQUEST_PARTS, MAX_ANSWER_PARTS, IN_FLIGHT, Op, Status, isAsk, cut, Assembler, request, answer };
