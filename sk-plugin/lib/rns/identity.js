// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Reticulum identities, destinations and announces.
 *
 * An identity is two key pairs, X25519 for key agreement and Ed25519 for signatures; its public
 * key is the two 32-byte public keys in that order, and its hash the truncated SHA-256 of that.
 * A destination named `app.aspect.aspect` has a 10-byte name hash, the first bytes of SHA-256
 * over the dotted name, and for a single destination its address is the truncated hash of the
 * name hash followed by the identity hash.
 *
 * An announce tells the network a destination exists and which key answers for it:
 *
 *   public key (64) | name hash (10) | random hash (10) | [ratchet (32) when the context flag is set] | signature (64) | app data
 *
 * signed over destination | public key | name hash | random hash | ratchet | app data. The random
 * hash is five random bytes and the send time in whole seconds as five big-endian bytes, so
 * every announce is new and a newer one is recognisably newer.
 */

const crypto = require("node:crypto");
const C = require("./crypto");

const NAME_HASH_BYTES = 10;
const RANDOM_HASH_BYTES = 10;
const PUBLIC_BYTES = 64;
const SIG_BYTES = 64;
const RATCHET_BYTES = 32;
const ANNOUNCE_MIN = PUBLIC_BYTES + NAME_HASH_BYTES + RANDOM_HASH_BYTES + SIG_BYTES;

class Identity {
  /** @param {Buffer} encPriv 32-byte X25519 private key @param {Buffer} sigSeed 32-byte Ed25519 seed */
  constructor(encPriv, sigSeed) {
    this.encPriv = Buffer.from(encPriv);
    this.sigSeed = Buffer.from(sigSeed);
    this.encPub = C.x25519Public(this.encPriv);
    this.sigPub = C.ed25519Public(this.sigSeed);
    this.publicKey = Buffer.concat([this.encPub, this.sigPub]);
    this.hash = C.truncatedHash(this.publicKey);
  }

  static generate() {
    return new Identity(crypto.randomBytes(32), crypto.randomBytes(32));
  }

  sign(data) {
    return C.ed25519Sign(this.sigSeed, data);
  }
}

/** The 10-byte name hash of a dotted destination name. */
function nameHash(name) {
  return C.sha256(Buffer.from(name, "utf8")).subarray(0, NAME_HASH_BYTES);
}

/** The address of a single destination: truncated hash of name hash | identity hash. */
function destinationHash(nameHashBytes, identityHash) {
  return C.truncatedHash(nameHashBytes, identityHash);
}

/** The address of a plain destination, which has no identity: the truncated hash of the name hash alone. */
function plainHash(name) {
  return C.truncatedHash(nameHash(name));
}

/**
 * An announce's data field for `identity`'s destination under `nameHashBytes`. `random` and
 * `nowS` are for test vectors; production takes fresh bytes and the clock.
 */
function buildAnnounce(identity, nameHashBytes, appData = Buffer.alloc(0), random = crypto.randomBytes(5), nowS = Math.floor(Date.now() / 1000)) {
  const when = Buffer.alloc(5);
  when.writeUIntBE(nowS % 2 ** 40, 0, 5);
  const randomHash = Buffer.concat([random, when]);
  const dest = destinationHash(nameHashBytes, identity.hash);
  const signature = identity.sign(Buffer.concat([dest, identity.publicKey, nameHashBytes, randomHash, appData]));
  return { destination: dest, data: Buffer.concat([identity.publicKey, nameHashBytes, randomHash, signature, appData]) };
}

/**
 * Checks an announce packet (already decoded) and returns what it says, or null when it is
 * malformed, its destination does not follow from its key and name, or the signature fails.
 */
function parseAnnounce(packet) {
  const d = packet.data;
  const ratchetLen = packet.contextFlag ? RATCHET_BYTES : 0;
  if (d.length < ANNOUNCE_MIN + ratchetLen) return null;
  let o = 0;
  const publicKey = d.subarray(o, (o += PUBLIC_BYTES));
  const nh = d.subarray(o, (o += NAME_HASH_BYTES));
  const randomHash = d.subarray(o, (o += RANDOM_HASH_BYTES));
  const ratchet = d.subarray(o, (o += ratchetLen));
  const signature = d.subarray(o, (o += SIG_BYTES));
  const appData = d.subarray(o);
  const identityHash = C.truncatedHash(publicKey);
  if (!destinationHash(nh, identityHash).equals(packet.destination)) return null;
  const signed = Buffer.concat([packet.destination, publicKey, nh, randomHash, ratchet, appData]);
  if (!C.ed25519Verify(Buffer.from(publicKey.subarray(32)), signed, Buffer.from(signature))) return null;
  return {
    destination: packet.destination,
    publicKey: Buffer.from(publicKey),
    encPub: Buffer.from(publicKey.subarray(0, 32)),
    sigPub: Buffer.from(publicKey.subarray(32)),
    identityHash,
    nameHash: Buffer.from(nh),
    emitted: randomHash.readUIntBE(5, 5),
    appData: Buffer.from(appData),
  };
}

module.exports = {
  NAME_HASH_BYTES, RANDOM_HASH_BYTES, PUBLIC_BYTES, SIG_BYTES, ANNOUNCE_MIN,
  Identity, nameHash, destinationHash, plainHash, buildAnnounce, parseAnnounce,
};
