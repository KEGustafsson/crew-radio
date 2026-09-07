package fi.crewradio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {

    private val payload = ByteArray(640) { it.toByte() }
    private val t = 1_788_739_200L

    @Test
    fun roundTripsHeaderAndPayload() {
        val p = Packet.encode(senderId = -12345, seq = 77, codec = Packet.Codec.OPUS, ttl = 4, payload = payload, time = t)
        assertEquals(Packet.HEADER + payload.size, p.size)
        val h = Packet.parse(p)!!
        assertEquals(-12345, h.senderId)
        assertEquals(77, h.seq)
        assertEquals(Packet.Codec.OPUS, h.codec)
        assertEquals(4, h.ttl)
        assertEquals(4, h.hops)
        assertEquals(t, h.time)
        assertArrayEquals(payload, p.copyOfRange(Packet.HEADER, p.size))
    }

    @Test
    fun timeIsAnUnsigned32BitSecondCount() {
        val far = 0xFFFF_FFF0L                                                   // 2106, past Int.MAX_VALUE
        assertEquals(far, Packet.parse(Packet.encode(1, 1, Packet.Codec.PCM, 4, payload, time = far))!!.time)
        assertEquals(far, Packet.parse(Packet.encode(1, 1, Packet.Codec.PCM, 4, payload, time = far + (1L shl 32)))!!.time)   // only the low 32 bits go out
        assertEquals(0L, Packet.parse(Packet.encode(1, 1, Packet.Codec.PCM, 4, payload, time = 0))!!.time)
    }

    @Test
    fun freshnessIsAWindowEitherSideOfNow() {
        val w = Packet.REPLAY_WINDOW_S.toLong()
        assertTrue(Packet.isFresh(t, t))
        assertTrue(Packet.isFresh(t - w, t))                                     // an old packet at the edge
        assertTrue(Packet.isFresh(t + w, t))                                     // a sender ahead of us at the edge
        assertFalse(Packet.isFresh(t - w - 1, t))
        assertFalse(Packet.isFresh(t + w + 1, t))
        assertFalse(Packet.isFresh(0, t))                                        // a clock never set
    }

    @Test
    fun freshnessSurvivesTheCounterWrap() {
        val top = 0xFFFF_FFFFL                                                   // the last second before the wrap
        assertTrue(Packet.isFresh(top, 5))                                       // sent just before, received just after
        assertTrue(Packet.isFresh(5, top))                                       // and the other way round
        assertFalse(Packet.isFresh(top - 100, 5))
        assertFalse(Packet.isFresh(1L shl 31, 0))                                // half the range apart is not close
    }

    @Test
    fun rejectsMalformedPackets() {
        val good = Packet.encode(1, 1, Packet.Codec.PCM, 4, payload, time = t)
        assertNull("empty payload", Packet.parse(good.copyOf(Packet.HEADER)))
        assertNull("bad magic", Packet.parse(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull("old version", Packet.parse(good.copyOf().also { it[2] = 3 }))
        assertNull("unknown codec", Packet.parse(good.copyOf().also { it[3] = 9 }))
        assertNull("oversized", Packet.parse(good.copyOf(Packet.MAX_SIZE + 1)))
    }

    @Test
    fun theOriginHopBudgetAndTheTimeAreAuthenticatedButTheTtlIsNot() {
        val crypto = TestKeys.crypto
        val header = Packet.encode(9, 8, Packet.Codec.OPUS, 4, ByteArray(0), time = t)
        val packet = header + crypto.seal(Packet.aadOf(header), payload)
        Packet.setTtl(packet, 255)                                       // a relay, or an attacker, rewrites the ttl
        val h = Packet.parse(packet)!!
        assertEquals(255, h.ttl)
        assertEquals(4, h.hops)                                          // the budget the sender stamped survives
        assertArrayEquals(payload, crypto.open(Packet.aadOf(packet), packet, Packet.HEADER, packet.size - Packet.HEADER))
        val hopsBumped = packet.copyOf().also { it[5] = 200.toByte() }   // but the budget itself cannot be changed
        assertNull(crypto.open(Packet.aadOf(hopsBumped), hopsBumped, Packet.HEADER, hopsBumped.size - Packet.HEADER))
        val refreshed = packet.copyOf().also { it[17] = (it[17].toInt() + 1).toByte() }   // nor the clock moved to dodge the window
        assertNull(crypto.open(Packet.aadOf(refreshed), refreshed, Packet.HEADER, refreshed.size - Packet.HEADER))
    }

    @Test
    fun aadIsTheHeaderWithoutTheTtl() {
        val a = Packet.aadOf(Packet.encode(9, 8, Packet.Codec.OPUS, 4, payload, time = t))
        val b = Packet.aadOf(Packet.encode(9, 8, Packet.Codec.OPUS, 1, payload, hops = 4, time = t))   // same budget, ttl already decremented
        assertEquals(Packet.HEADER, a.size)
        assertArrayEquals(a, b)
        assertEquals(0, a[4].toInt())
        assertEquals(Packet.VERSION, a[2].toInt())
    }

    @Test
    fun helloIsAKnownCodec() {
        val p = Packet.encode(1, 1, Packet.Codec.HELLO, 4, Hello("n", 0, 4).encode(), time = t)
        assertEquals(Packet.Codec.HELLO, Packet.parse(p)!!.codec)
        assertEquals(Packet.Codec.HELLO, Packet.Codec.fromId(2))
    }

    @Test
    fun ttlCanBeRewrittenInPlace() {
        val p = Packet.encode(1, 1, Packet.Codec.PCM, 2, payload, time = t)
        Packet.setTtl(p, 1)
        assertEquals(1, Packet.parse(p)!!.ttl)
        Packet.setTtl(p, -5)
        assertEquals(0, Packet.parse(p)!!.ttl)
        Packet.setTtl(p, 300)
        assertEquals(255, Packet.parse(p)!!.ttl)
        assertArrayEquals(payload, p.copyOfRange(Packet.HEADER, p.size))
    }

    @Test
    fun ttlIsClampedToOneByte() {
        assertEquals(255, Packet.parse(Packet.encode(1, 1, Packet.Codec.PCM, 999, payload, time = t))!!.ttl)
        assertEquals(0, Packet.parse(Packet.encode(1, 1, Packet.Codec.PCM, -3, payload, time = t))!!.ttl)
    }
}
