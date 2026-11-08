package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FloorTest {
    private val me = 0x4000_0000
    private val lower = 0x1000_0000
    private val higher = 0x7000_0000

    @Test
    fun aQuietChannelKeysAtOnce() {
        val f = Floor(me)
        assertSame(Floor.Key.OnAir, f.key(0))
        assertEquals(Floor.State.TALKING, f.state)
        assertTrue(f.onAir)
        assertSame("a second press changes nothing", Floor.Key.Held, f.key(10))
    }

    @Test
    fun aKeyWhileAnotherTalkerIsHeardIsRefusedAndHeldSilent() {
        val f = Floor(me)
        f.heard(higher, 1_000)
        val k = f.key(1_000 + Floor.BUSY_MS)
        assertTrue(k is Floor.Key.Refused)
        assertEquals(higher, (k as Floor.Key.Refused).by)
        assertEquals(Floor.State.WAITING, f.state)
        assertTrue(f.keyed)
        assertFalse(f.onAir)
    }

    @Test
    fun theChannelIsFreeOnceBusyMsHasPassed() {
        val f = Floor(me)
        f.heard(higher, 1_000)
        assertEquals(higher, f.busy(1_000 + Floor.BUSY_MS))
        assertNull(f.busy(1_000 + Floor.BUSY_MS + 1))
        assertSame(Floor.Key.OnAir, f.key(1_000 + Floor.BUSY_MS + 1))
    }

    @Test
    fun ourOwnFramesNeverMakeTheChannelBusy() {
        val f = Floor(me)
        assertFalse(f.heard(me, 0))
        assertNull(f.busy(0))
        assertSame(Floor.Key.OnAir, f.key(0))
    }

    @Test
    fun aRefusedKeyStillHeldKeysAnywayAfterOverrideMs() {
        val f = Floor(me)
        f.heard(lower, 0)
        f.key(100)
        assertEquals(100 + Floor.OVERRIDE_MS, f.overrideAt())
        assertFalse(f.due(100 + Floor.OVERRIDE_MS - 1))
        assertTrue(f.due(100 + Floor.OVERRIDE_MS))
        assertEquals(Floor.State.OVERRIDE, f.state)
        assertTrue(f.onAir)
        assertNull(f.overrideAt())
        assertFalse("an override burst does not yield, even to a lower id", f.heard(lower, 1_200))
        assertTrue(f.onAir)
    }

    @Test
    fun aRefusedKeyReleasedBeforeTheOverrideNeverKeys() {
        val f = Floor(me)
        f.heard(lower, 0)
        f.key(0)
        f.release()
        assertFalse(f.due(Floor.OVERRIDE_MS))
        assertEquals(Floor.State.IDLE, f.state)
    }

    @Test
    fun ofTwoCollidingTalkersOnlyTheHigherIdYields() {
        val f = Floor(me)
        f.key(0)
        assertFalse("a higher id yields to us", f.heard(higher, 20))
        assertTrue(f.onAir)
        assertTrue("a lower id has the channel: we yield", f.heard(lower, 40))
        assertEquals(Floor.State.LOCKED, f.state)
        assertFalse(f.onAir)
        assertTrue("still held", f.keyed)
    }

    @Test
    fun theTieBreakIsUnsigned() {
        val f = Floor(1)
        f.key(0)
        assertFalse("0x80000000 is higher than 1 unsigned, though negative signed", f.heard(0x8000_0000.toInt(), 10))
        val g = Floor(0x8000_0000.toInt())
        g.key(0)
        assertTrue(g.heard(1, 10))
        // Both ends compare the same two ids: exactly one survives.
        val a = Floor(0x8000_0000.toInt()); val b = Floor(1)
        a.key(0); b.key(0)
        val aYields = a.heard(1, 10)
        val bYields = b.heard(0x8000_0000.toInt(), 10)
        assertTrue(aYields != bYields)
    }

    @Test
    fun aYieldedKeyStaysOffUntilReleasedThenListensAgain() {
        val f = Floor(me)
        f.key(0)
        f.heard(lower, 10)
        assertFalse("no override from a lock-out", f.due(10 + 10 * Floor.OVERRIDE_MS))
        assertSame(Floor.Key.Held, f.key(5_000))
        assertEquals(Floor.State.LOCKED, f.state)
        f.release()
        f.heard(lower, 5_000)
        assertTrue("rule 1 again", f.key(5_100) is Floor.Key.Refused)
        f.release()
        assertSame(Floor.Key.OnAir, f.key(6_000))
    }

    @Test
    fun withTheRulesOffTheKeyGoesOnAirAndNeverYields() {
        val f = Floor(me)
        f.heard(lower, 0)
        assertSame(Floor.Key.OnAir, f.key(0, listen = false))
        assertFalse(f.heard(lower, 20))
        assertTrue(f.onAir)
    }

    @Test
    fun theLastTalkerIsRemembered() {
        val f = Floor(me)
        assertNull(f.lastTalker)
        f.heard(lower, 0)
        f.heard(higher, 10)
        assertEquals(higher, f.lastTalker)
    }
}
