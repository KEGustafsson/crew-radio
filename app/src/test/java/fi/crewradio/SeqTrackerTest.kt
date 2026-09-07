package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SeqTrackerTest {

    @Test
    fun inOrderPacketsHaveNoGap() {
        val t = SeqTracker()
        assertEquals(0, t.admit(1, 10))
        assertEquals(0, t.admit(1, 11))
        assertEquals(0, t.admit(1, 12))
    }

    @Test
    fun missingNumbersAreTheGap() {
        val t = SeqTracker()
        t.admit(1, 10)
        assertEquals(2, t.admit(1, 13))
        assertEquals(0, t.admit(1, 14))
    }

    @Test
    fun lateAndDuplicateAreRejectedWithoutMovingBack() {
        val t = SeqTracker()
        t.admit(1, 10)
        assertEquals(-1, t.admit(1, 9))
        assertEquals(-1, t.admit(1, 10))
        assertEquals(0, t.admit(1, 11))      // the late one did not disturb the high-water mark
    }

    @Test
    fun sendersAreIndependent() {
        val t = SeqTracker()
        t.admit(1, 100)
        assertEquals(0, t.admit(2, 5))
        assertEquals(0, t.admit(1, 101))
    }

    @Test
    fun counterWrapIsADistanceOfOne() {
        val t = SeqTracker()
        t.admit(1, Int.MAX_VALUE - 1)
        assertEquals(0, t.admit(1, Int.MAX_VALUE))
        assertEquals(0, t.admit(1, Int.MIN_VALUE))
        assertEquals(1, t.admit(1, Int.MIN_VALUE + 2))
        assertEquals(-1, t.admit(1, Int.MAX_VALUE))   // now late
    }

    /** A sender that went quiet, or one we stopped listening to, is still held to its mark: its old packets stay late. */
    @Test
    fun aQuietSenderIsNotForgotten() {
        val t = SeqTracker()
        t.admit(1, 50)
        for (s in 0 until 200) t.admit(2, s)          // a lot of traffic from someone else meanwhile
        assertEquals(-1, t.admit(1, 50))              // a recording of its last packet
        assertEquals(-1, t.admit(1, 10))
        assertEquals(0, t.admit(1, 51))
    }

    /** Only when more senders than the capacity have been heard is the least recently heard one let go. */
    @Test
    fun onlyTheLeastRecentlyHeardSenderIsEvicted() {
        val t = SeqTracker(capacity = 3)
        t.admit(1, 50)
        t.admit(2, 50)
        t.admit(3, 50)
        t.admit(1, 51)                                // sender 1 heard again: 2 is now the oldest
        t.admit(4, 50)                                // a fourth sender pushes 2 out
        assertEquals(-1, t.admit(1, 49))              // remembered
        assertEquals(-1, t.admit(3, 49))              // remembered
        assertEquals(0, t.admit(2, 10))               // forgotten: starts afresh
        assertEquals(SeqTracker.DEFAULT_CAPACITY, 256)
    }

    /**
     * Several transport threads delivering one sender's consecutive packets: a slot is only
     * ever reported missing if the packet for it then arrives late (or never), so the gaps
     * can never outnumber the late packets. A racy read-compare-write would let two threads
     * both see the same previous value and invent a gap with no late packet to match.
     */
    @Test
    fun concurrentDeliveryNeverInventsAGap() {
        val t = SeqTracker()
        val next = AtomicInteger(0)
        val gaps = AtomicInteger()
        val late = AtomicInteger()
        val start = CountDownLatch(1)
        val total = 200_000
        val threads = (1..4).map {
            thread {
                start.await()
                while (true) {
                    val s = next.getAndIncrement()
                    if (s >= total) break
                    val g = t.admit(7, s)
                    if (g < 0) late.incrementAndGet() else gaps.addAndGet(g)
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue("gaps ${gaps.get()} > late ${late.get()}", gaps.get() <= late.get())
        assertEquals(-1, t.admit(7, total - 1))      // high-water mark is the last packet
        assertEquals(0, t.admit(7, total))
    }
}
