// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const nodeCrypto = require("node:crypto");
const { ChannelCrypto, deriveKey, deriveKeySync, OVERHEAD, NONCE_BYTES, ITERATIONS, SALT } = require("../lib/crypto");
const P = require("../lib/packet");

const crypto = ChannelCrypto.forChannelKeySync("north-star-2026");
const aad = P.encodeHeader({ senderId: 7, seq: 42, codec: P.Codec.OPUS, ttl: 0, time: 1 });
const plain = Buffer.from(Array.from({ length: 60 }, (_, i) => (i * 7) & 0xff));

test("seals and opens, with the documented overhead", () => {
  const sealed = crypto.seal(aad, plain);
  assert.equal(sealed.length, plain.length + OVERHEAD);
  assert.deepEqual(crypto.open(aad, sealed), plain);
});

test("every packet gets its own nonce and ciphertext", () => {
  const a = crypto.seal(aad, plain);
  const b = crypto.seal(aad, plain);
  assert.notDeepEqual(a, b);
  assert.notDeepEqual(a.subarray(0, NONCE_BYTES), b.subarray(0, NONCE_BYTES));
});

test("a flipped bit anywhere, a different header or a different key fails, and nothing throws", () => {
  const sealed = crypto.seal(aad, plain);
  for (const i of [0, NONCE_BYTES, sealed.length >> 1, sealed.length - 1]) {
    const bad = Buffer.from(sealed);
    bad[i] ^= 1;
    assert.equal(crypto.open(aad, bad), null, `byte ${i}`);
  }
  const otherSender = Buffer.from(aad);
  otherSender[6] ^= 1;
  assert.equal(crypto.open(otherSender, sealed), null);
  const otherTime = Buffer.from(aad);
  otherTime[17] ^= 1;
  assert.equal(crypto.open(otherTime, sealed), null, "the time is authenticated");
  assert.equal(ChannelCrypto.forChannelKeySync("north-star-2027").open(aad, sealed), null);
  assert.equal(crypto.open(aad, sealed.subarray(0, OVERHEAD)), null);
  assert.equal(crypto.open(aad, Buffer.alloc(0)), null);
});

test("a packet without a byte of plaintext is refused unread, as the app does", () => {
  const empty = crypto.seal(aad, Buffer.alloc(0));
  assert.equal(empty.length, OVERHEAD, "authentic, but empty");
  assert.equal(crypto.open(aad, empty), null);
});

test("key derivation is PBKDF2 v4, deterministic, keyed, memoised, and the same asynchronously", async () => {
  assert.equal(ITERATIONS, 600_000);
  assert.equal(SALT.toString("utf8"), "CrewRadio channel key v4");
  assert.deepEqual(deriveKeySync("abcd-efgh-jkmn"), deriveKeySync("abcd-efgh-jkmn"));
  assert.notDeepEqual(deriveKeySync("abcd-efgh-jkmn"), deriveKeySync("abcd-efgh-jkmp"));
  assert.equal(deriveKeySync("abcd-efgh-jkmn"), deriveKeySync("abcd-efgh-jkmn"), "the very same buffer: memoised");
  assert.deepEqual(await deriveKey("abcd-efgh-jkmp"), deriveKeySync("abcd-efgh-jkmp"));
  assert.deepEqual(await deriveKey("fresh-key-2026"), nodeCrypto.pbkdf2Sync("fresh-key-2026", SALT, ITERATIONS, 32, "sha256"));
  const c = await ChannelCrypto.forChannelKey("north-star-2026");
  assert.deepEqual(c.key, crypto.key);
  assert.throws(() => new ChannelCrypto(Buffer.alloc(16)), /32 bytes/);
});

// The vectors below are checked by the Android app's own unit tests as well
// (app/src/test/java/fi/crewradio/CrossLanguageVectorTest.kt), so the two implementations
// are held to the same bytes: same PBKDF2 parameters, same AAD rule, same AES-GCM layout,
// same hello, and the same treatment of a channel key with a non-ASCII character.
function checkVector(vector) {
  const key = ChannelCrypto.forChannelKeySync(vector.channelKey);
  assert.equal(deriveKeySync(vector.channelKey).toString("hex"), vector.derivedKeyHex);
  const packet = Buffer.from(vector.packetHex, "hex");
  const header = P.parseHeader(packet);
  assert.deepEqual(header, vector.header);
  const opened = key.open(P.aadOf(packet), packet.subarray(P.HEADER));
  assert.equal(opened.toString("hex"), vector.plainHex);
  // and sealing with the vector's nonce reproduces the packet byte for byte
  const resealed = key.seal(P.aadOf(packet), opened, Buffer.from(vector.nonceHex, "hex"));
  assert.equal(Buffer.concat([packet.subarray(0, P.HEADER), resealed]).toString("hex"), vector.packetHex);
  return { key, opened, header };
}

test("cross-language vector: a packet sealed here opens on the phone, and vice versa", () => {
  const vector = require("./vector.json");
  const { key, opened, header } = checkVector(vector);
  assert.equal(header.time, 1788739200);
  assert.deepEqual(P.decodeHello(opened), vector.hello);
  assert.equal(P.encodeHello(vector.hello).toString("hex"), vector.plainHex);
  // The Aware secrets are the app's; derived here from the same packet key to pin the recipe.
  const passphrase = nodeCrypto.createHmac("sha256", key.key).update("CrewRadio aware v4").digest("base64").replace(/=+$/, "");
  assert.equal(passphrase, vector.awarePassphrase);
  assert.equal(passphrase.length, 43);
  const senderId = Buffer.alloc(4); senderId.writeInt32BE(vector.header.senderId);
  const tag = nodeCrypto.createHmac("sha256", key.key).update(Buffer.concat([Buffer.from("CrewRadio aware id v4"), senderId])).digest().subarray(0, 8);
  assert.equal(tag.toString("hex"), vector.awareIdTagHex);
});

test("cross-language vector, non-ASCII channel key: the passphrase is the key's UTF-8 bytes", () => {
  const vector = require("./vector.json").nonAscii;
  assert.equal(Buffer.from(vector.channelKey, "utf8").toString("hex"), vector.channelKeyUtf8Hex);
  const { opened } = checkVector(vector);
  assert.deepEqual(P.decodeHello(opened), require("./vector.json").hello);
});
