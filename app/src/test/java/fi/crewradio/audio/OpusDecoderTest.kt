package fi.crewradio.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The synthesised OpusHead against RFC 7845 section 5.1, and the csd-1 MediaCodec wants with it. */
class OpusDecoderTest {

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl 16)

    @Test
    fun opusHeadIsTheNineteenByteIdentificationHeader() {
        val h = OpusDecoder.opusHead(channels = 1, inputRate = 16_000)
        assertEquals(19, h.size)
        assertArrayEquals("OpusHead".toByteArray(Charsets.US_ASCII), h.copyOfRange(0, 8))
        assertEquals(1, h[8].toInt())                    // version
        assertEquals(1, h[9].toInt())                    // channel count
        assertEquals(312, le16(h, 10))                   // pre-skip, samples at 48 kHz
        assertEquals(16_000, le32(h, 12))                // input sample rate
        assertEquals(0, le16(h, 16))                     // output gain
        assertEquals(0, h[18].toInt())                   // channel mapping family
    }

    @Test
    fun preSkipMatchesTheHeaderInNanoseconds() {
        assertEquals(312, OpusDecoder.PRE_SKIP)
        val ns = OpusDecoder.preSkipNs()
        assertEquals(8, ns.size)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (ns[i].toLong() and 0xFF)
        assertEquals(6_500_000L, v)                      // 312 / 48000 s
    }

    @Test
    fun channelCountIsWrittenAsGiven() {
        assertEquals(2, OpusDecoder.opusHead(channels = 2, inputRate = 48_000)[9].toInt())
        assertEquals(48_000, le32(OpusDecoder.opusHead(channels = 2, inputRate = 48_000), 12))
    }
}
