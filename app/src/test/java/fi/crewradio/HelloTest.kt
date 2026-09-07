package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HelloTest {

    private fun roundTrip(h: Hello): Hello {
        val bytes = h.encode()
        return Hello.decode(bytes, 0, bytes.size)!!
    }

    @Test
    fun roundTripsNameTransportsTtlAndBuild() {
        val h = roundTrip(Hello("Skipper S25", Hello.LAN or Hello.AWARE, 4, 134))
        assertEquals("Skipper S25", h.name)
        assertEquals(Hello.LAN or Hello.AWARE, h.transports)
        assertEquals(4, h.ttl)
        assertEquals(134, h.versionCode)
        assertEquals(0, roundTrip(Hello("plugin", Hello.LAN, 4)).versionCode)    // not an app build
    }

    @Test
    fun buildNumberIsSixteenBitsBigEndian() {
        val bytes = Hello("x", 0, 4, 0x1234).encode()
        assertEquals(0x12, bytes[3].toInt())
        assertEquals(0x34, bytes[4].toInt())
        assertEquals(0xFFFF, roundTrip(Hello("x", 0, 4, 70_000)).versionCode)   // clamped, never wrapped
        assertEquals(0, roundTrip(Hello("x", 0, 4, -1)).versionCode)
    }

    @Test
    fun decodesAtAnOffsetInsideALargerBuffer() {
        val body = Hello("x", Hello.BT, 2).encode()
        val buf = ByteArray(5) + body + ByteArray(3)
        val h = Hello.decode(buf, 5, body.size)!!
        assertEquals("x", h.name)
        assertEquals(Hello.BT, h.transports)
    }

    @Test
    fun truncatesLongNamesOnACodePointBoundary() {
        // 20 x "ä" is 40 bytes of UTF-8; only 16 (32 bytes) fit, and never half of one.
        val h = roundTrip(Hello("ä".repeat(20), 0, 4))
        assertEquals("ä".repeat(16), h.name)
        assertEquals(Hello.MAX_NAME_BYTES, h.encode().size - 6)

        val emoji = roundTrip(Hello("🚤".repeat(9), 0, 4))   // 9 boats x 4 bytes = 36
        assertEquals("🚤".repeat(8), emoji.name)
    }

    @Test
    fun anEmptyNameIsAllowed() {
        val bytes = byteArrayOf(2, 1, 4, 0, 0, 0)
        assertEquals("", Hello.decode(bytes, 0, bytes.size)!!.name)
        assertEquals("", roundTrip(Hello("", 0, 4)).name)
    }

    @Test
    fun rejectsPayloadsOffTheWireContract() {
        assertNull(Hello.decode(byteArrayOf(2, 0, 4, 0, 0), 0, 5))                   // too short for a header
        assertNull(Hello.decode(byteArrayOf(1, 0, 4, 0), 0, 4))                      // version 1: no build number
        assertNull(Hello.decode(byteArrayOf(3, 0, 4, 0, 0, 0), 0, 6))                // unknown version
        assertNull(Hello.decode(byteArrayOf(2, 0, 4, 0, 0, 5, 65, 66), 0, 8))        // claims 5 name bytes, has 2
        assertNull(Hello.decode(byteArrayOf(2, 0, 4, 0, 0, 1, 65, 66), 0, 8))        // claims 1, has 2: trailing junk
        assertNull(Hello.decode(byteArrayOf(2, 0, 4, 0, 0, 1, -1), 0, 7))            // 0xFF is never valid UTF-8
        val tooLong = byteArrayOf(2, 0, 4, 0, 0, 33) + ByteArray(33) { 65 }         // 33 > MAX_NAME_BYTES
        assertNull(Hello.decode(tooLong, 0, tooLong.size))

        // Off the array rather than off the contract: still null, never an exception.
        val ok = byteArrayOf(2, 0, 4, 0, 0, 1, 65)
        assertNull("offset past the end", Hello.decode(ok, 99, ok.size))
        assertNull("negative offset", Hello.decode(ok, -1, ok.size))
        assertNull("length past the end", Hello.decode(ok, 0, ok.size + 8))
        assertNull("offset plus length past the end", Hello.decode(ok, 2, ok.size))
    }

    @Test
    fun stripsControlCharactersFromTheName() {
        val bytes = byteArrayOf(2, 0, 4, 0, 0, 5, 65, 10, 66, 0, 67)               // "A\nB\u0000C"
        assertEquals("ABC", Hello.decode(bytes, 0, bytes.size)!!.name)
    }

    @Test
    fun stripsFormatCharactersThatReorderOrHideText() {
        // Written as escapes on purpose: these characters are invisible in an editor, which is the problem with them.
        assertEquals("Skipper", Hello.sanitise("\u202ESkipper\u202C"))          // right-to-left override, pop
        assertEquals("Skipper", Hello.sanitise("Skip\u200Bper"))                 // zero-width space
        assertEquals("Skipper", Hello.sanitise("\uFEFFSkipper"))                 // byte order mark
        assertEquals("Skipper", Hello.sanitise("\u2066Skip\u2069per"))           // isolates
        assertEquals("Skipper", Hello.sanitise("Skip\u200Dper"))                 // zero-width joiner
        assertEquals("Skipper", roundTrip(Hello("\u202ESkipper", 0, 4)).name)
    }

    @Test
    fun collapsesWhitespaceAndTrims() {
        assertEquals("Skipper S25", Hello.sanitise("  Skipper \t  S25 "))
        assertEquals("Skipper S25", Hello.sanitise("Skipper\u00A0\u2003S25"))   // no-break and em spaces
        assertEquals("", Hello.sanitise("   "))
        assertEquals("ä ö", roundTrip(Hello("ä   ö", 0, 4)).name)
    }

    @Test
    fun describesTransportFlags() {
        assertEquals("", Hello.describe(0))
        assertEquals("LAN+BT+Aware", Hello.describe(Hello.LAN or Hello.BT or Hello.AWARE))
        assertEquals(Hello.AWARE, Hello.bitFor("Aware"))
        assertEquals(0, Hello.bitFor("Carrier pigeon"))
    }
}
