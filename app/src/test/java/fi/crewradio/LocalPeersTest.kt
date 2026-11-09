package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPeersTest {
    private val me = 0x5000_0000
    private val rns = Hello.LAN or Hello.RETICULUM or Hello.RNS_UP

    /** A hello nobody relayed (hops == ttl), over a local transport. */
    private fun LocalPeers.direct(sender: Int, transports: Int, now: Long) = heardHello(sender, transports, 4, 4, true, now)

    @Test
    fun aDirectLocalHelloMakesItsSenderLocalForFifteenSeconds() {
        val p = LocalPeers()
        p.direct(7, Hello.LAN, 1_000)
        assertTrue(p.isLocal(7, 1_000))
        assertTrue(p.isLocal(7, 1_000 + LocalPeers.LOCAL_MS - 1))
        assertFalse("expired after LOCAL_MS", p.isLocal(7, 1_000 + LocalPeers.LOCAL_MS))
        p.direct(7, Hello.LAN, 20_000)
        assertTrue("and back with its next hello", p.isLocal(7, 20_000))
    }

    @Test
    fun relayedHellosAndHellosOverReticulumMakeNobodyLocal() {
        val p = LocalPeers()
        p.heardHello(7, rns, 4, 3, true, 0)             // relayed once: hops 4, ttl 3
        p.heardHello(8, rns, 4, 4, false, 0)            // unrelayed, but over Reticulum
        assertFalse(p.isLocal(7, 0))
        assertFalse(p.isLocal(8, 0))
        assertFalse(p.rnsUp(7, 0))
        assertEquals("neither is a candidate", me, p.forwarder(me, rns, 0))
    }

    @Test
    fun rnsUpIsWhatTheLastLocalHelloSaid() {
        val p = LocalPeers()
        p.direct(7, rns, 0)
        assertTrue(p.rnsUp(7, 0))
        p.direct(7, Hello.LAN or Hello.RETICULUM, 1_000)  // its socket dropped: the bit is cleared
        assertFalse(p.rnsUp(7, 1_000))
        assertFalse("unknown sender", p.rnsUp(9, 1_000))
    }

    @Test
    fun rnsUpIsRequiredToBeACandidate() {
        val p = LocalPeers()
        p.direct(1, Hello.LAN or Hello.FWD_PREFER, 0)   // prefers, but is not on Reticulum
        assertNull("nobody on Reticulum: no forwarder", p.forwarder(me, Hello.LAN, 0))
        assertEquals(me, p.forwarder(me, rns, 0))
        assertFalse(p.isForwarder(me, Hello.LAN, 0))
    }

    @Test
    fun theHighestRankWinsThenTheLowestUnsignedId() {
        val p = LocalPeers()
        p.direct(0x10, rns or Hello.FWD_AVOID, 0)       // an ESP32: rank 0, lowest id
        assertEquals("a phone (rank 1) beats an avoider", me, p.forwarder(me, rns, 0))
        p.direct(0x7FFF_FFFF, rns or Hello.FWD_PREFER, 0)  // the plugin: rank 2, high id
        assertEquals(0x7FFF_FFFF, p.forwarder(me, rns, 0))
        assertFalse(p.isForwarder(me, rns, 0))
    }

    @Test
    fun tiesGoToTheLowestIdComparedUnsigned() {
        val p = LocalPeers()
        val high = 0x8000_0000.toInt()                  // negative as a signed Int
        p.direct(high, rns, 0)
        assertEquals("1 < 0x80000000 unsigned, whatever the sign says", 1, p.forwarder(1, rns, 0))
        assertEquals(high, LocalPeers().also { it.direct(high, rns, 0) }.forwarder(0x9000_0000.toInt(), rns, 0))
        p.direct(1, rns, 0)
        assertEquals(1, p.forwarder(0x7000_0000, rns, 0))
    }

    @Test
    fun anExpiredCandidateStopsCounting() {
        val p = LocalPeers()
        p.direct(1, rns, 0)
        assertEquals(1, p.forwarder(me, rns, 0))
        assertEquals("its hello is LOCAL_MS old: we forward", me, p.forwarder(me, rns, LocalPeers.LOCAL_MS))
        assertTrue(p.isForwarder(me, rns, LocalPeers.LOCAL_MS))
    }

    @Test
    fun ourOwnIdHeardBackNeverCountsTwice() {
        val p = LocalPeers()
        p.direct(me, rns or Hello.FWD_PREFER, 0)        // a copy of our id with the prefer bit: only our own bits count
        p.direct(2, rns, 0)
        assertEquals(2, p.forwarder(me, rns or Hello.FWD_AVOID, 0))
    }

    /** With hellos in the same millisecond, a full table evicts the sender heard first, whatever the ids. */
    @Test
    fun aTieOnTheClockIsBrokenByHearingOrder() {
        // Hellos in the same millisecond: the first heard is the one to go, whatever the ids hash to.
        for (order in listOf(listOf(1, 2, 3), listOf(3, 2, 1), listOf(64, 0x8000_0000.toInt(), 7))) {
            val p = LocalPeers(capacity = 3)
            for (id in order) p.direct(id, Hello.LAN, 500)
            p.direct(order[0], Hello.LAN, 500)              // the first heard again: now the second is eldest
            p.direct(99, Hello.LAN, 500)
            assertFalse("$order", p.isLocal(order[1], 500))
            assertTrue(p.isLocal(order[0], 500))
            assertTrue(p.isLocal(order[2], 500))
            assertTrue(p.isLocal(99, 500))
        }
    }

    @Test
    fun aFullTableMakesRoomByForgettingTheSenderHeardLongestAgo() {
        val p = LocalPeers(capacity = 2)
        p.direct(1, Hello.LAN, 0); p.direct(2, Hello.LAN, 100)
        p.direct(1, Hello.LAN, 200)                      // 1 heard again: 2 is now the oldest
        p.direct(3, Hello.LAN, 300)
        assertTrue("a newcomer is never refused", p.isLocal(3, 300))
        assertTrue(p.isLocal(1, 300))
        assertFalse("the one heard longest ago made room", p.isLocal(2, 300))
        assertEquals(2, p.count(300))
        p.direct(4, Hello.LAN, LocalPeers.LOCAL_MS + 300) // the others expired and are swept
        assertTrue(p.isLocal(4, LocalPeers.LOCAL_MS + 300))
    }
}
