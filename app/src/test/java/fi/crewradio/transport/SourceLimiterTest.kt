package fi.crewradio.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-source ingress budget: what one host on the WLAN may spend before the engine's own
 * global budget - which is charged before the AEAD and so cannot tell a crew frame from a
 * stranger's - is reached at all.
 */
class SourceLimiterTest {

    @Test
    fun aSourceSpendsItsBurstAndThenWaits() {
        val l = SourceLimiter(perSecond = 100.0, burst = 10.0)
        repeat(10) { assertTrue(l.allow(1, 0)) }
        assertFalse(l.allow(1, 0))
        assertTrue(l.allow(1, 10))                       // 100/s: one token back after 10 ms
        assertFalse(l.allow(1, 10))
    }

    /** The point of the bucket: a flooding host spends its own budget, not everyone else's. */
    @Test
    fun oneFloodingSourceDoesNotSpendAnothersBudget() {
        val l = SourceLimiter(perSecond = 100.0, burst = 10.0)
        repeat(50) { l.allow(1, 0) }
        assertFalse(l.allow(1, 0))
        repeat(10) { assertTrue(l.allow(2, 0)) }         // the crew's phone is untouched
    }

    /**
     * A flood that rotates its source address must not be able to lock the crew out by filling
     * the table, so the eldest is evicted rather than the newcomer refused. That is the one thing
     * this bucket must not copy from the engine's sender table.
     */
    @Test
    fun aFloodOfAddressesEvictsRatherThanLocksOut() {
        val l = SourceLimiter(perSecond = 100.0, burst = 10.0, maxSources = 8)
        for (source in 100 until 200) assertTrue(l.allow(source, 0))
        repeat(10) { assertTrue(l.allow(1, 0)) }         // a phone arriving after the flood still gets its budget
    }

    @Test
    fun tokensDoNotAccumulatePastTheBurst() {
        val l = SourceLimiter(perSecond = 100.0, burst = 10.0)
        repeat(10) { assertTrue(l.allow(1, 60_000)) }    // a minute of silence is still only a burst
        assertFalse(l.allow(1, 60_000))
    }

    @Test
    fun clearForgetsEverySource() {
        val l = SourceLimiter(perSecond = 100.0, burst = 10.0)
        repeat(10) { l.allow(1, 0) }
        assertFalse(l.allow(1, 0))
        l.clear()
        assertTrue(l.allow(1, 0))
    }
}
