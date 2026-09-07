package fi.crewradio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TonesTest {

    private fun sample(f: ByteArray, i: Int): Int =
        ((f[2 * i + 1].toInt() shl 8) or (f[2 * i].toInt() and 0xFF))

    @Test
    fun cuesAreWholeFramesOfTheExpectedLength() {
        assertEquals(4, Tones.micOn().size)                 // 80 ms
        assertEquals(8, Tones.micOff().size)                // 60 + 40 + 60 ms
        for (f in Tones.micOn() + Tones.micOff()) assertEquals(AudioConfig.FRAME_BYTES, f.size)
    }

    @Test
    fun beepIsRampedAndWellUnderFullScale() {
        val b = Tones.beep(880.0, 80)
        assertEquals(0, b[0].toInt())
        assertEquals(0, b[b.size - 1].toInt())
        val peak = b.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 4000..9000)         // ~0.25 of full scale
    }

    @Test
    fun cuesLeaveHeadroomForTheSpeechTheySitOn() {
        // The mixer adds a cue at full level on top of the summed, scaled speech: under 0.3 FS
        // the beep and a talker at full scale still clip only at the very peaks.
        var peak = 0
        for (f in Tones.micOn() + Tones.micOff()) for (i in 0 until AudioConfig.FRAME_SAMPLES) peak = maxOf(peak, abs(sample(f, i)))
        assertTrue("peak $peak", peak < 0.3 * 32768)
        assertTrue("peak $peak", peak > 0)
    }

    @Test
    fun silenceAndPaddingAreZero() {
        val frames = Tones.frames(Tones.beep(440.0, 30))    // 1.5 frames: the second is half padding
        assertEquals(2, frames.size)
        val last = frames[1]
        for (i in AudioConfig.FRAME_SAMPLES / 2 until AudioConfig.FRAME_SAMPLES) assertEquals(0, sample(last, i))
        assertTrue(Tones.silence(40).all { it.toInt() == 0 })
    }
}
