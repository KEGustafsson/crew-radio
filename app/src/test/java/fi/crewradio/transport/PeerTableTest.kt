package fi.crewradio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTableTest {

    @Test
    fun peersHeardWithinTheTtlAreLiveTheRestAreForgotten() {
        val t = PeerTable<String, Unit>(ttlMs = 5_000)
        t.put("a", Unit, 0)
        t.put("b", Unit, 3_000)
        assertEquals(setOf("a", "b"), t.live(5_000).toSet())     // exactly the ttl ago still counts
        assertEquals(setOf("b"), t.live(5_001).toSet())
        assertEquals(listOf("b"), t.expire(8_001))
        assertEquals(0, t.size)
    }

    @Test
    fun aSightingRefreshesAndReplacesTheValue() {
        val t = PeerTable<Int, String>(ttlMs = 1_000)
        t.put(1, "old", 0)
        t.put(1, "new", 900)
        assertEquals("new", t.get(1))
        assertEquals(listOf(1), t.live(1_800))
        assertTrue(t.touch(1, 1_800))
        assertEquals(listOf(1), t.live(2_700))
        assertFalse(t.touch(2, 0))
        assertNull(t.get(2))
    }

    @Test
    fun theStalestEntryMakesRoomWhenFull() {
        val t = PeerTable<Int, Unit>(ttlMs = 60_000, max = 3)
        t.put(1, Unit, 10)
        t.put(2, Unit, 5)
        t.put(3, Unit, 20)
        t.put(4, Unit, 30)
        assertEquals(3, t.size)
        assertFalse(t.contains(2))                          // unseen the longest
        assertEquals(setOf(1, 3, 4), t.keys.toSet())
    }

    @Test
    fun findsAKeyByItsValue() {
        val t = PeerTable<Int, String>(ttlMs = 1_000)
        t.put(7, "x", 0)
        assertEquals(7, t.keyWhere { it == "x" })
        assertNull(t.keyWhere { it == "y" })
        assertEquals("x", t.remove(7))
        assertNull(t.remove(7))
    }
}
