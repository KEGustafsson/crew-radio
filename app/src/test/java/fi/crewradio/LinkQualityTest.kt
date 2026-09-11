package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bars against the rules the class documents: hellos, audio, the overdue hello, and forgetting. */
class LinkQualityTest {

    private val quiet = 60_000L        // a node that has not talked lately

    private fun hellos(q: LinkQuality, n: Int, gapEvery: Int = 0) {
        for (i in 1..n) q.helloHeard(if (gapEvery > 0 && i % gapEvery == 0) 1 else 0)
    }

    @Test
    fun nothingHeardYetIsFourBars() {
        assertEquals(4, LinkQuality().level(0, quiet))
    }

    @Test
    fun everyHelloArrivingIsFourBars() {
        val q = LinkQuality()
        hellos(q, 10)
        assertEquals(4, q.level(900, quiet))
    }

    @Test
    fun missedHellosCostBarsOneByOne() {
        val q = LinkQuality()
        hellos(q, 9)
        q.helloHeard(1)                                  // one of ten missing
        assertEquals(3, q.level(0, quiet))
        q.helloHeard(1)                                  // two of the last ten
        assertEquals(2, q.level(0, quiet))
        q.helloHeard(1)
        assertEquals(1, q.level(0, quiet))
    }

    @Test
    fun aLateHelloCountsNothing() {
        val q = LinkQuality()
        hellos(q, 10)
        q.helloHeard(-1)
        assertEquals(4, q.level(0, quiet))
    }

    @Test
    fun oldLossesLeaveTheWindow() {
        val q = LinkQuality()
        q.helloHeard(3)                                  // a bad start
        assertEquals(1, q.level(0, quiet))
        hellos(q, 10)                                    // ten clean hellos push it out
        assertEquals(4, q.level(0, quiet))
    }

    @Test
    fun anOverdueHelloIsAMissingOne() {
        val q = LinkQuality()
        hellos(q, 10)
        assertEquals(4, q.level(1_500, quiet))
        assertEquals(3, q.level(1_600, quiet))           // one overdue
        assertEquals(2, q.level(2_600, quiet))           // two
        assertEquals(1, q.level(3_600, quiet))           // three: the roster drops it at four seconds
    }

    @Test
    fun aNodeNeverHeardFromDecaysToo() {
        assertEquals(1, LinkQuality().level(4_000, quiet))
    }

    @Test
    fun audioLossIsGradedByPercent() {
        fun talk(lostPer100: Int): Int {
            val q = LinkQuality()
            repeat(200) { i ->
                if (lostPer100 > 0 && i % (100 / lostPer100) == 0) q.audioLost(1)
                q.audioHeard()
            }
            return q.level(0, 0)
        }
        assertEquals(4, talk(0))
        assertEquals(4, talk(2))
        assertEquals(3, talk(5))
        assertEquals(2, talk(10))
        assertEquals(2, talk(25))                        // exactly 20 %, the last step's edge
        assertEquals(1, talk(50))                        // every other frame
    }

    @Test
    fun theFirstFramesOfATalkAreNoMeasure() {
        val q = LinkQuality()
        q.audioLost(10)
        repeat(20) { q.audioHeard() }                    // a third lost, but under AUDIO_MIN frames
        assertEquals(4, q.level(0, 0))
        repeat(30) { q.audioHeard() }
        assertEquals(2, q.level(0, 0))                   // 10 of 60, and now it counts
    }

    @Test
    fun aBadTransmissionIsForgottenOnceTheTalkerIsQuiet() {
        val q = LinkQuality()
        q.audioLost(50)
        repeat(100) { q.audioHeard() }
        assertEquals(1, q.level(0, 0))
        assertEquals(1, q.level(0, LinkQuality.AUDIO_MEMORY_MS))
        assertEquals(4, q.level(0, LinkQuality.AUDIO_MEMORY_MS + 1))
    }

    @Test
    fun aGapLargerThanTheWindowFillsIt() {
        val q = LinkQuality()
        repeat(250) { q.audioHeard() }
        q.audioLost(10_000)
        repeat(60) { q.audioHeard() }
        assertEquals(1, q.level(0, 0))                   // 190 lost of 250
    }

    @Test
    fun theWorseOfHellosAndAudioWins() {
        val q = LinkQuality()
        hellos(q, 10)
        repeat(100) { q.audioHeard() }
        assertEquals(4, q.level(0, 0))
        q.helloHeard(2)
        assertEquals(2, q.level(0, 0))
        q.audioLost(30); q.audioHeard()
        assertEquals(1, q.level(0, 0))
    }
}
