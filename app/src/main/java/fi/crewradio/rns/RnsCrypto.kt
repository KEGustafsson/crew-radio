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

    /** A token for [plain] under a 64-byte [key]; [iv] only for test vectors. */
    fun tokenEncrypt(key: ByteArray, plain: ByteArray, iv: ByteArray = randomBytes(IV_BYTES)): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")      // PKCS#5 is PKCS#7 for 16-byte blocks
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 32, 32, "AES"), IvParameterSpec(iv))
        val body = iv + c.doFinal(plain)
        return body + hmacSha256(key.copyOf(32), body)
    }

    /** The plaintext of a token, or null when it does not authenticate or unpad; never throws. */
    fun tokenDecrypt(key: ByteArray, token: ByteArray): ByteArray? {
        if (token.size < TOKEN_OVERHEAD + 16 || (token.size - TOKEN_OVERHEAD) % 16 != 0) return null
        val body = token.copyOf(token.size - MAC_BYTES)
        if (!MessageDigest.isEqual(token.copyOfRange(token.size - MAC_BYTES, token.size), hmacSha256(key.copyOf(32), body))) return null
        return try {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 32, 32, "AES"), IvParameterSpec(body, 0, IV_BYTES))
            c.doFinal(body, IV_BYTES, body.size - IV_BYTES)
        } catch (_: Exception) {
            null
        }
    }
}
