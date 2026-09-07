package fi.crewradio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendQueueTest {

    private fun frame(n: Int) = byteArrayOf(n.toByte())
    private fun numbers(q: SendQueue): List<Int> = generateSequence { q.poll()?.get(0)?.toInt() }.toList()

    @Test
    fun holdsUpToTheBoundAndDropsTheOldest() {
        val q = SendQueue(capacity = 16, stuckMs = 3_000) { 0L }
        for (n in 1..20) assertTrue(q.offer(frame(n)))
        assertEquals(16, q.size)
        assertEquals(4L, q.dropped)
        assertEquals((5..20).toList(), numbers(q))          // the four oldest went, the order is kept
    }

    @Test
    fun aQueueThatStaysFullForTheStuckTimeReportsIt() {
        var now = 0L
        val q = SendQueue(capacity = 4, stuckMs = 3_000) { now }
        repeat(4) { assertTrue(q.offer(frame(it))) }        // full, nothing dropped yet
        assertTrue(q.offer(frame(4)))                        // t = 0: first drop starts the stuck clock
        now = 2_999
        assertTrue(q.offer(frame(5)))                        // still within the stuck time
        now = 3_000
        assertFalse(q.offer(frame(6)))                       // nothing taken for 3 s: the link is dead
        assertEquals(4, q.size)
        assertEquals(2L, q.dropped)
    }

    @Test
    fun aTakeResetsTheStuckClock() {
        var now = 0L
        val q = SendQueue(capacity = 2, stuckMs = 3_000) { now }
        q.offer(frame(0)); q.offer(frame(1)); q.offer(frame(2))   // full since t = 0
        now = 2_000
        assertEquals(1, q.take()!![0].toInt())               // the writer is alive after all
        now = 4_000
        assertTrue(q.offer(frame(3)))                        // not full when offered: fine
        assertTrue(q.offer(frame(4)))                        // full again, clock restarts at 4 000
        now = 6_999
        assertTrue(q.offer(frame(5)))
        now = 7_000
        assertFalse(q.offer(frame(6)))
    }

    @Test
    fun closeEndsTheQueueForBothSides() {
        val q = SendQueue(capacity = 4, stuckMs = 3_000) { 0L }
        q.offer(frame(1))
        q.close()
        assertTrue(q.isClosed)
        assertNull(q.take())                                 // a blocked writer wakes up with nothing
        assertNull(q.poll())
        assertFalse(q.offer(frame(2)))
    }

    @Test
    fun takeWaitsForAnOfferFromAnotherThread() {
        val q = SendQueue()
        val t = Thread { Thread.sleep(50); q.offer(frame(7)) }
        t.start()
        assertEquals(7, q.take()!![0].toInt())
        t.join()
    }
}
