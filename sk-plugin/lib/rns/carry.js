// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * How Crew Radio packets ride inside a Reticulum link: the sealed packet unchanged, behind one
 * byte that says how it was cut. A link packet carries at most MDU (431) bytes of plaintext, an
 * Opus frame or a hello fits whole, and a PCM frame (686 bytes sealed) does not, so it goes in
 * two or three parts:
 *
 *   whole:  0x01 | packet
 *   part:   count (2-3) | index (0..count-1) | id u8 | bytes
 *
 * Parts are sent in order on one link and reassembled only in order: a missing or reordered part
 * drops that packet (it is 20 ms of audio, and the mixer conceals it), never the ones after it.
 * The same format as the app's fi.crewradio.rns.Carry.
 */

const { MDU } = require("./link");

const WHOLE = 1;
const MAX_PARTS = 3;
const PART_HEAD = 3;

/** The link payloads for one packet. `id` names its parts; the caller counts it per link. */
function cut(packet, id) {
  if (packet.length + 1 <= MDU) return [Buffer.concat([Buffer.from([WHOLE]), packet])];
  const room = MDU - PART_HEAD;
  const count = Math.ceil(packet.length / room);
  if (count > MAX_PARTS) throw new Error(`packet of ${packet.length} bytes is too large to carry`);
  const out = [];
  for (let i = 0; i < count; i++) {
    out.push(Buffer.concat([Buffer.from([count, i, id & 0xff]), packet.subarray(i * room, Math.min(packet.length, (i + 1) * room))]));
  }
  return out;
}

/** Reassembles one link's payloads; `push` returns a whole packet, or null while one is incomplete or for anything malformed. */
class Joiner {
  constructor() {
    this.pending = null;   // {count, id, next, parts}
  }

  push(payload) {
    if (!Buffer.isBuffer(payload) || payload.length < 2) return null;
    const count = payload[0];
    if (count === WHOLE) return Buffer.from(payload.subarray(1));
    if (count < 2 || count > MAX_PARTS || payload.length <= PART_HEAD) { this.pending = null; return null; }
    const index = payload[1];
    const id = payload[2];
    const body = payload.subarray(PART_HEAD);
    if (index === 0) {
      this.pending = { count, id, next: 1, parts: [body] };
      return null;
    }
    const p = this.pending;
    if (!p || p.id !== id || p.count !== count || p.next !== index) { this.pending = null; return null; }
    p.parts.push(body);
    p.next++;
    if (p.next < count) return null;
    this.pending = null;
    return Buffer.concat(p.parts);
  }
}

module.exports = { cut, Joiner, WHOLE, MAX_PARTS, PART_HEAD };
