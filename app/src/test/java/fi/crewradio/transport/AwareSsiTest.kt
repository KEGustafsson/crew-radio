package fi.crewradio.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AwareSsiTest {

    /** Stands in for the HMAC: eight bytes that depend on the id. */
    private val tag: (Int) -> ByteArray = { id -> ByteArray(8) { i -> ((id ushr (i * 4)) xor (i * 31)).toByte() } }

    @Test
    fun encodesIdBigEndianFollowedByTheTag() {
        val ssi = AwareSsi.encode(0x12345678, tag)
        assertEquals(12, ssi.size)
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), ssi.copyOf(4))
        assertArrayEquals(tag(0x12345678), ssi.copyOfRange(4, 12))
    }

    @Test
    fun aVerifiedTagYieldsTheId() {
        assertEquals(0x12345678, AwareSsi.decode(AwareSsi.encode(0x12345678, tag), tag))
        assertEquals(-5, AwareSsi.decode(AwareSsi.encode(-5, tag), tag))
    }

    @Test
    fun aForgedOrForeignInfoIsIgnored() {
        val ssi = AwareSsi.encode(42, tag)
        ssi[11] = (ssi[11].toInt() xor 1).toByte()
        assertNull(AwareSsi.decode(ssi, tag))                         // tag altered
        val other = AwareSsi.encode(42, tag).also { it[3] = 43 }
        assertNull(AwareSsi.decode(other, tag))                       // id altered, tag no longer matches
        assertNull(AwareSsi.decode(AwareSsi.encode(42, tag)) { ByteArray(8) })   // another crew's key
        assertNull(AwareSsi.decode(byteArrayOf(0, 0, 0, 42), tag))    // the old 4-byte format
        assertNull(AwareSsi.decode(ByteArray(13), tag))
        assertNull(AwareSsi.decode(null, tag))
        assertNull(AwareSsi.decode(AwareSsi.encode(42, tag)) { throw IllegalStateException("no key") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTagOfTheWrongSizeIsRefused() {
        AwareSsi.encode(1) { ByteArray(7) }
    }
}
