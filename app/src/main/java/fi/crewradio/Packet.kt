package fi.crewradio

import java.nio.ByteBuffer

/**
 * Wire format, version 4. 18-byte header, big-endian integers, followed by the sealed payload:
 *
 *     'P' 'T' | version u8 = 4 | codec u8 | ttl u8 | hops u8 | senderId int32 | seq int32 | time uint32
 *     then: nonce (12) | ciphertext | tag (16)
 *
 * The payload is AES-256-GCM under the crew's channel key ([ChannelCrypto]) with the header as
 * associated data, except the ttl byte, which relays rewrite in place ([aadOf]). `hops` is the
 * sender's original hop budget and is authenticated: a relay never lets ttl exceed it, so a
 * captured packet with its ttl bumped travels no further than the sender allowed. `time` is
 * the sender's wall clock in whole seconds since the Unix epoch, authenticated too: a receiver
 * drops a packet more than [REPLAY_WINDOW_S] off its own clock ([isFresh]) before any cache is
 * touched, so a recording cannot be played back later than that, however long the sender has
 * been quiet or the receiver has been off the channel. Without the key a packet cannot be
 * read or forged; no other header field can be altered in flight.
 *
 * codec: 0 = one 20 ms frame of PCM16LE 16 kHz mono, 1 = one 20 ms Opus packet,
 *        2 = a [Hello] roster heartbeat (no audio).
 * ttl:   hops this packet may still travel. A relay forwards only while it is > 1 and decrements
 *        it first, and only while the packet is within the relay's own hop limit of its origin
 *        ([Ingress.relayTtl]), so a flood over a large mesh is bounded and no peer can buy itself
 *        extra hops.
 *
 * Compatibility: there is no legacy decoding. Version 3 had no timestamp (14-byte header); its
 * packets fail [parse] and are dropped, so a crew must run the same app version on every phone.
 */
object Packet {
    const val HEADER = 18
    const val VERSION = 4
    /** Largest packet a peer may send: a sealed PCM frame with header is 686 bytes, Opus far less. Anything bigger is dropped unread. */
    const val MAX_SIZE = 1024
    /** How far a packet's [Header.time] may be from the receiver's clock, either way, in seconds. */
    const val REPLAY_WINDOW_S = 60

    /** What the payload is. PCM and OPUS carry audio; HELLO is the roster heartbeat. */
    enum class Codec(val id: Int) {
        PCM(0), OPUS(1), HELLO(2);

        companion object {
            /** Codec for a wire id, or null for an id this build does not know. */
            fun fromId(id: Int): Codec? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * Parsed header fields; the payload starts at [HEADER] in the same array. [hops] is the
     * authenticated origin budget, [time] the sender's clock as an unsigned 32-bit second count.
     */
    class Header(val senderId: Int, val seq: Int, val codec: Codec, val ttl: Int, val hops: Int, val time: Long)

    /**
     * Builds a complete packet; ttl and hops are clamped to the byte range, hops defaults to the
     * ttl the sender stamps, [time] is seconds since the epoch (the low 32 bits go on the wire).
     */
    fun encode(senderId: Int, seq: Int, codec: Codec, ttl: Int, payload: ByteArray, hops: Int = ttl, time: Long): ByteArray {
        val bb = ByteBuffer.allocate(HEADER + payload.size)
        bb.put('P'.code.toByte()).put('T'.code.toByte())
        bb.put(VERSION.toByte()).put(codec.id.toByte()).put(ttl.coerceIn(0, 255).toByte()).put(hops.coerceIn(0, 255).toByte())
        bb.putInt(senderId).putInt(seq).putInt(time.toInt())
        bb.put(payload)
        return bb.array()
    }

    /** Returns null for anything that is not a well-formed v4 packet with a non-empty payload. */
    fun parse(p: ByteArray): Header? {
        if (p.size <= HEADER || p.size > MAX_SIZE || p[0] != 'P'.code.toByte() || p[1] != 'T'.code.toByte()) return null
        if (p[2].toInt() != VERSION) return null
        val codec = Codec.fromId(p[3].toInt() and 0xFF) ?: return null
        val ttl = p[4].toInt() and 0xFF
        val hops = p[5].toInt() and 0xFF
        val bb = ByteBuffer.wrap(p, 6, 12)
        return Header(bb.int, bb.int, codec, ttl, hops, bb.int.toLong() and 0xFFFF_FFFFL)
    }

    /**
     * True if [time] is within [REPLAY_WINDOW_S] of [nowS], both in seconds. The difference is
     * taken modulo 2^32, so the wire's unsigned counter wrapping (in 2106) is a small distance,
     * not a jump; either side may be ahead.
     */
    fun isFresh(time: Long, nowS: Long): Boolean {
        val d = time.toInt() - nowS.toInt()             // wraps with the 32-bit counter
        return d in -REPLAY_WINDOW_S..REPLAY_WINDOW_S
    }

    /** The header as authenticated: a copy of the first [HEADER] bytes with the ttl zeroed, since relays change it. */
    fun aadOf(p: ByteArray): ByteArray = p.copyOf(HEADER).also { it[4] = 0 }

    /** Rewrites the ttl byte of an already encoded packet in place, clamped to the byte range. */
    fun setTtl(p: ByteArray, ttl: Int) {
        p[4] = ttl.coerceIn(0, 255).toByte()
    }
}
