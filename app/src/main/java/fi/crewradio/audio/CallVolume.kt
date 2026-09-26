package fi.crewradio.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build

/**
 * The phone's own call volume, which is what the session plays on: the [AudioPlayback] track has
 * `USAGE_VOICE_COMMUNICATION`, so it sits on the voice-call stream, the one the volume keys move
 * during a phone call and a Bluetooth headset's buttons move over HFP. The main screen's slider
 * sets it directly, because on channel the volume keys are a talk key and cannot.
 *
 * The stream has a floor (1 on every phone seen) and cannot be muted by an app, so the true mute
 * stays in the [Mixer]. Until Android 13 a Bluetooth headset's SCO link has a stream of its own
 * (`STREAM_BLUETOOTH_SCO`, hidden); Android 14 folded it into the voice-call stream.
 *
 * Android keeps that level per output device (earpiece, loudspeaker, each headset), and joining
 * or leaving moves the stream from one to another, so the level the crew chose is held in
 * [VolumeMemory] for the process and put back on whatever device the stream lands on ([restore]).
 * The slider shows the held level ([level]), not the device's, so it never jumps.
 */
class CallVolume(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The stream in use: the voice-call stream, or the SCO stream while a Bluetooth headset carries the call on Android 13 and below. */
    fun stream(bluetoothHeadset: Boolean): Int =
        if (bluetoothHeadset && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) STREAM_BLUETOOTH_SCO else AudioManager.STREAM_VOICE_CALL

    fun min(stream: Int): Int = try { am.getStreamMinVolume(stream) } catch (_: Exception) { 0 }
    fun max(stream: Int): Int = try { am.getStreamMaxVolume(stream) } catch (_: Exception) { 1 }
    fun get(stream: Int): Int = try { am.getStreamVolume(stream) } catch (_: Exception) { 0 }

    /** The level the crew chose for [stream]: the one to show, whichever device the stream is on. */
    fun level(stream: Int): Int = memory.level(stream, get(stream))

    /** The crew's choice: held, and set silently (no system volume panel), clamped to the stream's range. */
    fun set(stream: Int, index: Int) {
        val level = index.coerceIn(min(stream), max(stream))
        memory.chose(stream, level)
        write(stream, level)
    }

    /** Puts the held level back on [stream] if the device it now plays on has another. */
    fun restore(stream: Int) {
        memory.restore(stream, get(stream))?.let { write(stream, it.coerceIn(min(stream), max(stream))) }
    }

    /**
     * The two system broadcasts, from a receiver registered for [VOLUME_CHANGED_ACTION] and
     * [STREAM_DEVICES_CHANGED_ACTION]: a level changed on the device in use is the crew's choice
     * (a headset button, the phone's own panel), and a stream that moved to another device gets
     * the held level back. Other streams are left alone.
     */
    fun heard(intent: Intent) {
        val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
        if (stream != AudioManager.STREAM_VOICE_CALL && stream != STREAM_BLUETOOTH_SCO) return
        when (intent.action) {
            VOLUME_CHANGED_ACTION -> memory.changed(
                stream,
                intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1),
                intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
            )
            STREAM_DEVICES_CHANGED_ACTION -> restore(stream)
        }
    }

    private fun write(stream: Int, level: Int) {
        try { am.setStreamVolume(stream, level, 0) } catch (_: Exception) {}
    }

    companion object {
        /** `AudioManager.STREAM_BLUETOOTH_SCO`, hidden in the SDK; the value has never changed. */
        const val STREAM_BLUETOOTH_SCO = 6
        /** Sent by the system when any stream's level changes (a headset button, the phone's own panel). Not in the SDK; used by every volume widget. */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        /** Sent by the system when a stream moves to other output devices (the mode or the communication device changed). Not in the SDK. */
        const val STREAM_DEVICES_CHANGED_ACTION = "android.media.STREAM_DEVICES_CHANGED_ACTION"
        /** The stream both broadcasts are about. Not in the SDK. */
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        /** [VOLUME_CHANGED_ACTION]'s new level. Not in the SDK. */
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        /** [VOLUME_CHANGED_ACTION]'s level before, on the same device. Not in the SDK. */
        const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        /** The held levels belong to the process: the screen and the service each have their own [CallVolume]. */
        private val memory = VolumeMemory()
    }
}
