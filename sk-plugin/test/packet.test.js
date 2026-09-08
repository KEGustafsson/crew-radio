// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const P = require("../lib/packet");

test("header round-trips with big-endian ids, the codec, ttl and hops bytes and the time", () => {
  const h = P.encodeHeader({ senderId: -123456789, seq: 42, codec: P.Codec.OPUS, ttl: 3, time: 1788739200 });
  assert.equal(h.length, P.HEADER);
  assert.equal(P.HEADER, 18);
  assert.deepEqual([...h.subarray(0, 6)], [0x50, 0x54, 4, 1, 3, 3]);
  const parsed = P.parseHeader(Buffer.concat([h, Buffer.alloc(1)]));
  assert.deepEqual(parsed, { codec: 1, ttl: 3, hops: 3, senderId: -123456789, seq: 42, time: 1788739200 });
  const stamped = P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 1 });
  assert.ok(Math.abs(P.parseHeader(Buffer.concat([stamped, Buffer.alloc(1)])).time - Date.now() / 1000) < 2, "time defaults to the clock");
  const big = P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 1, time: 0xfffffff0 });
  assert.equal(P.parseHeader(Buffer.concat([big, Buffer.alloc(1)])).time, 0xfffffff0, "unsigned 32-bit");
});

test("hops defaults to the ttl and both are clamped to a byte", () => {
  const h = P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 300 });
  assert.equal(h[4], 255);
  assert.equal(h[5], 255);
  const h2 = P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 2, hops: 6 });
  assert.equal(h2[4], 2);
  assert.equal(h2[5], 6);
});

test("parse rejects the wrong magic, version (v3 included), codec, an empty payload and oversize", () => {
  const ok = Buffer.concat([P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 1 }), Buffer.alloc(1)]);
  assert.ok(P.parseHeader(ok));
  assert.equal(P.parseHeader(ok.subarray(0, P.HEADER)), null);
  for (const [i, v] of [[0, 0x51], [2, 3], [2, 5], [3, 9]]) {
    const bad = Buffer.from(ok);
    bad[i] = v;
    assert.equal(P.parseHeader(bad), null, `byte ${i} = ${v}`);
  }
  assert.equal(P.parseHeader(Buffer.alloc(P.MAX_SIZE + 1, 0x50)), null);
});

test("the AAD is the 18-byte header with the ttl zeroed and nothing else: the time is authenticated", () => {
  const p = Buffer.concat([P.encodeHeader({ senderId: 7, seq: 9, codec: 0, ttl: 5, hops: 6, time: 123456 }), Buffer.alloc(3, 0xaa)]);
  const aad = P.aadOf(p);
  assert.equal(aad.length, P.HEADER);
  assert.equal(aad[4], 0);
  assert.equal(aad[5], 6);
  assert.equal(aad.readUInt32BE(14), 123456);
  assert.equal(p[4], 5, "the packet itself is untouched");
});

test("isFresh: within 60 s either way, wrap-safe at the 32-bit boundary", () => {
  assert.equal(P.REPLAY_WINDOW_S, 60);
  assert.ok(P.isFresh(1000, 1000));
  assert.ok(P.isFresh(1000, 1060));
  assert.ok(P.isFresh(1060, 1000));
  assert.ok(!P.isFresh(1000, 1061));
  assert.ok(!P.isFresh(1061, 1000));
  assert.ok(P.isFresh(0xffffffff, 0), "a second before the counter wraps");
  assert.ok(P.isFresh(5, 0xfffffffb), "and a few after");
  assert.ok(!P.isFresh(0x80000000, 0));
});

test("hello v2 encodes and decodes with the version code, cutting the name to 32 UTF-8 bytes on a character boundary", () => {
  const h = P.encodeHello({ name: "Sirius", transports: P.Transports.LAN, ttl: 4, versionCode: 134 });
  assert.deepEqual([...h.subarray(0, 6)], [2, 1, 4, 0, 134, 6]);
  assert.deepEqual(P.decodeHello(h), { name: "Sirius", transports: 1, ttl: 4, versionCode: 134 });
  assert.equal(P.encodeHello({ name: "x", transports: 1, ttl: 1 }).readUInt16BE(3), 0, "the plugin's own build is 0: not applicable");
  const aUmlaut = String.fromCharCode(0xe4); // two UTF-8 bytes
  const long = P.encodeHello({ name: aUmlaut.repeat(40), transports: 7, ttl: 1 });
  assert.equal(long.length, 6 + 32);
  assert.equal(P.decodeHello(long).name, aUmlaut.repeat(16));
  const euro = String.fromCharCode(0x20ac); // three UTF-8 bytes: 32 is not a multiple, so 30 bytes
  assert.equal(P.encodeHello({ name: euro.repeat(20), transports: 1, ttl: 1 }).length, 6 + 30);
});

test("hello rejects a bad version (v1 included), a name over 32 bytes, trailing bytes and invalid UTF-8", () => {
  const good = P.encodeHello({ name: "x", transports: 1, ttl: 1 });
  assert.equal(P.decodeHello(Buffer.concat([good, Buffer.alloc(1)])), null);
  assert.equal(P.decodeHello(Buffer.from([1, 1, 1, 1, 0x78])), null, "hello v1");
  assert.equal(P.decodeHello(Buffer.from([3, 1, 1, 0, 0, 1, 0x78])), null);
  assert.equal(P.decodeHello(Buffer.from([2, 1, 1, 0, 0, 33, ...Buffer.alloc(33, 0x78)])), null);
  assert.equal(P.decodeHello(Buffer.from([2, 1, 1, 0, 0, 1, 0xff])), null);
  assert.equal(P.decodeHello(Buffer.from([2, 1, 1, 0, 0])), null, "too short");
});

test("a decoded name is sanitised: controls and format characters dropped, whitespace collapsed, trimmed", () => {
  const ctl = P.decodeHello(Buffer.from([2, 1, 1, 0, 0, 3, 0x61, 0x07, 0x62]));
  assert.equal(ctl.name, "ab");
  assert.equal(P.sanitiseName("a‮b​c﻿d⁦e"), "abcde", "bidi override, zero-width space, BOM, isolate");
  assert.equal(P.sanitiseName("  Sea \t\n  Wolf  "), "Sea Wolf");
  assert.equal(P.sanitiseName("x"), "x", "C1 controls");
  assert.equal(P.sanitiseName("Ärhäkkä"), "Ärhäkkä", "letters stay");
  assert.equal(P.sanitiseName(null), "");
  const tricky = P.decodeHello(P.encodeHello({ name: "‪eve‬  x", transports: 1, ttl: 1 }));
  assert.equal(tricky.name, "eve x");
});
