package fi.crewradio

/**
 * How well one crew member's packets reach this phone, as the four bars on the roster.
 *
 * Android reports no signal level for a connected Bluetooth Classic link and at best a distance
 * for Wi-Fi Aware, so the level is measured where it matters, on the packets. Every node numbers
 * its hellos (one a second) and a talker numbers its audio frames, so a gap in either sequence is a
 * packet that did not arrive, whatever it travelled over and however many relays it crossed; the
 * copies a phone hears twice have been dropped by [Ingress] before they get here, so a frame counts
 * once. Two windows: the last [HELLO_WINDOW] hellos, and the last [AUDIO_WINDOW] audio frames
 * (five seconds of talk). The level is the worse of the two, in bars 1 to [BARS]:
 *
 *  - hellos: none missing is 4, one in ten 3, two 2, three or more 1;
 *  - audio: up to 2 % of frames lost is 4, up to 8 % 3, up to 20 % 2, worse 1. The mixer conceals
 *    a lost frame with a fade, so a few percent is audible as voids and 20 % is broken speech.
 *
 * The gap before the first hello or the first audio frame an entry hears is not loss: the sender
 * numbered those packets while this phone was off the channel or the sender was out of the roster,
 * and an entry is fresh in both cases (the engine clears the roster on leave and drops a node after
 * four seconds of silence). Counting it made a good link climb from one bar to four after a rejoin.
 *
 * A hello that is overdue right now counts as missing too (one every second past a grace of
 * [HELLO_GRACE_MS]), so a node that has gone quiet loses a bar a second until the roster drops it
 * at four seconds. The audio window is set aside once a talker has been silent for
 * [AUDIO_MEMORY_MS], so one bad transmission does not mark a node for the rest of the day, and
 * emptied when the talker next speaks after such a silence, so it does not come back either; it
 * says nothing until [AUDIO_MIN] frames are in it: a talker's first few frames are no measure.
 *
 * Pure. The engine feeds it from several transport threads at once, hence synchronized.
 */
class LinkQuality {
    private val hellos = LossWindow(HELLO_WINDOW)
    private val audio = LossWindow(AUDIO_WINDOW)

    /**
     * A hello arrived with [gap] missing before it; a late one (negative gap) counts nothing, and
     * neither does the gap before the first hello this entry hears: the sequence numbers live for
     * the sender's process, so after this phone rejoins the channel, or the sender comes back
     * from being dropped, that gap is every hello sent while nobody was listening, and charging
     * it would show a link climbing from one bar for the ten seconds it takes to push it out.
     */
    @Synchronized
    fun helloHeard(gap: Int) {
        if (gap < 0) return
        if (hellos.total > 0) hellos.lost(gap)
        hellos.heard()
    }

    /**
     * [n] audio frames were missing before the one being admitted at [nowMs]; the frame itself is
     * [audioHeard]. A talker that has been quiet for [AUDIO_MEMORY_MS] starts with an empty window,
     * so the losses of an earlier transmission do not colour the first second of the next one, and
     * the gap before a window's first frame is history, not loss, as with the hellos: for a fresh
     * entry it is whatever was sent while nobody listened, and after a silence it is the tail of
     * the transmission the window was just emptied of.
     */
    @Synchronized
    fun audioLost(n: Int, nowMs: Long) {
        freshen(nowMs)                                   // first, so a gap after a silence lands in the new window's rules
        if (audio.total > 0) audio.lost(n)               // a gap before a window's first frame is history, not loss
    }

    @Synchronized
    fun audioHeard(nowMs: Long) {
        freshen(nowMs)
        audio.heard()
    }

    private fun freshen(nowMs: Long) {
        if (lastAudioMs != NEVER && nowMs - lastAudioMs > AUDIO_MEMORY_MS) audio.clear()
        lastAudioMs = nowMs
    }

    private var lastAudioMs = NEVER

    /**
     * The bars right now, 1 to [BARS]. [sinceHelloMs] is how long ago the last hello (or, before
     * any, the last packet) arrived; [sinceAudioMs] how long ago the last audio frame did, or any
     * large number for a node that has not talked.
     */
    @Synchronized
    fun level(sinceHelloMs: Long, sinceAudioMs: Long): Int {
        val overdue = if (sinceHelloMs <= HELLO_GRACE_MS) 0
        else ((sinceHelloMs - HELLO_GRACE_MS) / 1000 + 1).toInt().coerceAtMost(HELLO_WINDOW)
        val helloTotal = hellos.total + overdue
        val helloLevel = if (helloTotal == 0) BARS
        else bars((hellos.lostCount + overdue).toDouble() / helloTotal, HELLO_STEPS)
        val audioLevel = if (audio.total < AUDIO_MIN || sinceAudioMs > AUDIO_MEMORY_MS) BARS
        else bars(audio.lostCount.toDouble() / audio.total, AUDIO_STEPS)
        return minOf(helloLevel, audioLevel)
    }

    private fun bars(loss: Double, steps: DoubleArray): Int {
        var level = BARS
        for (step in steps) if (loss > step) level-- else break
        return level.coerceAtLeast(1)
    }

    /** The last [size] packets of one kind, each heard or lost, with the counts kept alongside. */
    private class LossWindow(private val size: Int) {
        private val ring = BooleanArray(size)          // true = lost
        private var next = 0
        var total = 0; private set
        var lostCount = 0; private set

        fun heard() = push(false)
        fun lost(n: Int) { repeat(n.coerceIn(0, size)) { push(true) } }
        fun clear() { ring.fill(false); next = 0; total = 0; lostCount = 0 }

        private fun push(lost: Boolean) {
            if (total == size && ring[next]) lostCount--
            ring[next] = lost
            if (lost) lostCount++
            next = (next + 1) % size
            if (total < size) total++
        }
    }

    companion object {
        const val BARS = 4

        /**
         * A Wi-Fi RSSI in dBm as bars, 0 to [BARS], on the thresholds Android's own
         * `WifiManager.calculateSignalLevel` defaults to (`config_wifiRssiLevelThresholds`:
         * -88, -77, -66, -55). For the one API level, 29, where the platform offers no
         * un-deprecated way to ask it.
         */
        fun wifiBars(rssi: Int): Int = WIFI_THRESHOLDS.count { rssi >= it }
        private val WIFI_THRESHOLDS = intArrayOf(-88, -77, -66, -55)
        /** At or below this the link is shown as breaking up. */
        const val WEAK = 1
        const val HELLO_WINDOW = 10
        /** Five seconds of 20 ms frames. */
        const val AUDIO_WINDOW = 250
        /** One second of talk before the audio window is believed. */
        const val AUDIO_MIN = 50
        const val HELLO_GRACE_MS = 1_500L
        const val AUDIO_MEMORY_MS = 10_000L
        private const val NEVER = Long.MIN_VALUE
        /** Loss above each step costs a bar: hellos, then audio. */
        private val HELLO_STEPS = doubleArrayOf(0.0, 0.1, 0.2)
        private val AUDIO_STEPS = doubleArrayOf(0.02, 0.08, 0.2)
    }
}
