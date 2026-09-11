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
    fun theFirstHelloOfAnEntryCarriesNoHistory() {
        val q = LinkQuality()
        q.helloHeard(57)                                 // a minute of hellos sent while this phone was off the channel
        assertEquals(4, q.level(0, quiet))
        q.helloHeard(0)
        assertEquals(4, q.level(0, quiet))
        q.helloHeard(3)                                  // from the second hello on a gap is loss
        assertEquals(1, q.level(0, quiet))
    }

    @Test
    fun theFirstAudioFrameOfAnEntryCarriesNoHistory() {
        val q = LinkQuality()
        q.audioLost(3_000, 0)                            // a talk this phone was not on the channel for
        repeat(60) { q.audioHeard(0) }
        assertEquals(4, q.level(0, 0))
        q.audioLost(30, 0); q.audioHeard(0)              // from then on a gap is loss: 30 of 91
        assertEquals(1, q.level(0, 0))
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
        q.helloHeard(0)
        q.helloHeard(3)                                  // a bad start: three of the first five
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
                if (lostPer100 > 0 && i % (100 / lostPer100) == 0) q.audioLost(1, 0)
                q.audioHeard(0)
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
        q.audioHeard(0)
        q.audioLost(10, 0)
        repeat(20) { q.audioHeard(0) }                    // a third lost, but under AUDIO_MIN frames
        assertEquals(4, q.level(0, 0))
        repeat(30) { q.audioHeard(0) }
        assertEquals(2, q.level(0, 0))                   // 10 of 61, and now it counts
    }

    @Test
    fun aBadTransmissionIsForgottenOnceTheTalkerIsQuiet() {
        val q = LinkQuality()
        q.audioHeard(0)
        q.audioLost(50, 0)
        repeat(100) { q.audioHeard(0) }
        assertEquals(1, q.level(0, 0))
        assertEquals(1, q.level(0, LinkQuality.AUDIO_MEMORY_MS))
        assertEquals(4, q.level(0, LinkQuality.AUDIO_MEMORY_MS + 1))
    }

    @Test
    fun aTalkerBackAfterASilenceStartsClean() {
        val q = LinkQuality()
        q.audioHeard(0)
        q.audioLost(50, 0)
        repeat(100) { q.audioHeard(0) }
        assertEquals(1, q.level(0, 0))
        val later = LinkQuality.AUDIO_MEMORY_MS + 1
        repeat(60) { q.audioHeard(later) }               // the next transmission, after the memory ran out
        assertEquals(4, q.level(0, 0))                   // the old losses are gone, not merely waiting
        q.audioLost(1, later); q.audioHeard(later)
        assertEquals(4, q.level(0, 0))                   // 1 of 62
    }

    @Test
    fun aGapOnTheFirstFrameAfterASilenceIsNotLoss() {
        val q = LinkQuality()
        repeat(100) { q.audioHeard(0) }
        assertEquals(4, q.level(0, 0))
        val later = LinkQuality.AUDIO_MEMORY_MS + 1
        q.audioLost(200, later)                          // the gap arrives before the first frame of the new talk
        repeat(60) { q.audioHeard(later) }
        assertEquals(4, q.level(0, 0))                   // the window was emptied first, so the gap is history
        q.audioLost(30, later); q.audioHeard(later)      // and from then on a gap counts: 30 of 91
        assertEquals(1, q.level(0, 0))
    }

    @Test
    fun aGapLargerThanTheWindowFillsIt() {
        val q = LinkQuality()
        repeat(250) { q.audioHeard(0) }
        q.audioLost(10_000, 0)
        repeat(60) { q.audioHeard(0) }
        assertEquals(1, q.level(0, 0))                   // 190 lost of 250
    }

    @Test
    fun wifiBarsFollowThePlatformDefaults() {
        assertEquals(0, LinkQuality.wifiBars(-95))
        assertEquals(1, LinkQuality.wifiBars(-88))
        assertEquals(1, LinkQuality.wifiBars(-80))
        assertEquals(2, LinkQuality.wifiBars(-77))
        assertEquals(3, LinkQuality.wifiBars(-60))
        assertEquals(4, LinkQuality.wifiBars(-55))
        assertEquals(4, LinkQuality.wifiBars(-30))
    }

    @Test
    fun theWorseOfHellosAndAudioWins() {
        val q = LinkQuality()
        hellos(q, 10)
        repeat(100) { q.audioHeard(0) }
        assertEquals(4, q.level(0, 0))
        q.helloHeard(2)
        assertEquals(2, q.level(0, 0))
        q.audioLost(30, 0); q.audioHeard(0)
        assertEquals(1, q.level(0, 0))
    }
}
