package fi.crewradio.rns

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A Reticulum link, either end, as the manual's "Link Establishment in Detail" describes it:
 *
 *  - request (initiator to the destination): ephemeral X25519 public key | ephemeral Ed25519
 *    public key. The link id is the truncated hash of the request's hashable part.
 *  - proof (destination to the link id, context LRPROOF): signature | the responder's ephemeral
 *    X25519 public key, signed by the destination's identity over link id | that key | the
 *    identity's Ed25519 public key.
 *  - rtt (initiator to the link id): the round trip as a MessagePack float, encrypted; it tells
 *    the responder the link is up.
 *
 * Both ends hold HKDF-SHA256(ECDH secret, salt = link id) as a 64-byte token key; every data
 * packet is one token. Keep-alives (context KEEPALIVE, one clear byte: 0xFF asks, 0xFE answers)
 * and the close (context LINKCLOSE, the link id as a token) complete it. No MTU signalling is
 * sent, so a link runs at the base MTU: [MDU] bytes of plaintext a packet. The plugin's
 * lib/rns/link.js is the same.
 */
internal class RnsLink(val id: ByteArray, private val key: ByteArray, val initiator: Boolean, val peer: ByteArray? = null) {
    @Volatile var active = initiator
        private set
    @Volatile var closed = false
        private set

    /** What a packet on this link meant. */
    sealed class Event {
        class Data(val plain: ByteArray) : Event()
        object Rtt : Event()
        object Close : Event()
        class Keepalive(val reply: ByteArray?) : Event()
    }

    fun encrypt(plain: ByteArray, iv: ByteArray = RnsCrypto.randomBytes(RnsCrypto.IV_BYTES)) = RnsCrypto.tokenEncrypt(key, plain, iv)
    fun decrypt(token: ByteArray) = RnsCrypto.tokenDecrypt(key, token)

    /** The token key, for the cross-language test. */
    internal fun keyForTest(): ByteArray = key.copyOf()

    fun dataPacket(plain: ByteArray): ByteArray {
        require(plain.size <= MDU) { "link payload ${plain.size} > $MDU" }
        return RnsPacket.encode(RnsPacket.DATA, RnsPacket.LINK, id, data = encrypt(plain))
    }

    fun rttPacket(seconds: Double): ByteArray = RnsPacket.encode(RnsPacket.DATA, RnsPacket.LINK, id, RnsPacket.CTX_LRRTT, encrypt(packRtt(seconds)))

    fun closePacket(): ByteArray = RnsPacket.encode(RnsPacket.DATA, RnsPacket.LINK, id, RnsPacket.CTX_LINKCLOSE, encrypt(id))

    fun keepalivePacket(answer: Boolean = false): ByteArray =
        RnsPacket.encode(RnsPacket.DATA, RnsPacket.LINK, id, RnsPacket.CTX_KEEPALIVE, byteArrayOf((if (answer) KEEPALIVE_ANSWER else KEEPALIVE_ASK).toByte()))

    /** The meaning of a packet addressed to this link, or null for anything that does not authenticate or that our links do not use. */
    fun handle(p: RnsPacket): Event? {
        if (p.packetType != RnsPacket.DATA || closed) return null
        if (p.context == RnsPacket.CTX_KEEPALIVE) {
            if (p.data.size != 1) return null
            return Event.Keepalive(if ((p.data[0].toInt() and 0xFF) == KEEPALIVE_ASK) keepalivePacket(true) else null)
        }
        val plain = decrypt(p.data) ?: return null
        return when (p.context) {
            RnsPacket.CTX_NONE -> { active = true; Event.Data(plain) }
            RnsPacket.CTX_LRRTT -> { active = true; Event.Rtt }
            RnsPacket.CTX_LINKCLOSE -> if (plain.contentEquals(id)) { closed = true; Event.Close } else null
            else -> null
        }
    }

    /** A request on its way: [complete] checks the proof against the destination's key. */
    class Request(val raw: ByteArray, val id: ByteArray, private val peer: RnsIdentity.Announce, private val encPriv: ByteArray) {
        fun complete(proof: RnsPacket): RnsLink? {
            if (proof.packetType != RnsPacket.PROOF || proof.context != RnsPacket.CTX_LRPROOF || !proof.destination.contentEquals(id)) return null
            if (proof.data.size != 96 && proof.data.size != 99) return null
            val signature = proof.data.copyOf(64)
            val peerEncPub = proof.data.copyOfRange(64, 96)
            val signalling = proof.data.copyOfRange(96, proof.data.size)
            if (!Curve25519.ed25519Verify(peer.sigPub, id + peerEncPub + peer.sigPub + signalling, signature)) return null
            val shared = RnsIdentity.agree(encPriv, peerEncPub) ?: return null
            return RnsLink(id, deriveKey(shared, id), initiator = true, peer = peer.destination)
        }
    }

    companion object {
        /** Plaintext bytes a link packet carries at the base MTU: whole AES blocks, less the byte PKCS#7 always adds. */
        const val MDU = ((RnsPacket.MTU - 1 - RnsPacket.HEADER_MIN - RnsCrypto.TOKEN_OVERHEAD) / 16) * 16 - 1
        private const val KEEPALIVE_ASK = 0xFF
        private const val KEEPALIVE_ANSWER = 0xFE

        fun deriveKey(shared: ByteArray, linkId: ByteArray) = RnsCrypto.hkdf(64, shared, linkId)

        /** The link id of a request: its hashable part, less any signalling bytes past the two keys. */
        fun linkIdOf(raw: ByteArray, dataLength: Int): ByteArray {
            val part = RnsPacket.hashablePart(raw)
            val extra = maxOf(0, dataLength - 64)
            return RnsCrypto.truncatedHash(if (extra > 0) part.copyOf(part.size - extra) else part)
        }

        /** MessagePack float 64, as the RTT packet carries it. */
        fun packRtt(seconds: Double): ByteArray = ByteBuffer.allocate(9).put(0xCB.toByte()).putDouble(seconds).array()

        /** Starts a link to an announced destination; [transportId] names the next hop when it is further than one. Keys only for tests. */
        fun request(
            peer: RnsIdentity.Announce, transportId: ByteArray?,
            encPriv: ByteArray = RnsCrypto.randomBytes(32), sigSeed: ByteArray = RnsCrypto.randomBytes(32)
        ): Request {
            val data = Curve25519.x25519Public(encPriv) + Curve25519.ed25519Public(sigSeed)
            val raw = RnsPacket.encode(RnsPacket.LINKREQUEST, RnsPacket.SINGLE, peer.destination, data = data, transportId = transportId)
            return Request(raw, linkIdOf(raw, data.size), peer, encPriv)
        }

        /** Answers a request to [identity]'s destination: the link (active once the initiator's first packet arrives) and the proof to send. */
        fun accept(identity: RnsIdentity, request: RnsPacket, encPriv: ByteArray = RnsCrypto.randomBytes(32)): Pair<RnsLink, ByteArray>? {
            if (request.packetType != RnsPacket.LINKREQUEST || (request.data.size != 64 && request.data.size != 67)) return null
            val id = linkIdOf(request.raw, request.data.size)
            val shared = RnsIdentity.agree(encPriv, request.data.copyOf(32)) ?: return null
            val encPub = Curve25519.x25519Public(encPriv)
            val signature = identity.sign(id + encPub + identity.sigPub)
            val proof = RnsPacket.encode(RnsPacket.PROOF, RnsPacket.LINK, id, RnsPacket.CTX_LRPROOF, signature + encPub)
            return RnsLink(id, deriveKey(shared, id), initiator = false) to proof
        }
    }
}

/**
 * Crew Radio packets inside a link: the sealed packet unchanged behind one byte. An Opus frame
 * or a hello fits whole; a PCM frame (686 bytes sealed) goes in parts, reassembled only in
 * order, so a lost part drops that one 20 ms frame and nothing after it:
 *
 *     whole: 0x01 | packet            part: count (2-3) | index | id u8 | bytes
 *     proof: 0x80 | HMAC-SHA256(confirm key, role | link id)   (role 1 = the end that dialled)
 *
 * (0x81 and 0x82 lead the parts of a question for the boat and its answer: [AskCarry].)
 *
 * The proof is how a link is confirmed: each end sends its own as soon as the link is up, and
 * nothing else goes either way until the far end's has checked out. It is bound to the link id
 * (fresh keys on every link) and to the sender's role, so a proof seen on one link is worthless on
 * any other and cannot be echoed back to its maker; a sealed channel packet, which anyone can copy
 * from anywhere, proves nothing about the link it arrives on.
 *
 * The same as the plugin's lib/rns/carry.js.
 */
internal object Carry {
    private const val WHOLE = 1
    private const val MAX_PARTS = 3
    private const val PART_HEAD = 3
    private const val KEYPROOF = 0x80
    private const val PROOF_BYTES = 32

    /** The key proof the end in [initiator]'s role sends on the link [linkId]. */
    fun keyProof(confirmKey: ByteArray, linkId: ByteArray, initiator: Boolean): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(confirmKey, "HmacSHA256"))
            update(if (initiator) 1 else 0)
            doFinal(linkId)
        }
        return byteArrayOf(KEYPROOF.toByte()) + mac
    }

    /** True when [payload] is a key proof frame, whatever it proves. */
    fun isKeyProof(payload: ByteArray): Boolean = payload.size == 1 + PROOF_BYTES && payload[0] == KEYPROOF.toByte()

    /** True when [payload] is the far end's proof for this link, made in [initiator]'s role (compared in constant time). */
    fun proofMatches(payload: ByteArray, confirmKey: ByteArray, linkId: ByteArray, initiator: Boolean): Boolean =
        isKeyProof(payload) && MessageDigest.isEqual(payload, keyProof(confirmKey, linkId, initiator))

    fun cut(packet: ByteArray, id: Int): List<ByteArray> {
        if (packet.size + 1 <= RnsLink.MDU) return listOf(byteArrayOf(WHOLE.toByte()) + packet)
        val room = RnsLink.MDU - PART_HEAD
        val count = (packet.size + room - 1) / room
        require(count <= MAX_PARTS) { "packet of ${packet.size} bytes is too large to carry" }
        return (0 until count).map { i ->
            byteArrayOf(count.toByte(), i.toByte(), id.toByte()) + packet.copyOfRange(i * room, minOf(packet.size, (i + 1) * room))
        }
    }

    /** One link's reassembly; not thread-safe, the owner holds its lock. */
    class Joiner {
        private var count = 0
        private var id = -1
        private var next = 0
        private val parts = ArrayList<ByteArray>(MAX_PARTS)

        fun push(payload: ByteArray): ByteArray? {
            if (payload.size < 2) return null
            val n = payload[0].toInt() and 0xFF
            if (n == WHOLE) return payload.copyOfRange(1, payload.size)
            if (n < 2 || n > MAX_PARTS || payload.size <= PART_HEAD) { reset(); return null }
            val index = payload[1].toInt() and 0xFF
            val pid = payload[2].toInt() and 0xFF
            val body = payload.copyOfRange(PART_HEAD, payload.size)
            if (index == 0) {
                reset()
                count = n; id = pid; next = 1; parts.add(body)
                return null
            }
            if (next == 0 || pid != id || n != count || index != next) { reset(); return null }
            parts.add(body)
            next++
            if (next < count) return null
            val out = parts.fold(ByteArray(0)) { acc, b -> acc + b }
            reset()
            return out
        }

        private fun reset() { count = 0; id = -1; next = 0; parts.clear() }
    }
}
