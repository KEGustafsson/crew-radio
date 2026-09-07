package fi.crewradio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ChannelCryptoTest {

    private val crypto = TestKeys.crypto
    private val aad = Packet.encode(7, 42, Packet.Codec.OPUS, 0, ByteArray(1), time = 1_788_739_200L)
    private val plain = ByteArray(60) { (it * 7).toByte() }

    @Test
    fun roundTrips() {
        val sealed = crypto.seal(aad, plain)
        assertEquals(plain.size + ChannelCrypto.OVERHEAD, sealed.size)
        assertArrayEquals(plain, crypto.open(aad, sealed))
    }

    @Test
    fun opensFromAnOffsetInsideALargerArray() {
        val sealed = crypto.seal(aad, plain)
        val packet = aad + sealed
        assertArrayEquals(plain, crypto.open(aad, packet, aad.size, sealed.size))
    }

    @Test
    fun readsNothingPastTheLengthItIsGiven() {
        val sealed = crypto.seal(aad, plain)
        val buf = aad + sealed + ByteArray(40) { 0x5A }                           // a datagram buffer with junk after the packet
        assertArrayEquals(plain, crypto.open(aad, buf, aad.size, sealed.size))     // the junk is not part of the tag
        assertNull(crypto.open(aad, buf, aad.size, sealed.size + 1))              // one byte more and the tag is wrong
        assertNull(crypto.open(aad, buf, aad.size, sealed.size - 1))
        assertNull(crypto.open(aad, buf, aad.size, buf.size))                     // past the end: refused, not thrown
        assertNull(crypto.open(aad, buf, -1, sealed.size))
    }

    @Test
    fun everyPacketGetsItsOwnNonceAndCiphertext() {
        val a = crypto.seal(aad, plain)
        val b = crypto.seal(aad, plain)
        assertFalse(a.contentEquals(b))
        assertFalse(a.copyOf(ChannelCrypto.NONCE_BYTES).contentEquals(b.copyOf(ChannelCrypto.NONCE_BYTES)))
    }

    @Test
    fun aFlippedBitAnywhereFails() {
        val sealed = crypto.seal(aad, plain)
        for (i in listOf(0, ChannelCrypto.NONCE_BYTES, sealed.size / 2, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", crypto.open(aad, bad))
        }
    }

    @Test
    fun theHeaderIsAuthenticatedToo() {
        val sealed = crypto.seal(aad, plain)
        val otherSender = aad.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertNull(crypto.open(otherSender, sealed))
    }

    @Test
    fun aDifferentChannelKeyCannotOpenIt() {
        val sealed = crypto.seal(aad, plain)
        assertNull(TestKeys.other.open(aad, sealed))
        assertNotNull(TestKeys.crypto.open(aad, sealed))
    }

    @Test
    fun truncatedInputIsRejectedNotThrown() {
        val sealed = crypto.seal(aad, plain)
        assertNull(crypto.open(aad, sealed.copyOf(ChannelCrypto.OVERHEAD)))
        assertNull(crypto.open(aad, ByteArray(0)))
    }

    @Test
    fun keyDerivationIsPbkdf2OverUtf8() {
        // The written-out PBKDF2 agrees with the platform's, for ASCII and for a key with non-ASCII characters.
        for (key in listOf("abcd-efgh-jkmn", "Pohjantähti-2026")) {
            val spec = PBEKeySpec(key.toCharArray(), "CrewRadio channel key v4".toByteArray(), ChannelCrypto.ITERATIONS, 256)
            val platform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            assertArrayEquals(key, platform, ChannelCrypto.derive(key).encoded)
        }
        assertEquals(32, ChannelCrypto.derive("x").encoded.size)
        assertEquals(600_000, ChannelCrypto.ITERATIONS)
    }

    @Test
    fun awareSecretsAreDerivedNotTheKeyItself() {
        val pass = crypto.awarePassphrase
        assertEquals(43, pass.length)
        assertTrue(pass, pass.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' })
        assertFalse(pass.contains(TestKeys.CHANNEL_KEY))
        assertEquals(pass, ChannelCrypto.forChannelKey(TestKeys.CHANNEL_KEY).awarePassphrase)       // the same on every phone
        assertFalse(pass == TestKeys.other.awarePassphrase)

        val tag = crypto.awareIdTag(0x12345678)
        assertEquals(ChannelCrypto.AWARE_ID_TAG_BYTES, tag.size)
        assertArrayEquals(tag, crypto.awareIdTag(0x12345678))
        assertFalse(tag.contentEquals(crypto.awareIdTag(0x12345679)))                                 // bound to the id
        assertFalse(tag.contentEquals(TestKeys.other.awareIdTag(0x12345678)))                         // and to the key
    }
}
