package fi.crewradio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Where the mixer's 20 ms frames go. [AudioPlayback] is the real one, an AudioTrack; the
 * mixer's tests supply a fake, so this interface is the whole contract the mixer relies on.
 */
interface Playback {
    fun start()

    /**
     * Writes [length] bytes from [offset], blocking until they are queued, and returns how many
     * were taken: [length] normally, fewer or a negative AudioTrack error code (ERROR_DEAD_OBJECT
     * after an audio-server restart) when the track is no longer playing.
     */
    fun write(data: ByteArray, offset: Int, length: Int): Int

    fun stop()

    /** Times the track ran dry since [start], as the platform counts them; 0 without a track. */
    fun underrunCount(): Int
}

/** Streaming PCM16 playback through an AudioTrack. write() is blocking; call it from the mixer thread. */
class AudioPlayback : Playback {

    private var track: AudioTrack? = null

    override fun start() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConfig.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            // The mixer writes a frame every 20 ms whether or not anyone talks, so the track sits
            // full and its size is pure delay. Two frames is the least the platform is happy with;
            // the jitter buffer is the mixer's own prefill, and an underrun count that climbs on a
            // phone is the sign to grow that instead.
            .setBufferSizeInBytes(maxOf(minBuf, AudioConfig.FRAME_BYTES * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    override fun write(data: ByteArray, offset: Int, length: Int): Int =
        track?.write(data, offset, length) ?: AudioTrack.ERROR_INVALID_OPERATION

    override fun underrunCount(): Int = track?.underrunCount ?: 0

    override fun stop() {
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }
}
