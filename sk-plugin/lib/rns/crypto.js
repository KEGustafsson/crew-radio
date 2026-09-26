// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The cryptographic primitives Reticulum names as its own (manual, "Cryptographic Primitives"),
 * all from Node's OpenSSL: X25519 key agreement, Ed25519 signatures, HKDF-SHA256, SHA-256 /
 * SHA-512, and the encrypted token - AES-256-CBC with PKCS#7 padding, then HMAC-SHA256 over
 * iv | ciphertext, with none of Fernet's version or timestamp fields:
 *
 *   token = iv (16) | AES-256-CBC(encKey, iv, pkcs7(plain)) | HMAC-SHA256(signKey, iv | ciphertext) (32)
 *
 * A 64-byte token key is split sign-first: bytes 0-31 authenticate, 32-63 encrypt. Written here
 * from the published primitives, not taken from the reference implementation; the app's
 * fi.crewradio.rns package is the same thing in Kotlin, and test/rns.vector.json pins both to
 * the same bytes.
 */

const crypto = require("node:crypto");

const KEY_BYTES = 32;
const IV_BYTES = 16;
const MAC_BYTES = 32;
/** Bytes a token adds before padding: the IV and the MAC. */
const TOKEN_OVERHEAD = IV_BYTES + MAC_BYTES;

// DER wrappers for raw Curve25519 keys: Node imports keys as PKCS#8 / SPKI, Reticulum sends 32 raw bytes.
const X25519_PKCS8 = Buffer.from("302e020100300506032b656e04220420", "hex");
const X25519_SPKI = Buffer.from("302a300506032b656e032100", "hex");
const ED25519_PKCS8 = Buffer.from("302e020100300506032b657004220420", "hex");
const ED25519_SPKI = Buffer.from("302a300506032b6570032100", "hex");

function sha256(...parts) {
  const h = crypto.createHash("sha256");
  for (const p of parts) h.update(p);
  return h.digest();
}

/** Reticulum's truncated hash: the first 16 bytes of SHA-256. */
function truncatedHash(...parts) {
  return sha256(...parts).subarray(0, 16);
}

function x25519Private(raw) {
  return crypto.createPrivateKey({ key: Buffer.concat([X25519_PKCS8, raw]), format: "der", type: "pkcs8" });
}

function x25519PublicKey(raw) {
  return crypto.createPublicKey({ key: Buffer.concat([X25519_SPKI, raw]), format: "der", type: "spki" });
}

function ed25519Private(seed) {
  return crypto.createPrivateKey({ key: Buffer.concat([ED25519_PKCS8, seed]), format: "der", type: "pkcs8" });
}

function ed25519PublicKey(raw) {
  return crypto.createPublicKey({ key: Buffer.concat([ED25519_SPKI, raw]), format: "der", type: "spki" });
}

function rawPublic(privateKey) {
  const der = crypto.createPublicKey(privateKey).export({ format: "der", type: "spki" });
  return Buffer.from(der.subarray(der.length - KEY_BYTES));
}

/** The raw X25519 public key for a raw 32-byte private key. */
function x25519Public(priv) {
  return rawPublic(x25519Private(priv));
}

/** The raw Ed25519 public key for a 32-byte seed. */
function ed25519Public(seed) {
  return rawPublic(ed25519Private(seed));
}

/** The 32-byte X25519 shared secret; throws for a public key that yields the all-zero secret. */
function x25519(priv, peerPub) {
  return crypto.diffieHellman({ privateKey: x25519Private(priv), publicKey: x25519PublicKey(peerPub) });
}

function ed25519Sign(seed, data) {
  return crypto.sign(null, data, ed25519Private(seed));
}

/** False for a bad signature and for anything that is not a key or a signature at all; never throws. */
function ed25519Verify(pub, data, sig) {
  if (!Buffer.isBuffer(pub) || pub.length !== KEY_BYTES || !Buffer.isBuffer(sig) || sig.length !== 64) return false;
  try {
    return crypto.verify(null, data, ed25519PublicKey(pub), sig);
  } catch {
    return false;
  }
}

/** HKDF-SHA256 (RFC 5869); an empty salt is the RFC's string of zeros. */
function hkdf(length, ikm, salt, info = Buffer.alloc(0)) {
  return Buffer.from(crypto.hkdfSync("sha256", ikm, salt ?? Buffer.alloc(0), info, length));
}

function hmacSha256(key, ...parts) {
  const h = crypto.createHmac("sha256", key);
  for (const p of parts) h.update(p);
  return h.digest();
}

/** A token for `plain` under a 64-byte key. `iv` is for test vectors; production draws a fresh one. */
function tokenEncrypt(key, plain, iv = crypto.randomBytes(IV_BYTES)) {
  const c = crypto.createCipheriv("aes-256-cbc", key.subarray(32, 64), iv);
  const body = Buffer.concat([iv, c.update(plain), c.final()]);
  return Buffer.concat([body, hmacSha256(key.subarray(0, 32), body)]);
}

/** The plaintext of a token, or null when it does not authenticate or unpad (never throws). */
function tokenDecrypt(key, token) {
  if (!Buffer.isBuffer(token) || token.length < TOKEN_OVERHEAD + 16 || (token.length - TOKEN_OVERHEAD) % 16 !== 0) return null;
  const body = token.subarray(0, token.length - MAC_BYTES);
  const mac = token.subarray(token.length - MAC_BYTES);
  if (!crypto.timingSafeEqual(mac, hmacSha256(key.subarray(0, 32), body))) return null;
  try {
    const d = crypto.createDecipheriv("aes-256-cbc", key.subarray(32, 64), body.subarray(0, IV_BYTES));
    return Buffer.concat([d.update(body.subarray(IV_BYTES)), d.final()]);
  } catch {
    return null;
  }
}

module.exports = {
  KEY_BYTES, IV_BYTES, MAC_BYTES, TOKEN_OVERHEAD,
  sha256, truncatedHash, x25519Public, ed25519Public, x25519, ed25519Sign, ed25519Verify,
  hkdf, hmacSha256, tokenEncrypt, tokenDecrypt,
};
