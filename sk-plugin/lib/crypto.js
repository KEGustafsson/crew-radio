// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The channel's AEAD, byte-compatible with the app's ChannelCrypto.kt: AES-256-GCM under a key
 * derived from the crew's channel key by PBKDF2-HMAC-SHA256 (600 000 iterations, a fixed
 * application salt, the key's UTF-8 bytes as the passphrase), a random 96-bit nonce per packet
 * prepended to the ciphertext, and a 128-bit tag after it.
 *
 * Derivation is deliberately slow (a few hundred milliseconds on a Pi), so the plugin derives
 * on the thread pool with deriveKey() at start; deriveKeySync() is for tools and tests. Both
 * memoise per key value, so a restart with the same key costs nothing.
 */

const crypto = require("node:crypto");

const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const OVERHEAD = NONCE_BYTES + TAG_BYTES;
const ITERATIONS = 600_000;
const SALT = Buffer.from("CrewRadio channel key v4", "utf8");
const RETICULUM_LABEL = Buffer.from("CrewRadio reticulum v1", "ascii");

const derived = new Map();   // channel key -> 32-byte key, for the process lifetime

/** The AES-256 key for a channel key, asynchronously; deterministic, so every phone with the same key agrees. */
function deriveKey(channelKey) {
  const k = String(channelKey);
  const hit = derived.get(k);
  if (hit) return Promise.resolve(hit);
  return new Promise((resolve, reject) => {
    crypto.pbkdf2(Buffer.from(k, "utf8"), SALT, ITERATIONS, 32, "sha256", (err, key) => {
      if (err) return reject(err);
      derived.set(k, key);
      resolve(key);
    });
  });
}

/** The same key, blocking the thread it runs on. */
function deriveKeySync(channelKey) {
  const k = String(channelKey);
  let key = derived.get(k);
  if (!key) {
    key = crypto.pbkdf2Sync(Buffer.from(k, "utf8"), SALT, ITERATIONS, 32, "sha256");
    derived.set(k, key);
  }
  return key;
}

class ChannelCrypto {
  /** @param {Buffer} key 32 bytes, from deriveKey */
  constructor(key) {
    if (!Buffer.isBuffer(key) || key.length !== 32) throw new Error("ChannelCrypto: key must be 32 bytes");
    this.key = key;
  }

  /** Resolves with the crypto for a channel key, derived off the main thread. */
  static forChannelKey(channelKey) {
    return deriveKey(channelKey).then((key) => new ChannelCrypto(key));
  }

  /** The same, blocking: for the CLI and tests. */
  static forChannelKeySync(channelKey) {
    return new ChannelCrypto(deriveKeySync(channelKey));
  }

  /** nonce | ciphertext | tag for `plain` under `aad`. `nonce` is only for tests; production draws a fresh one. */
  seal(aad, plain, nonce = crypto.randomBytes(NONCE_BYTES)) {
    const c = crypto.createCipheriv("aes-256-gcm", this.key, nonce, { authTagLength: TAG_BYTES });
    c.setAAD(aad);
    const body = Buffer.concat([c.update(plain), c.final()]);
    return Buffer.concat([nonce, body, c.getAuthTag()]);
  }

  /**
   * The channel's Reticulum destination aspect: the first 8 bytes of HMAC-SHA256(packet key,
   * "CrewRadio reticulum v1") in lower-case hex, as the app's ChannelCrypto.reticulumTag. Our
   * destination is `crewradio.channel.<tag>`; without the key nobody can tell which channel an
   * announce belongs to, nor make one that a crew member dials.
   */
  get reticulumTag() {
    return crypto.createHmac("sha256", this.key).update(RETICULUM_LABEL).digest().subarray(0, 8).toString("hex");
  }

  /**
   * The plaintext, or null when the packet is not authentic under `aad` (never throws). A packet
   * without at least one byte of plaintext is refused unread, as the app does.
   */
  open(aad, sealed) {
    if (!Buffer.isBuffer(sealed) || sealed.length < OVERHEAD + 1) return null;
    try {
      const d = crypto.createDecipheriv("aes-256-gcm", this.key, sealed.subarray(0, NONCE_BYTES), { authTagLength: TAG_BYTES });
      d.setAAD(aad);
      d.setAuthTag(sealed.subarray(sealed.length - TAG_BYTES));
      return Buffer.concat([d.update(sealed.subarray(NONCE_BYTES, sealed.length - TAG_BYTES)), d.final()]);
    } catch {
      return null;
    }
  }
}

module.exports = { ChannelCrypto, deriveKey, deriveKeySync, NONCE_BYTES, TAG_BYTES, OVERHEAD, ITERATIONS, SALT };
