package fi.crewradio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMemoryTest {
    private val call = 0
    private val sco = 6

    @Test
    fun firstLookTakesTheDevicesLevel() {
        val m = VolumeMemory()
        assertEquals(1, m.level(call, 1))
        assertEquals(1, m.level(call, 3))               // held now: another device's level does not replace it
    }

    @Test
    fun joiningPutsTheOffChannelLevelOnTheLoudspeaker() {
        // Seen on the S25, 2026-09-26: 1 off channel (earpiece), 3 on channel (loudspeaker).
        val m = VolumeMemory()
        assertEquals(1, m.level(call, 1))
        assertEquals(1, m.restore(call, 3))
        assertNull(m.restore(call, 1))                  // once set, nothing more to do
    }

    @Test
    fun leavingPutsTheOnChannelChoiceOnTheEarpiece() {
        val m = VolumeMemory()
        m.level(call, 1)
        m.chose(call, 4)                                // slider moved on channel
        assertEquals(4, m.restore(call, 1))             // back on the earpiece after leaving
    }

    @Test
    fun aHeadsetButtonIsAChoice() {
        val m = VolumeMemory()
        m.level(call, 2)
        assertTrue(m.changed(call, 2, 5))
        assertEquals(5, m.level(call, 2))
        assertEquals(5, m.restore(call, 2))
    }

    @Test
    fun noChangeOrMissingExtrasAreIgnored() {
        val m = VolumeMemory()
        m.level(call, 2)
        assertFalse(m.changed(call, 5, 5))
        assertFalse(m.changed(call, -1, 5))
        assertFalse(m.changed(call, 5, -1))
        assertEquals(2, m.level(call, 7))
    }

    @Test
    fun theRestoresOwnBroadcastChangesNothing() {
        val m = VolumeMemory()
        m.level(call, 1)
        val set = m.restore(call, 3)!!
        m.changed(call, 3, set)                         // the broadcast setStreamVolume sends
        assertEquals(1, m.level(call, 3))
    }

    @Test
    fun streamsAreHeldApart() {
        // Below Android 14 a Bluetooth headset has a stream of its own, in steps of its own.
        val m = VolumeMemory()
        m.level(call, 3)
        m.chose(sco, 12)
        assertEquals(3, m.level(call, 1))
        assertEquals(12, m.level(sco, 9))
    }
}
