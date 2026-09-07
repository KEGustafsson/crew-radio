package fi.crewradio.transport

import fi.crewradio.Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class StreamLinkTest {

    private fun framed(vararg lengths: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (len in lengths) {
            out.write(len ushr 8); out.write(len and 0xFF)
            out.write(ByteArray(len) { len.toByte() })
        }
        return out.toByteArray()
    }

    /** Reads frames until the stream ends or a frame is refused; returns the lengths read and whether it was refused. */
    private fun read(bytes: ByteArray): Pair<List<Int>, Boolean> {
        val got = mutableListOf<Int>()
        val link = StreamLink("t", ByteArrayInputStream(bytes), OutputStream.nullOutputStream()) {}
        val refused = try {
            link.readLoop { got += it.size }
            false
        } catch (e: IOException) {
            e.message?.startsWith("bad frame length") == true
        }
        return got to refused
    }

    @Test
    fun acceptsFramesBetweenTheHeaderAndTheMaximum() {
        val (got, refused) = read(framed(Packet.HEADER + 1, 200, Packet.MAX_SIZE))
        assertEquals(listOf(Packet.HEADER + 1, 200, Packet.MAX_SIZE), got)
        assertFalse(refused)                                  // the stream simply ended
    }

    @Test
    fun aFrameNoLongerThanAHeaderIsNotAPacket() {
        val (got, refused) = read(framed(Packet.HEADER + 1, Packet.HEADER, Packet.HEADER + 1))
        assertEquals(listOf(Packet.HEADER + 1), got)
        assertTrue(refused)
    }

    @Test
    fun aFrameOverTheMaximumDropsTheLink() {
        val (got, refused) = read(framed(Packet.MAX_SIZE + 1))
        assertTrue(got.isEmpty())
        assertTrue(refused)
    }

    @Test
    fun writesLengthPrefixedFrames() {
        val out = ByteArrayOutputStream()
        val link = StreamLink("t", ByteArrayInputStream(ByteArray(0)), out) {}
        link.write(byteArrayOf(1, 2, 3))
        link.write(ByteArray(300))
        assertArrayEquals(framed(3).copyOf(2), out.toByteArray().copyOf(2))
        assertEquals(2 + 3 + 2 + 300, out.size())
        assertEquals(300 ushr 8, out.toByteArray()[5].toInt())
        assertEquals(300 and 0xFF, out.toByteArray()[6].toInt() and 0xFF)
    }

    @Test
    fun theSenderThreadWritesWhatWasOffered() {
        val out = ByteArrayOutputStream()
        var closed = false
        val link = StreamLink("t", ByteArrayInputStream(ByteArray(0)), out) { closed = true }
        val tx = Thread { link.sendLoop() }
        tx.start()
        assertTrue(link.offer(byteArrayOf(9)))
        assertTrue(link.offer(byteArrayOf(8, 7)))
        val deadline = System.currentTimeMillis() + 2_000
        while (synchronized(out) { out.size() } < 7 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertArrayEquals(byteArrayOf(0, 1, 9, 0, 2, 8, 7), out.toByteArray())
        link.close()
        tx.join(2_000)
        assertFalse(tx.isAlive)                               // close() ended the writer
        assertTrue(closed)
        assertFalse(link.offer(byteArrayOf(1)))               // nothing goes out on a closed link
    }
}
