package fi.crewradio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DecimatorTest {

    private val frame48k = 960   // one 20 ms frame at 48 kHz

    /** A sine of [hz] at 48 kHz, [n] samples, 8000 peak. */
    private fun sine(hz: Double, n: Int) = ShortArray(n) { (8000 * sin(2 * PI * hz * it / 48000)).toInt().toShort() }

    /** Level of [out] past the filter's settling, in dB relative to the input sine's RMS. */
    private fun levelDb(out: ShortArray): Double {
        var acc = 0.0
        for (i in 200 until out.size) acc += out[i].toDouble() * out[i]
        val rms = sqrt(acc / (out.size - 200))
        return 20 * log10(rms / (8000 / sqrt(2.0)))
    }

    @Test
    fun produces320SamplesPerFrameEveryFrame() {
        val d = Decimator(3)
        repeat(5) { assertEquals(320, d.process(ShortArray(frame48k)).size) }
    }

    @Test
    fun keepsPhaseAcrossOddSizedChunks() {
        val d = Decimator(3)
        val total = d.process(ShortArray(7)).size + d.process(ShortArray(500)).size + d.process(ShortArray(453)).size
        assertEquals(320, total)
    }

    @Test
    fun chunkingDoesNotChangeTheOutput() {
        val whole = Decimator(3).process(sine(1000.0, 3 * frame48k))
        val chunked = Decimator(3)
        val input = sine(1000.0, 3 * frame48k)
        val parts = ArrayList<Short>()
        var at = 0
        for (len in intArrayOf(7, 500, 453, 960, 1, 959)) {
            for (s in chunked.process(input.copyOfRange(at, at + len))) parts.add(s)
            at += len
        }
        assertEquals(input.size, at)
        assertArrayEquals(whole, parts.toShortArray())
    }

    @Test
    fun passesDcAtUnityGain() {
        val d = Decimator(3)
        val out = d.process(ShortArray(frame48k) { 1000 })
        for (i in 40 until out.size) assertTrue("sample $i = ${out[i]}", abs(out[i] - 1000) <= 2)
    }

    @Test
    fun passesSibilantsAt6kHzWithinOneDecibel() {
        val out = Decimator(3).process(sine(6000.0, 20 * frame48k))
        val db = levelDb(out)
        assertTrue("6 kHz at $db dB", abs(db) < 1.0)
    }

    @Test
    fun stops9kHzBeforeItFoldsOntoTheSpeech() {
        val out = Decimator(3).process(sine(9000.0, 20 * frame48k))
        val db = levelDb(out)
        assertTrue("9 kHz at $db dB", db < -20.0)
    }

    @Test
    fun attenuatesAliasingContentAboveNewNyquist() {
        val d = Decimator(3)
        // Alternating +/-8000 is a tone at 24 kHz; after decimation it must be gone, not folded to 8 kHz.
        val out = d.process(ShortArray(frame48k) { if (it % 2 == 0) 8000 else -8000 })
        for (i in 40 until out.size) assertTrue("sample $i = ${out[i]}", abs(out[i].toInt()) < 200)
    }

    @Test
    fun unityDcGainForAnyFactor() {
        for (f in 1..6) assertEquals(1.0, Decimator.design(f).sum().toDouble(), 1e-5)
    }

    @Test
    fun sixteenTapsPerUnitOfFactor() {
        assertEquals(49, Decimator.design(3).size)
        assertEquals(33, Decimator.design(2).size)
    }
}
