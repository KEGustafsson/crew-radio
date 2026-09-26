package fi.crewradio.rns

import java.nio.charset.StandardCharsets

/**
 * A Reticulum identity: an X25519 key pair for key agreement and an Ed25519 key pair for
 * signatures. Its public key is the two public keys in that order, its hash the truncated
 * SHA-256 of that. A single destination named `app.aspect.aspect` is addressed by the truncated
 * hash of its 10-byte name hash and the identity hash. An announce says which key answers for a
 * destination:
 *
 *     public key (64) | name hash (10) | random hash (10) | [ratchet (32) with the context flag] | signature (64) | app data
 *
 * signed over destination | public key | name hash | random hash | ratchet | app data, the random
 * hash being five random bytes and the send time in seconds as five big-endian bytes. The same
 * as the plugin's lib/rns/identity.js.
 */
internal class RnsIdentity(encPriv: ByteArray, sigSeed: ByteArray) {
    private val encPriv = encPriv.copyOf()
    private val sigSeed = sigSeed.copyOf()
    val encPub: ByteArray = Curve25519.x25519Public(this.encPriv)
    val sigPub: ByteArray = Curve25519.ed25519Public(this.sigSeed)
    val publicKey: ByteArray = encPub + sigPub
    val hash: ByteArray = RnsCrypto.truncatedHash(publicKey)

    fun sign(data: ByteArray): ByteArray = Curve25519.ed25519Sign(sigSeed, data)
    /** The X25519 secret with [peerPub]; null for a low-order key that yields all zeros, which OpenSSL refuses too. */
    fun agree(peerPub: ByteArray): ByteArray? = agree(encPriv, peerPub)

    /** What an announce told us, once its destination and signature have been checked. */
    class Announce(val destination: ByteArray, val publicKey: ByteArray, val nameHash: ByteArray, val emitted: Long, val appData: ByteArray) {
        val encPub: ByteArray get() = publicKey.copyOf(32)
        val sigPub: ByteArray get() = publicKey.copyOfRange(32, 64)
    }

    companion object {
        const val NAME_HASH_BYTES = 10
        const val RANDOM_HASH_BYTES = 10
        const val PUBLIC_BYTES = 64
        const val SIG_BYTES = 64
        const val RATCHET_BYTES = 32
        const val ANNOUNCE_MIN = PUBLIC_BYTES + NAME_HASH_BYTES + RANDOM_HASH_BYTES + SIG_BYTES

        fun generate() = RnsIdentity(RnsCrypto.randomBytes(32), RnsCrypto.randomBytes(32))

        fun agree(priv: ByteArray, peerPub: ByteArray): ByteArray? {
            if (peerPub.size != 32) return null
            val s = Curve25519.x25519(priv, peerPub)
            return if (s.all { it.toInt() == 0 }) null else s
        }

        fun nameHash(name: String): ByteArray = RnsCrypto.sha256(name.toByteArray(StandardCharsets.UTF_8)).copyOf(NAME_HASH_BYTES)

        fun destinationHash(nameHash: ByteArray, identityHash: ByteArray): ByteArray = RnsCrypto.truncatedHash(nameHash, identityHash)

        /** An announce's destination and data field; [random] and [nowS] only for test vectors. */
        fun buildAnnounce(
            identity: RnsIdentity, nameHash: ByteArray, appData: ByteArray = ByteArray(0),
            random: ByteArray = RnsCrypto.randomBytes(5), nowS: Long = System.currentTimeMillis() / 1000
        ): Pair<ByteArray, ByteArray> {
            val t = nowS % (1L shl 40)
            val randomHash = random + ByteArray(5) { i -> (t shr (8 * (4 - i))).toByte() }
            val dest = destinationHash(nameHash, identity.hash)
            val signature = identity.sign(dest + identity.publicKey + nameHash + randomHash + appData)
            return dest to (identity.publicKey + nameHash + randomHash + signature + appData)
        }

        /** Checks an announce packet; null when malformed, when its destination does not follow from its key and name, or when the signature fails. */
        fun parseAnnounce(p: RnsPacket): Announce? {
            val d = p.data
            val ratchetLen = if (p.contextFlag) RATCHET_BYTES else 0
            if (d.size < ANNOUNCE_MIN + ratchetLen) return null
            val nameAt = PUBLIC_BYTES
            val randomAt = nameAt + NAME_HASH_BYTES
            val ratchetAt = randomAt + RANDOM_HASH_BYTES
            val sigAt = ratchetAt + ratchetLen
            val appAt = sigAt + SIG_BYTES
            val publicKey = d.copyOfRange(0, nameAt)
            val nh = d.copyOfRange(nameAt, randomAt)
            val randomHash = d.copyOfRange(randomAt, ratchetAt)
            val ratchet = d.copyOfRange(ratchetAt, sigAt)
            val signature = d.copyOfRange(sigAt, appAt)
            val appData = d.copyOfRange(appAt, d.size)
            if (!destinationHash(nh, RnsCrypto.truncatedHash(publicKey)).contentEquals(p.destination)) return null
            val signed = p.destination + publicKey + nh + randomHash + ratchet + appData
            if (!Curve25519.ed25519Verify(publicKey.copyOfRange(32, 64), signed, signature)) return null
            var emitted = 0L
            for (i in 5 until 10) emitted = (emitted shl 8) or (randomHash[i].toLong() and 0xFF)
            return Announce(p.destination, publicKey, nh, emitted, appData)
        }
    }
}
