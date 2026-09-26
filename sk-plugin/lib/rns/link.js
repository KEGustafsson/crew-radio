// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Reticulum links, as the manual's "Link Establishment in Detail" describes them, for both ends:
 *
 *   request  (initiator -> destination): link request packet, data = ephemeral X25519 public key
 *            | ephemeral Ed25519 public key. The link id is the truncated hash of the request's
 *            hashable part, so every hop names the link the same way.
 *   proof    (destination -> link id): proof packet, context LRPROOF, data = signature | the
 *            responder's ephemeral X25519 public key, signed by the destination's identity over
 *            link id | that key | the identity's Ed25519 public key. Transport nodes check it too.
 *   rtt      (initiator -> link id): the measured round trip as a MessagePack float, encrypted;
 *            it is what tells the responder the link is up.
 *
 * Both ends then hold HKDF-SHA256(ECDH secret, salt = link id) as a 64-byte token key, and every
 * data packet on the link is one token. Keep-alives (context KEEPALIVE, one clear byte: 0xFF
 * asks, 0xFE answers) and the close (context LINKCLOSE, the link id as a token) complete it.
 * No link MTU signalling is sent, so a link runs at the base MTU: MDU bytes of plaintext a packet.
 */

const crypto = require("node:crypto");
const C = require("./crypto");
const P = require("./packet");

/** Plaintext bytes one link packet can carry at the base MTU, the way Reticulum counts it: whole AES blocks minus a padding byte. */
const MDU = Math.floor((P.MTU - 1 - P.HEADER_MIN - C.TOKEN_OVERHEAD) / 16) * 16 - 1;
const KEEPALIVE_ASK = 0xff;
const KEEPALIVE_ANSWER = 0xfe;

function deriveKey(sharedSecret, linkId) {
  return C.hkdf(64, sharedSecret, linkId);
}

/** The link id of a link request packet: its hashable part, less any signalling bytes past the two keys. */
function linkIdOf(raw, dataLength) {
  const part = P.hashablePart(raw);
  const extra = Math.max(0, dataLength - 64);
  return C.truncatedHash(extra > 0 ? part.subarray(0, part.length - extra) : part);
}

/** MessagePack float 64 of the round trip in seconds, as the RTT packet carries it. */
function packRtt(seconds) {
  const b = Buffer.alloc(9);
  b[0] = 0xcb;
  b.writeDoubleBE(seconds, 1);
  return b;
}

class Link {
  constructor({ id, key, initiator, peer = null }) {
    this.id = id;
    this.key = key;
    this.initiator = initiator;
    this.peer = peer;               // the far end's destination hash, when we dialled it
    this.active = initiator;        // a responder waits for the RTT (or any authentic packet)
    this.closed = false;
  }

  encrypt(plain, iv) {
    return C.tokenEncrypt(this.key, plain, iv);
  }

  decrypt(token) {
    return C.tokenDecrypt(this.key, token);
  }

  /** One data packet carrying `plain` (at most MDU bytes). */
  dataPacket(plain) {
    if (plain.length > MDU) throw new Error(`link payload ${plain.length} > ${MDU}`);
    return P.encode({ packetType: P.PacketType.DATA, destType: P.DestType.LINK, destination: this.id, data: this.encrypt(plain) });
  }

  rttPacket(seconds) {
    return P.encode({ packetType: P.PacketType.DATA, destType: P.DestType.LINK, destination: this.id, context: P.Context.LRRTT, data: this.encrypt(packRtt(seconds)) });
  }

  closePacket() {
    return P.encode({ packetType: P.PacketType.DATA, destType: P.DestType.LINK, destination: this.id, context: P.Context.LINKCLOSE, data: this.encrypt(this.id) });
  }

  keepalivePacket(answer = false) {
    return P.encode({ packetType: P.PacketType.DATA, destType: P.DestType.LINK, destination: this.id, context: P.Context.KEEPALIVE, data: Buffer.from([answer ? KEEPALIVE_ANSWER : KEEPALIVE_ASK]) });
  }

  /**
   * What a packet addressed to this link means: {kind: "data", plain}, {kind: "rtt"},
   * {kind: "close"}, {kind: "keepalive", reply} (reply is a packet to send, or null), or null
   * for anything that does not authenticate or that a link of ours does not use.
   */
  handle(packet) {
    if (packet.packetType !== P.PacketType.DATA || this.closed) return null;
    if (packet.context === P.Context.KEEPALIVE) {
      if (packet.data.length !== 1) return null;
      return { kind: "keepalive", reply: packet.data[0] === KEEPALIVE_ASK ? this.keepalivePacket(true) : null };
    }
    const plain = this.decrypt(packet.data);
    if (plain == null) return null;
    switch (packet.context) {
      case P.Context.NONE:
        this.active = true;
        return { kind: "data", plain };
      case P.Context.LRRTT:
        this.active = true;
        return { kind: "rtt" };
      case P.Context.LINKCLOSE:
        if (!plain.equals(this.id)) return null;
        this.closed = true;
        return { kind: "close" };
      default:
        return null;                 // requests, resources, channels: not used on our links
    }
  }
}

/**
 * Starts a link to a destination we have an announce for. Returns the request packet, the link
 * id and `complete(proofPacket)`, which checks the proof against the destination's key and
 * yields the established Link, or null when the proof is not the destination's.
 * `transportId` is the next hop for a destination more than one hop away. `keys` is for tests.
 */
function requestLink(peer, transportId = null, keys = { encPriv: crypto.randomBytes(32), sigSeed: crypto.randomBytes(32) }) {
  const encPub = C.x25519Public(keys.encPriv);
  const sigPub = C.ed25519Public(keys.sigSeed);
  const data = Buffer.concat([encPub, sigPub]);
  const raw = P.encode({ packetType: P.PacketType.LINKREQUEST, destType: P.DestType.SINGLE, destination: peer.destination, data, transportId });
  const id = linkIdOf(raw, data.length);
  const complete = (proof) => {
    if (proof.packetType !== P.PacketType.PROOF || proof.context !== P.Context.LRPROOF || !proof.destination.equals(id)) return null;
    if (proof.data.length !== 64 + 32 && proof.data.length !== 64 + 32 + 3) return null;
    const signature = proof.data.subarray(0, 64);
    const peerEncPub = proof.data.subarray(64, 96);
    const signalling = proof.data.subarray(96);
    if (!C.ed25519Verify(peer.sigPub, Buffer.concat([id, peerEncPub, peer.sigPub, signalling]), signature)) return null;
    let shared;
    try {
      shared = C.x25519(keys.encPriv, peerEncPub);
    } catch {
      return null;
    }
    return new Link({ id, key: deriveKey(shared, id), initiator: true, peer: peer.destination });
  };
  return { raw, id, complete };
}

/**
 * Answers a link request addressed to `identity`'s destination. Returns the Link (not active
 * until the initiator's first authentic packet) and the proof packet to send, or null for a
 * request that is not one. `encPriv` is for tests.
 */
function acceptLink(identity, request, encPriv = crypto.randomBytes(32)) {
  if (request.packetType !== P.PacketType.LINKREQUEST || (request.data.length !== 64 && request.data.length !== 67)) return null;
  const id = linkIdOf(request.raw, request.data.length);
  let shared;
  try {
    shared = C.x25519(encPriv, request.data.subarray(0, 32));
  } catch {
    return null;
  }
  const encPub = C.x25519Public(encPriv);
  const signature = identity.sign(Buffer.concat([id, encPub, identity.sigPub]));
  const proof = P.encode({ packetType: P.PacketType.PROOF, destType: P.DestType.LINK, destination: id, context: P.Context.LRPROOF, data: Buffer.concat([signature, encPub]) });
  return { link: new Link({ id, key: deriveKey(shared, id), initiator: false }), proof };
}

module.exports = { MDU, Link, requestLink, acceptLink, linkIdOf, packRtt, deriveKey };
