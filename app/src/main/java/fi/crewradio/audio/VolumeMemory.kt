package fi.crewradio.audio

/**
 * The call volume the crew chose, one level per stream, whatever the stream plays on.
 *
 * Android keeps a stream's volume per output device: the voice-call stream has one level for the
 * earpiece, another for the loudspeaker, another for each headset. Off channel the stream sits on
 * the earpiece and on channel on the loudspeaker, so the slider jumped between the two numbers
 * every time the channel was joined or left. This holds the level the crew last chose (with the
 * slider, a headset's buttons or the phone's own volume panel) and says what to put back when the
 * stream lands on another device. A stream seen for the first time takes the level it has then.
 *
 * Pure and synchronised: the slider, the volume broadcasts and the route's restore reach it from
 * different places.
 */
class VolumeMemory {
    private val levels = HashMap<Int, Int>()

    /** The level to show for [stream]; [current] (the device's own) becomes it when none is held yet. */
    @Synchronized
    fun level(stream: Int, current: Int): Int = levels.getOrPut(stream) { current }

    /** The crew set [level] on [stream] here. */
    @Synchronized
    fun chose(stream: Int, level: Int) { levels[stream] = level }

    /**
     * A volume-changed broadcast: a real change ([previous] differs from [value], both present) is
     * the crew's choice, from wherever it came. The one a restore itself causes carries the held
     * level, so taking it changes nothing. Returns true when the level was taken.
     */
    @Synchronized
    fun changed(stream: Int, previous: Int, value: Int): Boolean {
        if (previous < 0 || value < 0 || previous == value) return false
        levels[stream] = value
        return true
    }

    /** The level [stream] must be set to now that it reads [current], or null when it already has it. */
    @Synchronized
    fun restore(stream: Int, current: Int): Int? = levels.getOrPut(stream) { current }.takeIf { it != current }
}
