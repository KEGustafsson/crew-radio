package fi.crewradio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeMemoryTest {
    // The voice-call stream, 1-7 on the phones seen, and the SCO stream below Android 14, 0-15.
    private val call = 0
    private val callMin = 1
    private val callMax = 7
    private val sco = 6
    private val scoMin = 0
    private val scoMax = 15

    private fun VolumeMemory.call(current: Int) = level(call, current, callMin, callMax)
    private fun VolumeMemory.sco(current: Int) = level(sco, current, scoMin, scoMax)

    @Test
    fun firstLookTakesTheDevicesLevel() {
        val m = VolumeMemory()
        assertEquals(1, m.call(1))
        assertEquals(1, m.call(3))                      // held now: another device's level does not replace it
    }

    @Test
    fun joiningPutsTheOffChannelLevelOnTheLoudspeaker() {
        // Seen on the S25, 2026-09-26: 1 off channel (earpiece), 3 on channel (loudspeaker).
        val m = VolumeMemory()
        assertEquals(1, m.call(1))
        assertEquals(1, m.restore(call, 3, callMin, callMax))
        assertNull(m.restore(call, 1, callMin, callMax)) // once set, nothing more to do
    }

    @Test
    fun leavingPutsTheOnChannelChoiceOnTheEarpiece() {
        val m = VolumeMemory()
        m.call(1)
        m.chose(call, 4, callMax)                       // slider moved on channel
        assertEquals(4, m.restore(call, 1, callMin, callMax))
    }

    @Test
    fun aHeadsetButtonIsAChoice() {
        val m = VolumeMemory()
        m.call(2)
        assertTrue(m.changed(call, 2, 5, 5, callMax))
        assertEquals(5, m.call(2))
        assertEquals(5, m.restore(call, 2, callMin, callMax))
    }

    @Test
    fun noChangeOrMissingExtrasAreIgnored() {
        val m = VolumeMemory()
        m.call(2)
        assertFalse(m.changed(call, 5, 5, 5, callMax))
        assertFalse(m.changed(call, -1, 5, 5, callMax))
        assertFalse(m.changed(call, 5, -1, 5, callMax))
        assertEquals(2, m.call(7))
    }

    @Test
    fun theRestoresOwnBroadcastChangesNothing() {
        val m = VolumeMemory()
        m.call(1)
        val set = m.restore(call, 3, callMin, callMax)!!
        m.changed(call, 3, set, set, callMax)           // the broadcast setStreamVolume sends
        assertEquals(1, m.call(3))
    }

    @Test
    fun aLateBroadcastDoesNotUndoANewerChoice() {
        val m = VolumeMemory()
        m.call(1)
        val set = m.restore(call, 3, callMin, callMax)!! // writes 1; its broadcast is still on the way
        m.chose(call, 4, callMax)                       // the crew moves the slider meanwhile
        assertFalse(m.changed(call, 3, set, 4, callMax)) // the stream reads 4 now: the late 1 is not taken
        assertEquals(4, m.call(4))
    }

    @Test
    fun aBluetoothHeadsetStreamGetsTheSameShare() {
        // Below Android 14 the headset is the SCO stream: joining with one must not jump either.
        val m = VolumeMemory()
        m.call(4)                                       // 4 of 7 off channel
        assertEquals(9, m.sco(2))                       // 4/7 of 15 = 8.6
        assertEquals(9, m.restore(sco, 2, scoMin, scoMax))
    }

    @Test
    fun goingBackAndForthDoesNotDrift() {
        val m = VolumeMemory()
        m.chose(sco, 12, scoMax)                        // set on the headset
        assertEquals(6, m.call(1))                      // 12/15 of 7 = 5.6
        assertEquals(12, m.sco(3))                      // back on the headset: the choice itself, not 6 converted back
        assertEquals(6, m.call(1))
    }

    @Test
    fun theLowestCallStepIsNeverASilentHeadset() {
        assertEquals(2, VolumeMemory.convert(1, callMax, scoMin, scoMax))   // 1/7 of 15 = 2.1
        assertEquals(1, VolumeMemory.convert(0, scoMax, callMin, callMax))  // clamped to the call floor
        assertEquals(7, VolumeMemory.convert(15, scoMax, callMin, callMax))
        assertEquals(3, VolumeMemory.convert(3, callMax, callMin, callMax)) // same range: unchanged
    }
}
