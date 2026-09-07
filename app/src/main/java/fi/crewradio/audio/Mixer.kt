package fi.crewradio.audio

import fi.crewradio.transport.Backoff
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Per-sender jitter queues summed into one PCM stream. Needed for full duplex,
 * where several peers can be talking at the same time; in half duplex it just
 * passes a single stream through with a little jitter protection.
 *
 * Pacing comes from [Playback.write] blocking: one 20 ms frame per loop,
 * silence if nobody is sending. Each loop is one [tick]; the worker thread only
 * repeats it, so the tests run the same code with a fake sink and their own clock.
 *
 * Loss: a slot the engine knows is missing (a gap in the sender's sequence, see
 * [conceal]) and a queue that runs dry while its sender is still talking are both
 * filled with [Conceal.frame] - the last good frame, fading - instead of a click of
 * silence. After [Conceal.MAX_FRAMES] of that the stream goes quiet until real audio returns,
 * and a stream that fell silent starts over with a fresh prefill when it speaks again.
 *
 * Headroom: with more than one talker the sum is scaled by 1/sqrt(n) (a Q15 table, no
 * float in the loop), which keeps the loudness of a crowd about right without clipping two
 * voices that happen to peak together. The cue tone is added after that, at its own level.
 *
 * A track that stops taking frames (ERROR_DEAD_OBJECT after an audio-server restart) is
 * recreated, at once and then on a 2 s doubling backoff, and reported once through [onStatus];
 * meanwhile the loop paces itself instead of spinning at audio priority.
 */
class Mixer(
    private val playback: Playback = AudioPlayback(),
    private val clock: () -> Long = System::nanoTime,
) {

    private class Stream(var lastSeen: Long) {
        val frames = ArrayDeque<ByteArray>()
        var primed = false
        var last: ByteArray? = null      // last real frame, what concealment repeats
        var concealed = 0                // consecutive concealed slots so far
    }

    private val streams = ConcurrentHashMap<Int, Stream>()
    private val cues = ArrayDeque<ByteArray>()              // tone frames, one per slot, on top of the streams
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Slots filled by concealment since [start]; shown on the Status screen. */
    val concealedFrames = AtomicLong()

    /**
     * Times the output track ran dry since [start] (deltas of [Playback.underrunCount], sampled
     * about once a second): each one is at least a frame of silence the mixer did not deliver in
     * time. A count that climbs on a phone means the two-frame track needs a bigger prefill.
     */
    val underrunFrames = AtomicLong()

    /** Told once when the track stops taking audio and once when it is back; shown on the status line. */
    @Volatile var onStatus: ((String) -> Unit)? = null

    /** Output silence (queues keep draining) - the channel is on hold behind a phone call. */
    @Volatile var muted = false

    /**
     * Gain on the summed speech, 0..1: the user's mute is 0 (the level itself is the phone's call
     * volume, see [CallVolume]). Cue tones are added after it, so a muted phone still hears its
     * own key beeps.
     */
    @Volatile var gain = 1f
        set(value) { field = value.coerceIn(0f, 1f); gainQ15 = (field * 32768f).toInt() }
    @Volatile private var gainQ15 = 32768

    private val prefillFrames = 2     // 40 ms before a new stream starts draining
    private val maxQueuedFrames = 10  // 200 ms cap; drop oldest beyond this
    private val idleTimeoutNs = 1_000_000_000L
    private val stillTalkingNs = 150_000_000L   // an empty queue this soon after a frame is loss, not the end

    // Worker-thread state: the slot buffers (allocated once, the loop allocates nothing), the
    // underrun sampling and the track-failure bookkeeping. Touched by tick() only.
    private val acc = IntArray(AudioConfig.FRAME_SAMPLES)
    private val out = ByteArray(AudioConfig.FRAME_BYTES)
    private var loops = 0
    private var lastUnderruns = 0
    private var failedWrites = 0
    private var failing = false
    private var retryAtNs = 0L
    private val backoff = Backoff(RETRY_FIRST_MS, RETRY_MAX_MS)

    fun start() {
        if (running) return
        open()
        worker = thread(name = "ptt-mixer") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) if (!tick(clock())) pace()
        }
    }

    /** What [start] does short of the thread: counters, queues and the track. The tests drive [tick] themselves. */
    internal fun open() {
        running = true
        concealedFrames.set(0)
        underrunFrames.set(0)
        loops = 0
        lastUnderruns = 0
        failedWrites = 0
        failing = false
        retryAtNs = 0L
        backoff.reset()
        synchronized(cues) { cues.clear() }
        playback.start()
    }

    /**
     * One 20 ms slot: sums what every stream contributes, scales it by [gain] and the headroom
     * for that many talkers, adds the cue, writes the frame. Returns false when the track did not
     * take it, so the caller paces itself instead of spinning. One thread at a time.
     */
    internal fun tick(now: Long): Boolean {
        acc.fill(0)
        var active = 0
        for ((id, st) in streams) {
            val frame = synchronized(st) { nextFrame(st, now) }
            if (frame != null) {
                active++
                var i = 0
                while (i < AudioConfig.FRAME_SAMPLES) {
                    val lo = frame[2 * i].toInt() and 0xFF
                    val hi = frame[2 * i + 1].toInt()
                    acc[i] += (hi shl 8) or lo
                    i++
                }
            } else if (now - st.lastSeen > idleTimeoutNs) {
                streams.remove(id)
            }
        }
        // The user's volume and the crowd headroom scale the speech only; the cue is the phone's
        // own voice, added at full level.
        if (active > 0) {
            val g = ((gainQ15.toLong() * HEADROOM_Q15[minOf(active, HEADROOM_Q15.size - 1)]) shr 15).toInt()
            if (g != 32768) for (i in 0 until AudioConfig.FRAME_SAMPLES) acc[i] = ((acc[i].toLong() * g) shr 15).toInt()
        }
        val cue = synchronized(cues) { cues.pollFirst() }
        if (cue != null) {
            active++
            for (i in 0 until AudioConfig.FRAME_SAMPLES) {
                acc[i] += (cue[2 * i + 1].toInt() shl 8) or (cue[2 * i].toInt() and 0xFF)
            }
        }
        if (active == 0 || muted) {
            out.fill(0)
        } else {
            for (i in 0 until AudioConfig.FRAME_SAMPLES) {
                val v = acc[i].coerceIn(-32768, 32767)
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = (v shr 8).toByte()
            }
        }
        val n = playback.write(out, 0, out.size)
        if (n != out.size) {
            failed(n, now)
            return false
        }
        if (failedWrites > 0) recovered()
        if (++loops % UNDERRUN_SAMPLE_LOOPS == 0) sampleUnderruns()
        return true
    }

    /** The frame a stream contributes to this slot: real audio, a concealed repeat, or nothing. Call holding the stream lock. */
    private fun nextFrame(st: Stream, now: Long): ByteArray? {
        if (!st.primed && st.frames.size >= prefillFrames) st.primed = true
        if (!st.primed) return null
        val f = st.frames.pollFirst()
        if (f != null && f !== HOLE) {
            st.last = f
            st.concealed = 0
            return f
        }
        // A known hole, or a queue that ran dry while the sender is still talking.
        if (f == null && now - st.lastSeen > stillTalkingNs) { rest(st); return null }
        val fill = Conceal.frame(st.last, st.concealed + 1)
        if (fill == null) {
            if (f == null) rest(st)      // dry past what concealment covers: the same as a pause
            return null
        }
        st.concealed++
        concealedFrames.incrementAndGet()
        return fill
    }

    /** The stream's talk burst is over: the next one prefills again, and has nothing old to repeat. */
    private fun rest(st: Stream) {
        st.primed = false
        st.concealed = 0
        st.last = null
    }

    /** The platform's underrun counter, read about once a second; a recreated track starts it over. */
    private fun sampleUnderruns() {
        val count = playback.underrunCount()
        if (count > lastUnderruns) underrunFrames.addAndGet((count - lastUnderruns).toLong())
        lastUnderruns = count
    }

    /**
     * The track refused a frame. A single refusal is forgiven; from [PERSISTENT_WRITES] on the
     * track is taken for dead, said so once, and recreated now and then on the backoff until
     * a write goes through again. A recreation that throws is left to the next attempt: the
     * status line already says playback is down.
     */
    private fun failed(code: Int, now: Long) {
        failedWrites++
        if (failedWrites < PERSISTENT_WRITES) return
        if (!failing) {
            failing = true
            onStatus?.invoke("Playback failed ($code), restarting")
        }
        if (now < retryAtNs) return
        retryAtNs = now + backoff.next() * 1_000_000L
        try { playback.stop() } catch (_: Exception) {}
        try {
            playback.start()
            lastUnderruns = 0
        } catch (_: Exception) {}
    }

    private fun recovered() {
        failedWrites = 0
        if (failing) {
            failing = false
            backoff.reset()
            retryAtNs = 0L
            onStatus?.invoke("Playback restored")
        }
    }

    /** Sleeps a slot's worth while the track is down, so the queues drain at the usual pace and the thread does not spin. */
    private fun pace() {
        try { Thread.sleep(AudioConfig.FRAME_MS.toLong()) } catch (_: InterruptedException) {}
    }

    fun push(senderId: Int, data: ByteArray, offset: Int, length: Int) {
        if (length != AudioConfig.FRAME_BYTES) return
        val now = clock()
        val st = streams.getOrPut(senderId) { Stream(now) }
        synchronized(st) {
            st.lastSeen = now
            st.frames.addLast(data.copyOfRange(offset, offset + length))
            while (st.frames.size > maxQueuedFrames) st.frames.pollFirst()
        }
    }

    /** Reserves [count] slots for frames the engine knows were lost, so timing holds and each is concealed in turn. */
    fun conceal(senderId: Int, count: Int) {
        val st = streams[senderId] ?: return          // nothing heard from them yet: nothing to repeat either
        synchronized(st) {
            repeat(count.coerceAtMost(Conceal.MAX_FRAMES)) { st.frames.addLast(HOLE) }
            while (st.frames.size > maxQueuedFrames) st.frames.pollFirst()
        }
    }

    /** Plays [frames] (see [Tones]) from the next slot on, over whatever else is sounding. */
    fun cue(frames: List<ByteArray>) {
        synchronized(cues) {
            if (!running) return                                  // checked under the lock: stop() clears under it too
            for (f in frames) if (f.size == AudioConfig.FRAME_BYTES) cues.addLast(f)
        }
    }

    /** Streams currently held, talking or not yet swept; for the tests. */
    internal fun streamCount(): Int = streams.size

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        streams.clear()
        synchronized(cues) { cues.clear() }
        playback.stop()
    }

    private companion object {
        /** Queue marker for a slot whose packet never came. */
        val HOLE = ByteArray(0)

        /** Consecutive refused writes before the track counts as dead: 60 ms, not one hiccup. */
        const val PERSISTENT_WRITES = 3
        const val RETRY_FIRST_MS = 2_000L
        const val RETRY_MAX_MS = 15_000L
        /** Loops between reads of the underrun counter: about a second. */
        const val UNDERRUN_SAMPLE_LOOPS = 50

        /** 1/sqrt(n) in Q15 for n talkers at once, 0 and 1 unity; more than the last entry share its scale. */
        val HEADROOM_Q15 = IntArray(17) { n -> if (n <= 1) 32768 else (32768.0 / sqrt(n.toDouble())).roundToInt() }
    }
}
