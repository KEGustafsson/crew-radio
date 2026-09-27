package fi.crewradio.rns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater

class AskCarryTest {
    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(bytes)
        d.finish()
        val out = ByteArray(bytes.size + 64)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    @Test
    fun partsInAnyOrderCopiesIgnoredOnlyItsOwnKindTaken() {
        val big = ByteArray(AskCarry.ROOM * 3 + 5) { it.toByte() }
        val parts = AskCarry.cut(AskCarry.ANSWER, 42, big)
        assertEquals(4, parts.size)
        assertTrue(parts.all { it.size <= RnsLink.MDU && AskCarry.isAsk(it) })
        val j = AskCarry.Assembler(AskCarry.ANSWER)
        assertNull(j.push(parts[2]))
        assertNull(j.push(parts[0]))
        assertNull("a copy", j.push(parts[0]))
        assertNull(j.push(parts[3]))
        val whole = j.push(parts[1])!!
        assertEquals(42, whole.first)
        assertArrayEquals(big, whole.second)
        assertNull("an answer is not a request", AskCarry.Assembler(AskCarry.REQUEST).push(parts[0]))
        assertFalse("a part carries at least a byte", AskCarry.isAsk(byteArrayOf(0x82.toByte(), 0, 3, 0, 1)))
        assertFalse("the channel's frames are not questions", AskCarry.isAsk(Carry.cut(ByteArray(686), 1)[0]))
        assertFalse(AskCarry.isAsk(Carry.keyProof(ByteArray(32), ByteArray(16), true)))
        assertNull("nor is a question a channel frame", Carry.Joiner().push(parts[0]))
    }

    @Test
    fun malformedPartsAndTooManyMessagesInFlight() {
        val r = AskCarry.REQUEST.toByte()
        val k = AskCarry.Assembler(AskCarry.REQUEST)
        assertNull("index past the count", k.push(byteArrayOf(r, 0, 1, 2, 2, 9)))
        assertNull("more parts than a request may have", k.push(byteArrayOf(r, 0, 1, 0, (AskCarry.MAX_REQUEST_PARTS + 1).toByte(), 9)))
        assertNull(k.push(byteArrayOf(r, 0, 1, 0, 2, 9)))
        assertNull("another count: started over from this part", k.push(byteArrayOf(r, 0, 1, 1, 3, 9)))
        assertNull(k.push(byteArrayOf(r, 0, 1, 0, 3, 8)))
        assertArrayEquals(byteArrayOf(8, 9, 7), k.push(byteArrayOf(r, 0, 1, 2, 3, 7))!!.second)
        val m = AskCarry.Assembler(AskCarry.REQUEST)
        for (id in 0..AskCarry.IN_FLIGHT) m.push(byteArrayOf(r, 0, id.toByte(), 0, 2, id.toByte()))
        assertEquals(AskCarry.IN_FLIGHT, m.held)
        assertNull("the oldest was dropped", m.push(byteArrayOf(r, 0, 0, 1, 2, 0)))
        assertEquals(0xfffe, AskCarry.idOf(AskCarry.cut(AskCarry.REQUEST, 0xfffe, byteArrayOf(1))[0]))
    }

    @Test
    fun sizesAndReplies() {
        assertEquals(AskCarry.MAX_ANSWER_PARTS, AskCarry.cut(AskCarry.ANSWER, 1, ByteArray(AskCarry.ROOM * AskCarry.MAX_ANSWER_PARTS)).size)
        assertEquals(1, AskCarry.cut(AskCarry.ANSWER, 1, byteArrayOf(0)).size)
        try {
            AskCarry.cut(AskCarry.REQUEST, 1, ByteArray(AskCarry.ROOM * AskCarry.MAX_REQUEST_PARTS + 1))
            throw AssertionError("too large to carry")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(AskCarry.reply(ByteArray(0)))
        val r = AskCarry.reply(byteArrayOf(3, 'x'.code.toByte()))!!
        assertEquals(AskCarry.FAILED, r.status)
        assertArrayEquals("x".toByteArray(), r.body)
        assertArrayEquals(byteArrayOf(2, 'h'.code.toByte()), AskCarry.request(AskCarry.OP_SAY, "h".toByteArray()))
    }

    @Test
    fun inflateReadsZlibAndRefusesJunkTruncationAndBombs() {
        val json = """{"navigation":{"speedOverGround":{"value":3.1}}}"""
        assertEquals(json, AskCarry.inflate(deflate(json.toByteArray())))
        val zeros = deflate(ByteArray(2 shl 20))
        assertTrue("two megabytes of zeros deflate small", zeros.size < 10_000)
        assertNull("but inflate past the cap", AskCarry.inflate(zeros))
        assertEquals(1000, AskCarry.inflate(deflate(ByteArray(1000) { 'a'.code.toByte() }), limit = 1000)!!.length)
        assertNull(AskCarry.inflate(byteArrayOf(1, 2, 3, 4)))
        val whole = deflate(json.toByteArray())
        assertNull("cut short", AskCarry.inflate(whole.copyOf(whole.size / 2)))
        assertNull(AskCarry.inflate(ByteArray(0)))
    }
}
