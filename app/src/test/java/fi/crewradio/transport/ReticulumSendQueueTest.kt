package fi.crewradio.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReticulumSendQueueTest {
    private var now = 0L
    private fun queue(capacity: Int = ReticulumSendQueue.CAPACITY) = ReticulumSendQueue(capacity = capacity) { now }
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun everythingWaitingGoesOutAsOneBufferInOrder() {
        val q = queue()
        q.offer(listOf(b(1), b(2)), voice = true)            // one frame, two links
        q.offer(listOf(b(3)), voice = false)                 // a hello
        assertArrayEquals(b(1, 2, 3), q.take())
        assertEquals(0, q.size)
    }

    @Test
    fun voiceOlderThanTheMaxAgeIsDroppedAndCounted() {
        val q = queue()
        q.offer(listOf(b(1)), voice = true)
        q.offer(listOf(b(2)), voice = false)
        now = ReticulumSendQueue.VOICE_MAX_AGE_MS
        q.offer(listOf(b(3)), voice = true)
        now = ReticulumSendQueue.VOICE_MAX_AGE_MS + 1
        assertArrayEquals("frame 1 is too late; the hello is never dropped by age", b(2, 3), q.take())
        assertEquals(1L, q.aged)
    }

    @Test
    fun aBatchOfOnlyLateVoiceIsSkippedAndTheWriterWaitsForMore() {
        val q = queue()
        q.offer(listOf(b(1)), voice = true)
        now = 1_000
        val t = Thread { Thread.sleep(50); q.offer(listOf(b(9)), voice = true) }
        t.start()
        assertArrayEquals(b(9), q.take())
        t.join()
        assertEquals(1L, q.aged)
    }

    @Test
    fun aBacklogNeverRefusesAnOfferOrCountsAsStalled() {
        val q = queue()
        for (i in 0 until 100_000) {
            now = i * 20L                                    // half an hour of frames nobody writes
            assertTrue(q.offer(listOf(b(i and 0x7F)), voice = true))
        }
        assertFalse("nothing was handed to the socket: no stall, no redial", q.stalled())
    }

    @Test
    fun aSocketThatAcceptsNothingForTenSecondsIsStalled() {
        val q = queue()
        q.offer(listOf(b(1)), voice = false)
        assertArrayEquals(b(1), q.take())                    // handed to the socket at t = 0
        now = ReticulumSendQueue.NO_PROGRESS_MS - 1
        assertFalse(q.stalled())
        now = ReticulumSendQueue.NO_PROGRESS_MS
        assertTrue(q.stalled())
        q.written()
        assertFalse("progress after all", q.stalled())
    }

    @Test
    fun progressRestartsTheNoProgressClock() {
        val q = queue()
        q.offer(listOf(b(1)), voice = false)
        q.take(); now = 9_000; q.written()
        q.offer(listOf(b(2)), voice = false)
        q.take()
        now = 9_000 + ReticulumSendQueue.NO_PROGRESS_MS - 1
        assertFalse(q.stalled())
    }

    @Test
    fun aFullQueueDropsTheOldestVoiceFirst() {
        val q = queue(capacity = 3)
        q.offer(listOf(b(1)), voice = false)
        q.offer(listOf(b(2)), voice = true)
        q.offer(listOf(b(3)), voice = true)
        q.offer(listOf(b(4)), voice = false)
        assertEquals(1L, q.dropped)
        assertArrayEquals(b(1, 3, 4), q.take())
        val c = queue(capacity = 2)
        c.offer(listOf(b(1)), voice = false); c.offer(listOf(b(2)), voice = false); c.offer(listOf(b(3)), voice = false)
        assertArrayEquals("no voice to drop: the oldest goes", b(2, 3), c.take())
    }

    @Test
    fun closeEndsTheQueueForBothSides() {
        val q = queue()
        q.offer(listOf(b(1)), voice = true)
        q.close()
        assertNull(q.take())
        assertFalse(q.offer(listOf(b(2)), voice = true))
    }
}
