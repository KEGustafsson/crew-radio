package fi.crewradio

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Confidentiality and authenticity of every packet on the wire, from one shared channel key.
 *
 * AES-256-GCM (hardware-accelerated on every phone this app runs on) with a fresh random
 * 96-bit nonce per packet and the packet header as associated data, so a packet from a
 * phone without the key, or one altered in flight, fails the tag and is dropped before it
 * reaches the relay, the roster or a decoder. Replays of an authentic packet are caught by
 * the authenticated timestamp, the seen-cache and the sequence tracker afterwards ([Ingress]).
 *
 * The packet key comes from the crew's channel key (a passphrase, as UTF-8) through
 * PBKDF2-HMAC-SHA256, [ITERATIONS] rounds with a fixed application salt: the same passphrase
 * gives the same key on every phone. Deriving it costs about a second on a mid-range phone,
 * which is the point (an offline guess costs the same), so [derive] runs once per key value
 * and off the main thread. The Wi-Fi Aware secrets are HMACs of the packet key under their own
 * labels ([awarePassphrase], [awareIdTag]): the NAN handshake's cheaper KDF then verifies
 * nothing about the packet key, and a foreign publisher of our service name is told apart
 * before any network request. Pure JVM crypto, unit-tested.
 */
class ChannelCrypto(private val key: SecretKeySpec) {

    /** [aad] is authenticated but sent in the clear; returns nonce || ciphertext || tag. */
    fun seal(aad: ByteArray, plain: ByteArray): ByteArray =
        seal(aad, plain, ByteArray(NONCE_BYTES).also { random.nextBytes(it) })

    /** [seal] with a caller-supplied nonce: for test vectors only, a nonce must never repeat under one key. */
    internal fun seal(aad: ByteArray, plain: ByteArray, nonce: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return nonce + ct
    }

    /**
     * The plaintext, or null if the [length] bytes at [offset] were not sealed with this key and
     * this [aad] exactly. Nothing past [offset] + [length] is read.
     */
    fun open(aad: ByteArray, sealed: ByteArray, offset: Int = 0, length: Int = sealed.size - offset): ByteArray? {
        if (length < OVERHEAD + 1 || offset < 0 || offset + length > sealed.size) return null
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, offset, NONCE_BYTES))
            c.updateAAD(aad)
            c.doFinal(sealed, offset + NONCE_BYTES, length - NONCE_BYTES)
        } catch (_: Exception) {
            null                                   // bad tag, wrong key, truncated: all the same to us
        }
    }

    /**
     * The Wi-Fi Aware passphrase: Base64 of HMAC-SHA256(packet key, "CrewRadio aware v4"), no
     * padding, 43 printable ASCII characters, within Aware's 8-63. Same on every phone with the key.
     */
    val awarePassphrase: String by lazy {
        Base64.getEncoder().withoutPadding().encodeToString(hmac(AWARE_LABEL.toByteArray(StandardCharsets.US_ASCII)))
    }

    /**
     * The tag a node puts next to its sender id in its Aware discovery info: the first 8 bytes of
     * HMAC-SHA256(packet key, "CrewRadio aware id v4" || senderId int32 BE). A publisher whose tag
     * does not verify has not got the channel key and is ignored.
     */
    fun awareIdTag(senderId: Int): ByteArray =
        hmac(ByteBuffer.allocate(AWARE_ID_LABEL.length + 4).put(AWARE_ID_LABEL.toByteArray(StandardCharsets.US_ASCII)).putInt(senderId).array())
            .copyOf(AWARE_ID_TAG_BYTES)

    private fun hmac(data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run { init(SecretKeySpec(key.encoded, HMAC)); doFinal(data) }

    companion object {
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        /** Bytes a sealed payload is longer than its plaintext. */
        const val OVERHEAD = NONCE_BYTES + TAG_BYTES
        const val ITERATIONS = 600_000
        const val AWARE_ID_TAG_BYTES = 8
        private const val TAG_BITS = TAG_BYTES * 8
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val AWARE_LABEL = "CrewRadio aware v4"
        private const val AWARE_ID_LABEL = "CrewRadio aware id v4"
        private val SALT = "CrewRadio channel key v4".toByteArray(StandardCharsets.US_ASCII)
        private val random = SecureRandom()

        /**
         * The AES-256 key for a channel key; deterministic, so every phone with the same key
         * agrees. PBKDF2 is written out here over the passphrase's UTF-8 bytes (one HMAC block,
         * since the key is exactly one hash long) rather than taken from a provider's
         * `PBKDF2WithHmacSHA256`, whose treatment of non-ASCII characters has differed between
         * Android releases; the Signal K plugin derives from the same bytes.
         */
        fun derive(channelKey: String): SecretKeySpec {
            val mac = Mac.getInstance(HMAC)
            val password = channelKey.toByteArray(StandardCharsets.UTF_8)
            // HMAC zero-pads its key, so one zero byte is the same key as none: what PBKDF2 defines for an empty password.
            mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, HMAC))
            var u = mac.doFinal(SALT + byteArrayOf(0, 0, 0, 1))
            val out = u.copyOf()
            for (i in 2..ITERATIONS) {
                u = mac.doFinal(u)
                for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
            }
            return SecretKeySpec(out, "AES")
        }

        fun forChannelKey(channelKey: String) = ChannelCrypto(derive(channelKey))
    }
}
