package fi.crewradio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The same bytes the Signal K plugin's test suite checks (sk-plugin/test/vector.json), so the
 * two implementations of the wire format and the channel crypto are held to each other:
 * same PBKDF2 parameters, same AAD rule, same AES-GCM layout, same hello encoding, same Aware
 * derivations. The packet was sealed with a fixed nonce; this test opens it, seals it again
 * with that nonce, and re-encodes the hello.
 */
class CrossLanguageVectorTest {
    private val channelKey = "north-star-2026"
    private val derivedKeyHex = "c74b235783ad92075401d7b04e84f2d138f4bf1e18468b317cc4d7027b19caf7"
    private val nonceHex = "000102030405060708090a0b"
    private val plainHex = "020104008606536972697573"
    private val packetHex = "50540402040412345678000000076a9dfe80000102030405060708090a0b00622f484f4a8de723109250e778f788e7f50beb461834130f404b53"
    private val time = 1_788_739_200L
    private val awarePassphrase = "nM6G++UJzXiOghDLRtm8cTjb+OGhA0RZyFWgLSqVip8"
    private val awareIdTagHex = "c5bac4b54dc0db33"

    /** The plugin derives from the UTF-8 bytes of the passphrase; so must we, for a key that is not ASCII. */
    private val nonAsciiKey = "Pohjantähti-2026"
    private val nonAsciiKeyHex = "306af81b75bdb297ba26931dc75b0dbae8f1d7137c563792f81a7614758ab1af"

    @Test
    fun theKeyDerivesToTheSameBytes() {
        assertEquals(derivedKeyHex, ChannelCrypto.derive(channelKey).encoded.toHex())
        assertEquals(nonAsciiKeyHex, ChannelCrypto.derive(nonAsciiKey).encoded.toHex())
    }

    @Test
    fun aPacketSealedByThePluginOpensHere() {
        val packet = packetHex.hexToBytes()
        val h = Packet.parse(packet)
        assertNotNull(h)
        assertEquals(0x12345678, h!!.senderId)
        assertEquals(7, h.seq)
        assertEquals(Packet.Codec.HELLO, h.codec)
        assertEquals(4, h.ttl)
        assertEquals(4, h.hops)
        assertEquals(time, h.time)
        val plain = TestKeys.crypto.open(Packet.aadOf(packet), packet, Packet.HEADER, packet.size - Packet.HEADER)
        assertNotNull(plain)
        assertEquals(plainHex, plain!!.toHex())
        val hello = Hello.decode(plain, 0, plain.size)
        assertNotNull(hello)
        assertEquals("Sirius", hello!!.name)
        assertEquals(Hello.LAN, hello.transports)
        assertEquals(4, hello.ttl)
        assertEquals(134, hello.versionCode)
    }

    @Test
    fun theSamePacketSealsToTheSameBytes() {
        val header = Packet.encode(0x12345678, 7, Packet.Codec.HELLO, 4, ByteArray(0), time = time)
        val packet = header + TestKeys.crypto.seal(Packet.aadOf(header), plainHex.hexToBytes(), nonceHex.hexToBytes())
        assertEquals(packetHex, packet.toHex())
    }

    @Test
    fun theHelloEncodesToTheSameBytes() {
        assertArrayEquals(plainHex.hexToBytes(), Hello("Sirius", Hello.LAN, 4, 134).encode())
    }

    @Test
    fun theAwareSecretsAreTheSame() {
        assertEquals(awarePassphrase, TestKeys.crypto.awarePassphrase)
        assertEquals(awareIdTagHex, TestKeys.crypto.awareIdTag(0x12345678).toHex())
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
