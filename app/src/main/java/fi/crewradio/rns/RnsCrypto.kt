package fi.crewradio.rns

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives Reticulum names as its own, apart from the curves ([Curve25519]): SHA-256, the
 * truncated hash, HKDF-SHA256 and the encrypted token —
 *
 *     token = iv (16) | AES-256-CBC(encKey, iv, pkcs7(plain)) | HMAC-SHA256(signKey, iv | ciphertext) (32)
 *
 * with a 64-byte key split sign-first (bytes 0-31 authenticate, 32-63 encrypt) and none of
 * Fernet's version or timestamp fields. The plugin's lib/rns/crypto.js is the same in Node;
 * the cross-language vector holds the two together.
 */
internal object RnsCrypto {
    private const val CIPHER = "AES/CBC/NoPadding"
    private const val BLOCK = 16
    const val IV_BYTES = 16
    const val MAC_BYTES = 32
    /** Bytes a token adds before padding. */
    const val TOKEN_OVERHEAD = IV_BYTES + MAC_BYTES

    val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    /** Reticulum's truncated hash: the first 16 bytes of SHA-256. */
    fun truncatedHash(vararg parts: ByteArray): ByteArray = sha256(*parts).copyOf(16)

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** HKDF-SHA256 (RFC 5869); an empty salt is the RFC's string of zeros. */
    fun hkdf(length: Int, ikm: ByteArray, salt: ByteArray, info: ByteArray = ByteArray(0)): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var i = 1
        while (done < length) {
            block = hmacSha256(prk, block, info, byteArrayOf(i.toByte()))
            val n = minOf(block.size, length - done)
            block.copyInto(out, done, 0, n)
            done += n
            i++
        }
        return out
    }

    /*
     * CBC is Reticulum's, not a choice made here: the token is AES-256-CBC, encrypt-then-MAC, and
     * a node using anything else is not on the network. What makes CBC with PKCS#7 dangerous is
     * a padding oracle - a receiver that says whether the padding was good before anyone knows
     * the ciphertext is genuine. So the cipher runs without padding and the padding is written
     * and removed here, the removal only after the HMAC over iv | ciphertext has matched (in
     * constant time): a forged or altered token is refused on the MAC alone, whatever its padding.
     */

    /** A token for [plain] under a 64-byte [key]; [iv] only for test vectors. */
    fun tokenEncrypt(key: ByteArray, plain: ByteArray, iv: ByteArray = randomBytes(IV_BYTES)): ByteArray {
        val c = Cipher.getInstance(CIPHER)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 32, 32, "AES"), IvParameterSpec(iv))
        val body = iv + c.doFinal(pad(plain))
        return body + hmacSha256(key.copyOf(32), body)
    }

    /** The plaintext of a token, or null when it does not authenticate or unpad; never throws. */
    fun tokenDecrypt(key: ByteArray, token: ByteArray): ByteArray? {
        if (token.size < TOKEN_OVERHEAD + 16 || (token.size - TOKEN_OVERHEAD) % 16 != 0) return null
        val body = token.copyOf(token.size - MAC_BYTES)
        if (!MessageDigest.isEqual(token.copyOfRange(token.size - MAC_BYTES, token.size), hmacSha256(key.copyOf(32), body))) return null
        return try {
            val c = Cipher.getInstance(CIPHER)
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 32, 32, "AES"), IvParameterSpec(body, 0, IV_BYTES))
            unpad(c.doFinal(body, IV_BYTES, body.size - IV_BYTES))
        } catch (_: Exception) {
            null
        }
    }

    /** PKCS#7: 1-16 bytes, each the count, so there is always at least one. */
    internal fun pad(plain: ByteArray): ByteArray {
        val n = BLOCK - plain.size % BLOCK
        return plain + ByteArray(n) { n.toByte() }
    }

    /** The data without its PKCS#7 padding, or null when the padding is not well formed. Only ever called on an authenticated token. */
    internal fun unpad(padded: ByteArray): ByteArray? {
        if (padded.isEmpty() || padded.size % BLOCK != 0) return null
        val n = padded[padded.size - 1].toInt() and 0xFF
        if (n < 1 || n > BLOCK) return null
        for (i in padded.size - n until padded.size) if ((padded[i].toInt() and 0xFF) != n) return null
        return padded.copyOf(padded.size - n)
    }
}
