// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Crew Radio wire format, version 4, exactly as the app's Packet.kt and Hello.kt define it.
 *
 *   'P' 'T' | version u8 = 4 | codec u8 | ttl u8 | hops u8 | senderId int32 BE | seq int32 BE | time uint32 BE
 *   then: nonce (12) | ciphertext | tag (16)            (see crypto.js)
 *
 * The header is the AES-GCM associated data with the ttl byte zeroed, since relays rewrite it;
 * everything else, `time` included, is authenticated. `time` is the sender's wall clock in whole
 * seconds since the epoch: a receiver drops a packet more than REPLAY_WINDOW_S off its own clock
 * before any cache sees it, which is what keeps a recording from being played back later.
 * codec 0 = one 20 ms frame of PCM16LE 16 kHz mono, 1 = one Opus packet, 2 = a hello.
 */

const HEADER = 18;
const VERSION = 4;
const MAX_SIZE = 1024;
const REPLAY_WINDOW_S = 60;

const Codec = Object.freeze({ PCM: 0, OPUS: 1, HELLO: 2 });

/**
 * Builds the 18-byte header. `hops` is the sender's original budget and defaults to the ttl;
 * `time` is in seconds and defaults to the clock now (production stamps it explicitly).
 */
function encodeHeader({ senderId, seq, codec, ttl, hops = ttl, time = Math.floor(Date.now() / 1000) }) {
  const h = Buffer.alloc(HEADER);
  h[0] = 0x50; // 'P'
  h[1] = 0x54; // 'T'
  h[2] = VERSION;
  h[3] = codec & 0xff;
  h[4] = clampByte(ttl);
  h[5] = clampByte(hops);
  h.writeInt32BE(senderId | 0, 6);
  h.writeInt32BE(seq | 0, 10);
  h.writeUInt32BE(time >>> 0, 14);
  return h;
}

/** Parses a packet's header; null for anything that is not a well-formed v4 packet with a payload. */
function parseHeader(p) {
  if (!Buffer.isBuffer(p) || p.length <= HEADER || p.length > MAX_SIZE) return null;
  if (p[0] !== 0x50 || p[1] !== 0x54 || p[2] !== VERSION) return null;
  const codec = p[3];
  if (codec !== Codec.PCM && codec !== Codec.OPUS && codec !== Codec.HELLO) return null;
  return { codec, ttl: p[4], hops: p[5], senderId: p.readInt32BE(6), seq: p.readInt32BE(10), time: p.readUInt32BE(14) };
}

/** The header as authenticated: the first 18 bytes with the ttl zeroed. */
function aadOf(p) {
  const a = Buffer.from(p.subarray(0, HEADER));
  a[4] = 0;
  return a;
}

/**
 * True when a packet stamped `time` is within the replay window of the clock `nowS` (both in
 * whole seconds). The comparison is unsigned 32-bit and wrap-safe, like the app's.
 */
function isFresh(time, nowS) {
  const d = ((time >>> 0) - (nowS >>> 0)) | 0;   // signed distance, wraps with the counter
  return d >= -REPLAY_WINDOW_S && d <= REPLAY_WINDOW_S;
}

function clampByte(n) {
  return Math.max(0, Math.min(255, n | 0));
}

// ---- Hello v2: ver u8 = 2 | transports u8 | ttl u8 | versionCode uint16 BE | nameLen u8 | name UTF-8 (max 32 bytes) ----

const HELLO_VERSION = 2;
const HELLO_HEAD = 6;
const HELLO_MAX_NAME = 32;
const Transports = Object.freeze({ LAN: 1, BT: 2, AWARE: 4 });

/**
 * Encodes a hello; the name is cut to 32 UTF-8 bytes on a character boundary, as the app does.
 * `versionCode` is the sender's build; 0 means not applicable, which is what this plugin sends.
 */
function encodeHello({ name, transports, ttl, versionCode = 0 }) {
  const bytes = utf8Prefix(name, HELLO_MAX_NAME);
  const h = Buffer.alloc(HELLO_HEAD + bytes.length);
  h[0] = HELLO_VERSION;
  h[1] = transports & 0xff;
  h[2] = clampByte(ttl);
  h.writeUInt16BE(Math.max(0, Math.min(0xffff, versionCode | 0)), 3);
  h[5] = bytes.length;
  bytes.copy(h, HELLO_HEAD);
  return h;
}

/**
 * A name as the roster shows it, the same rule as the app's Hello: ISO control characters and
 * Unicode format characters (bidi overrides, zero-width joiners and the like, which can make a
 * name read as another) are dropped, runs of whitespace become one space, and the ends are trimmed.
 */
function sanitiseName(s) {
  return String(s ?? "").replace(/[\p{Cc}\p{Cf}]/gu, "").replace(/\s+/g, " ").trim();
}

/** Decodes a hello payload; null for anything off the contract. */
function decodeHello(p) {
  if (!Buffer.isBuffer(p) || p.length < HELLO_HEAD || p[0] !== HELLO_VERSION) return null;
  const nameLen = p[5];
  if (nameLen > HELLO_MAX_NAME || p.length !== HELLO_HEAD + nameLen) return null;
  let name;
  try {
    name = new TextDecoder("utf-8", { fatal: true }).decode(p.subarray(HELLO_HEAD));
  } catch {
    return null;
  }
  return { name: sanitiseName(name), transports: p[1], ttl: p[2], versionCode: p.readUInt16BE(3) };
}

function utf8Prefix(s, max) {
  let str = String(s);
  let b = Buffer.from(str, "utf8");
  while (b.length > max) {
    str = str.slice(0, -1);
    b = Buffer.from(str, "utf8");
  }
  return b;
}

module.exports = {
  HEADER, VERSION, MAX_SIZE, REPLAY_WINDOW_S, Codec, Transports, HELLO_MAX_NAME, HELLO_VERSION,
  encodeHeader, parseHeader, aadOf, isFresh, encodeHello, decodeHello, sanitiseName,
};
