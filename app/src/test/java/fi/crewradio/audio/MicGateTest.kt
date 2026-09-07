package fi.crewradio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicGateTest {

    private fun frame(vararg samples: Short): ByteArray {
        val f = ByteArray(AudioConfig.FRAME_BYTES)
        for (i in 0 until AudioConfig.FRAME_SAMPLES) {
            val s = samples[i % samples.size].toInt()
            f[2 * i] = (s and 0xFF).toByte()
            f[2 * i + 1] = (s shr 8).toByte()
        }
        return f
    }

    @Test
    fun rmsOfSilenceAndOfAKnownSquareWave() {
        assertEquals(0.0, MicGate.rms(frame(0)), 0.0)
        assertEquals(1000.0, MicGate.rms(frame(1000, -1000)), 0.001)
    }

    @Test
    fun speechOpensAfterTheAttackAndQuietClosesAfterTheHang() {
        val g = MicGate()
        assertNull(g.feed(frame(300, -300)))                      // one loud frame is not yet speech
        assertEquals(MicGate.Change.OPEN, g.feed(frame(300, -300)))
        assertNull(g.feed(frame(300, -300)))
        repeat(MicGate.HANG_FRAMES - 1) { assertNull(g.feed(frame(0))) }
        assertEquals(MicGate.Change.CLOSE, g.feed(frame(0)))
        assertFalse(g.open)
    }

    @Test
    fun aSpikeDoesNotOpen() {
        val g = MicGate()
        repeat(50) { assertNull(g.feed(20.0)); assertNull(g.feed(2.0)) }   // the headset's idle pattern
        assertNull(g.feed(500.0)); assertNull(g.feed(2.0))                  // a lone loud frame
        assertFalse(g.open)
    }

    @Test
    fun aPauseBetweenWordsStaysOpen() {
        val g = MicGate()
        g.feed(500.0); g.feed(500.0)
        repeat(MicGate.HANG_FRAMES - 1) { g.feed(2.0) }
        assertNull(g.feed(500.0))                                  // speech again: still open, counter reset
        assertTrue(g.open)
        repeat(MicGate.HANG_FRAMES - 1) { assertNull(g.feed(2.0)) }
        assertTrue(g.open)
    }

    @Test
    fun levelsBetweenTheThresholdsChangeNothing() {
        val g = MicGate()
        g.feed(500.0); g.feed(500.0)
        repeat(200) { assertNull(g.feed(60.0)) }                   // above close, below open: neither
        assertTrue(g.open)
        g.reset()
        repeat(200) { assertNull(g.feed(60.0)) }
        assertFalse(g.open)
    }

    @Test
    fun thePhoneMicNeedsCloseTalkAndCountsAQuietTableAsQuiet() {
        val g = MicGate()
        g.tune(phoneMic = true)
        assertEquals(MicGate.PHONE_OPEN, g.openRms, 0.0)
        assertEquals(MicGate.PHONE_CLOSE, g.closeRms, 0.0)
        repeat(10) { assertNull(g.feed(200.0)) }                   // a headset's speech level: room noise on the phone
        assertFalse(g.open)
        assertNull(g.feed(400.0))
        assertEquals(MicGate.Change.OPEN, g.feed(400.0))           // close talk peaks 400-1150
        repeat(MicGate.HANG_FRAMES - 1) { assertNull(g.feed(100.0)) }   // 100 would hold a headset open; not the phone
        assertEquals(MicGate.Change.CLOSE, g.feed(100.0))
        g.tune(phoneMic = false)
        assertEquals(MicGate.HEADSET_OPEN, g.openRms, 0.0)
        assertEquals(MicGate.HEADSET_CLOSE, g.closeRms, 0.0)
        assertNull(g.feed(200.0))
        assertEquals(MicGate.Change.OPEN, g.feed(200.0))           // the same 200 keys a headset
    }
}
