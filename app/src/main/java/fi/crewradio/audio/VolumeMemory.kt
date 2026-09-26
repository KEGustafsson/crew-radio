package fi.crewradio.audio

import kotlin.math.roundToInt

/**
 * The call volume the crew chose: one level, whatever the stream plays on.
 *
 * Android keeps a stream's volume per output device: the voice-call stream has one level for the
 * earpiece, another for the loudspeaker, another for each headset. Off channel the stream sits on
 * the earpiece and on channel on the loudspeaker, so the slider jumped between the two numbers
 * every time the channel was joined or left. Until Android 13 a Bluetooth headset is a stream of
 * its own besides, in steps of its own (0-15 against the voice call's 1-7, say).
 *
 * So this holds the crew's last choice (with the slider, a headset's buttons or the phone's own
 * volume panel) together with the range of the stream it was made on, and every stream shows and
 * gets that choice in its own steps: the same share of its maximum, clamped to its range. Only a
 * choice moves the anchor, never a conversion, so going back and forth between two streams does
 * not drift. The first stream seen, before any choice, gives the level it has then.
 *
 * Pure and synchronised: the slider, the volume broadcasts and the route's restore reach it from
 * different places.
 */
class VolumeMemory {
    private class Choice(val stream: Int, val level: Int, val max: Int)

    private var choice: Choice? = null

    /** The level to show for [stream] (range [min]..[max]); [current] becomes the choice when none is held yet. */
    @Synchronized
    fun level(stream: Int, current: Int, min: Int, max: Int): Int {
        val c = choice ?: Choice(stream, current, max).also { choice = it }
        if (c.stream == stream) return c.level.coerceIn(min, max)
        return convert(c.level, c.max, min, max)
    }

    /** The crew set [level] on [stream], whose maximum is [max]. */
    @Synchronized
    fun chose(stream: Int, level: Int, max: Int) { choice = Choice(stream, level, max) }

    /**
     * A volume-changed broadcast: a real change ([previous] differs from [value], both present) is
     * the crew's choice, from wherever it came, but only while the stream still reads that [value]
     * ([current]). Broadcasts arrive late: one from a restore or an earlier slider step can land
     * after the crew set a newer level, and taking it would hold the older one. Returns true when
     * the level was taken.
     */
    @Synchronized
    fun changed(stream: Int, previous: Int, value: Int, current: Int, max: Int): Boolean {
        if (previous < 0 || value < 0 || previous == value || value != current) return false
        choice = Choice(stream, value, max)
        return true
    }

    /** The level [stream] must be set to now that it reads [current], or null when it already has it. */
    @Synchronized
    fun restore(stream: Int, current: Int, min: Int, max: Int): Int? = level(stream, current, min, max).takeIf { it != current }

    companion object {
        /**
         * [level] out of [fromMax] as the same share of [max], within [min]..[max]. A share of the
         * maximum rather than of the range, so the voice call's lowest step (1 of 7) never becomes a
         * silent 0 on a stream that allows one.
         */
        fun convert(level: Int, fromMax: Int, min: Int, max: Int): Int {
            if (fromMax <= 0 || fromMax == max) return level.coerceIn(min, max)
            return (level.toDouble() * max / fromMax).roundToInt().coerceIn(min, max)
        }
    }
}
