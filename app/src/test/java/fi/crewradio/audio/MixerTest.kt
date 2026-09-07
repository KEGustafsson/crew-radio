package fi.crewradio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Drives the mixer one slot at a time with a fake sink and a hand-turned clock: what the
 * worker thread does, minus the thread. Every frame here is a constant, so one sample of
 * the output says what the slot was made of.
 */
class MixerTest {

    private class FakePlayback : Playback {
        val written = ArrayList<ByteArray>()
        var starts = 0
        var stops = 0
        var result: Int? = null          // null: take the frame; else the code to return instead
        var underruns = 0
        override fun start() { starts++ }
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            written.add(data.copyOfRange(offset, offset + length))
            return result ?: length
        }
        override fun stop() { stops++ }
        override fun underrunCount(): Int = underruns
    }

    private var now = 1_000_000_000L
    private val ms = 1_000_000L
    private val playback = FakePlayback()
    private val status = ArrayList<String>()
    private val mixer = Mixer(playback) { now }.also { it.onStatus = { s -> status.add(s) }; it.open() }

    private fun frame(value: Int): ByteArray {
        val f = ByteArray(AudioConfig.FRAME_BYTES)
        for (i in 0 until AudioConfig.FRAME_SAMPLES) {
            f[2 * i] = (value and 0xFF).toByte()
            f[2 * i + 1] = (value shr 8).toByte()
        }
        return f
    }

    private fun push(sender: Int, value: Int) = mixer.push(sender, frame(value), 0, AudioConfig.FRAME_BYTES)

    /** Runs one slot, the clock moving on a frame, and returns the first sample of what was written. */
    private fun slot(): Int {
        mixer.tick(now)
        now += AudioConfig.FRAME_MS * ms
        val f = playback.written.last()
        return (f[1].toInt() shl 8) or (f[0].toInt() and 0xFF)
    }

    @Test
    fun aStreamWaitsForTwoFramesBeforeItPlays() {
        push(1, 1000)
        assertEquals(0, slot())
        push(1, 2000)
        assertEquals(1000, slot())
        assertEquals(2000, slot())
    }

    @Test
    fun aHoleIsConcealedFromTheLastFrameAndRealAudioResumesAfterIt() {
        push(1, 1000); push(1, 2000)
        mixer.conceal(1, 1)
        push(1, 3000)
        assertEquals(1000, slot())
        assertEquals(2000, slot())
        assertEquals(1200, slot())                       // 2000 * 0.6
        assertEquals(3000, slot())
        assertEquals(1L, mixer.concealedFrames.get())
    }

    @Test
    fun concealmentGivesUpAfterMaxFramesAndReservesNoMoreThanThat() {
        push(1, 1000); push(1, 2000)
        mixer.conceal(1, 10)
        assertEquals(1000, slot())
        assertEquals(2000, slot())
        assertEquals(1200, slot())
        assertEquals(720, slot())
        assertEquals(432, slot())
        assertEquals(0, slot())                          // the fourth missing slot is silence, not a stutter
        assertEquals(0, slot())
        assertEquals(Conceal.MAX_FRAMES.toLong(), mixer.concealedFrames.get())
    }

    @Test
    fun aQueueThatRunsDryMidTalkIsConcealedToo() {
        push(1, 1000); push(1, 2000)
        assertEquals(1000, slot())
        assertEquals(2000, slot())
        assertEquals(1200, slot())                       // 40 ms after the last packet: still talking, lost
        assertEquals(720, slot())
    }

    @Test
    fun dropsTheOldestBeyondTenQueued() {
        for (v in 1..12) push(1, v * 100)
        assertEquals(300, slot())
        assertEquals(400, slot())
    }

    @Test
    fun gainScalesTheSpeechAndNotTheCue() {
        mixer.gain = 0.5f
        push(1, 10000); push(1, 10000)
        mixer.cue(listOf(frame(4000)))
        assertEquals(9000, slot())                       // 10000 * 0.5 + 4000
        assertEquals(5000, slot())
        mixer.gain = 0f                                  // the user's mute
        push(1, 10000); push(1, 10000)
        mixer.cue(listOf(frame(4000)))
        assertEquals(4000, slot())                       // the beep still sounds
        assertEquals(0, slot())
    }

    @Test
    fun mutedOutputsSilenceOverEverything() {
        push(1, 10000); push(1, 10000)
        mixer.cue(listOf(frame(4000)))
        mixer.muted = true
        assertEquals(0, slot())
        assertTrue(playback.written.last().all { it.toInt() == 0 })
        mixer.muted = false
        assertEquals(10000, slot())                      // the queue kept draining meanwhile
    }

    @Test
    fun anIdleStreamIsSweptAfterASecond() {
        push(1, 1000); push(1, 2000)
        slot(); slot()
        assertEquals(1, mixer.streamCount())
        now += 1_100 * ms
        slot()
        assertEquals(0, mixer.streamCount())
    }

    @Test
    fun aStreamThatPausedPrefillsAgainBeforeItsNextBurst() {
        push(1, 1000); push(1, 2000)
        assertEquals(1000, slot())
        assertEquals(2000, slot())
        now += 200 * ms                                  // past the 150 ms that still counts as talking
        assertEquals(0, slot())
        push(1, 5000)
        assertEquals(0, slot())                          // one frame of the new phrase: wait for the second
        push(1, 6000)
        assertEquals(5000, slot())
        assertEquals(6000, slot())
        assertEquals(0L, mixer.concealedFrames.get())    // the pause was never mistaken for loss
    }

    @Test
    fun twoTalkersShareTheHeadroom() {
        push(1, 10000); push(1, 10000)
        assertEquals(10000, slot())                      // one talker: unity
        push(2, 10000); push(2, 10000)
        val two = slot()
        assertTrue("two talkers: $two", abs(two - 14142) <= 2)   // 20000 / sqrt(2)
    }

    @Test
    fun loudTalkersStillClipRatherThanWrap() {
        push(1, 30000); push(1, 30000)
        push(2, 30000); push(2, 30000)
        push(3, 30000); push(3, 30000)
        val v = slot()
        assertTrue("sum $v", v in 30000..32767)          // 90000 / sqrt(3) = 51962, clipped
    }

    @Test
    fun recreatesTheTrackAfterPersistentFailureWithBackoffAndReportsOnce() {
        playback.result = -6
        slot(); slot()
        assertEquals(1, playback.starts)                 // two refused writes are forgiven
        assertTrue(status.isEmpty())
        slot()
        assertEquals(2, playback.starts)                 // the third is persistent: recreated at once
        assertEquals(1, playback.stops)
        assertEquals(listOf("Playback failed (-6), restarting"), status)
        repeat(50) { slot() }                            // one second: within the first 2 s wait
        assertEquals(2, playback.starts)
        now += 1_100 * ms
        slot()
        assertEquals(3, playback.starts)
        assertEquals(1, status.size)                     // said once
        now += 2_100 * ms                                // the wait doubled: not yet
        slot()
        assertEquals(3, playback.starts)
        now += 2_000 * ms
        slot()
        assertEquals(4, playback.starts)
        playback.result = null
        slot()
        assertEquals(listOf("Playback failed (-6), restarting", "Playback restored"), status)
        playback.result = -6
        repeat(3) { slot() }
        assertEquals(5, playback.starts)                 // a new outage starts over: at once, and reported again
        assertEquals(3, status.size)
    }

    @Test
    fun aShortWriteCountsAsRefused() {
        playback.result = 0
        repeat(3) { slot() }
        assertEquals(listOf("Playback failed (0), restarting"), status)
    }

    @Test
    fun countsTheTrackUnderrunsAboutOnceASecond() {
        playback.underruns = 2
        repeat(49) { slot() }
        assertEquals(0L, mixer.underrunFrames.get())
        slot()
        assertEquals(2L, mixer.underrunFrames.get())
        playback.underruns = 5
        repeat(50) { slot() }
        assertEquals(5L, mixer.underrunFrames.get())
    }

    /**
     * A transport thread already inside onPacket can reach push() after the session ended. The
     * frame must not be waiting in the queue when the next session opens, or the first thing the
     * crew hears is a fragment of the last one.
     */
    @Test
    fun aFramePushedAfterStopIsNotPlayedByTheNextSession() {
        push(1, 1000)
        mixer.stop()
        push(1, 2000)                    // the late copy, from a thread that had already started
        assertEquals("a stopped mixer queues nothing", 0, mixer.streamCount())

        mixer.open()
        push(1, 3000); push(1, 4000)     // a full prefill of this session's own audio
        assertEquals("the new session starts with its own first frame", 3000, slot())
        assertEquals(4000, slot())
    }

    @Test
    fun concealIsIgnoredWhileStopped() {
        push(1, 1000); push(1, 2000)
        mixer.stop()
        mixer.conceal(1, 3)
        assertEquals(0, mixer.streamCount())
    }
}
