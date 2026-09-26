// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Reticulum packets and the HDLC framing its TCP interfaces use, as the manual's "Wire Format"
 * section publishes them:
 *
 *   flags u8 | hops u8 | [transport id (16) when header type 2] | destination (16) | context u8 | data
 *
 *   flags = ifac(1 bit) | header type(1) | context flag(1) | propagation(1) | destination type(2) | packet type(2)
 *
 * Interface access codes are not supported: a hub that needs one drops everything we send, and
 * anything arriving with the IFAC bit set is ignored.
 *
 * On a TCP interface each packet travels as an HDLC-like frame: 0x7E, the packet with 0x7D and
 * 0x7E escaped as 0x7D followed by the byte XOR 0x20, 0x7E. No checksum: TCP has one.
 */

const { sha256 } = require("./crypto");

const MTU = 500;
const HASH_BYTES = 16;
/** The largest header: flags, hops, two addresses and the context. */
const HEADER_MAX = 2 + 2 * HASH_BYTES + 1;
const HEADER_MIN = 2 + HASH_BYTES + 1;

const HeaderType = Object.freeze({ ONE: 0, TWO: 1 });
const Propagation = Object.freeze({ BROADCAST: 0, TRANSPORT: 1 });
const DestType = Object.freeze({ SINGLE: 0, GROUP: 1, PLAIN: 2, LINK: 3 });
const PacketType = Object.freeze({ DATA: 0, ANNOUNCE: 1, LINKREQUEST: 2, PROOF: 3 });
const Context = Object.freeze({
  NONE: 0x00,
  PATH_RESPONSE: 0x0b,
  KEEPALIVE: 0xfa,
  LINKIDENTIFY: 0xfb,
  LINKCLOSE: 0xfc,
  LINKPROOF: 0xfd,
  LRRTT: 0xfe,
  LRPROOF: 0xff,
});

/**
 * Builds a packet. `transportId` makes it header type 2 with transport propagation: the next hop
 * that is to carry it on, which a packet for a destination further than one hop away must name.
 */
function encode({ packetType, destType, destination, context = Context.NONE, data = Buffer.alloc(0), transportId = null, contextFlag = false, hops = 0 }) {
  const two = transportId != null;
  const flags = ((two ? 1 : 0) << 6) | ((contextFlag ? 1 : 0) << 5) | ((two ? 1 : 0) << 4) | ((destType & 3) << 2) | (packetType & 3);
  const parts = [Buffer.from([flags, hops & 0xff])];
  if (two) parts.push(transportId);
  parts.push(destination, Buffer.from([context & 0xff]), data);
  return Buffer.concat(parts);
}

/** Parses a packet; null for anything too short, with an interface access code, or oversized. */
function decode(raw) {
  if (!Buffer.isBuffer(raw) || raw.length < HEADER_MIN || raw.length > MTU) return null;
  const flags = raw[0];
  if (flags & 0x80) return null;                                   // IFAC: not ours to check
  const headerType = (flags >> 6) & 1;
  const off = headerType === HeaderType.TWO ? 2 + HASH_BYTES : 2;
  if (raw.length < off + HASH_BYTES + 1) return null;
  return {
    headerType,
    contextFlag: ((flags >> 5) & 1) === 1,
    propagation: (flags >> 4) & 1,
    destType: (flags >> 2) & 3,
    packetType: flags & 3,
    hops: raw[1],
    transportId: headerType === HeaderType.TWO ? Buffer.from(raw.subarray(2, 2 + HASH_BYTES)) : null,
    destination: Buffer.from(raw.subarray(off, off + HASH_BYTES)),
    context: raw[off + HASH_BYTES],
    data: Buffer.from(raw.subarray(off + HASH_BYTES + 1)),
    raw,
  };
}

/**
 * The part of a packet that stays the same however it is carried: the low nibble of the flags
 * (destination and packet type) and everything after the hop count and any transport id. Its
 * hash names the packet: a link's id is the truncated hash of its request's hashable part.
 */
function hashablePart(raw) {
  const two = ((raw[0] >> 6) & 1) === 1;
  return Buffer.concat([Buffer.from([raw[0] & 0x0f]), raw.subarray(two ? 2 + HASH_BYTES : 2)]);
}

function packetHash(raw) {
  return sha256(hashablePart(raw));
}

// ---- HDLC framing ----

const FLAG = 0x7e;
const ESC = 0x7d;
const ESC_MASK = 0x20;

function frame(packet) {
  const out = [FLAG];
  for (const b of packet) {
    if (b === FLAG || b === ESC) out.push(ESC, b ^ ESC_MASK);
    else out.push(b);
  }
  out.push(FLAG);
  return Buffer.from(out);
}

/**
 * Collects frames from a byte stream. Bytes before the first flag are dropped; a frame that
 * grows past `max` is abandoned up to the next flag, so a peer that never sends one cannot make
 * the buffer grow without bound.
 */
class Deframer {
  constructor(onFrame, max = MTU + 64) {
    this.onFrame = onFrame;
    this.max = max;
    this.buf = [];
    this.inFrame = false;
    this.escape = false;
    this.overflow = false;
  }

  push(chunk) {
    for (const b of chunk) {
      if (b === FLAG) {
        if (this.inFrame && this.buf.length > 0 && !this.overflow) this.onFrame(Buffer.from(this.buf));
        this.buf = [];
        this.inFrame = true;
        this.escape = false;
        this.overflow = false;
      } else if (!this.inFrame || this.overflow) {
        // outside a frame, or skipping an oversized one
      } else if (this.escape) {
        this.buf.push(b ^ ESC_MASK);
        this.escape = false;
      } else if (b === ESC) {
        this.escape = true;
      } else {
        this.buf.push(b);
      }
      if (this.buf.length > this.max) {
        this.buf = [];
        this.overflow = true;
      }
    }
  }
}

module.exports = {
  MTU, HASH_BYTES, HEADER_MAX, HEADER_MIN, HeaderType, Propagation, DestType, PacketType, Context,
  encode, decode, hashablePart, packetHash, frame, Deframer,
};
