package fi.crewradio.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Opus encoder on top of the platform MediaCodec (`audio/opus`, an AOSP
 * software codec since Android 10), so no native library is needed.
 *
 * Feed it 20 ms PCM16 frames from the capture thread; every finished Opus
 * packet comes back through [onPacket] on the same thread. The codec is run
 * synchronously with a short input timeout, so a stalled encoder drops a frame
 * instead of blocking the mic, and one short wait for output right after each
 * frame goes in, so the packet leaves on this call rather than the next one.
 *
 * Complexity 5 instead of the default 10: the same speech quality at 24 kbit/s
 * for a fraction of the CPU, which on a phone is battery. Constant bitrate is
 * asked for too, so a packet's size says nothing about the speech in it; an
 * encoder that refuses the request is configured without it.
 */
class OpusEncoder(private val onPacket: (ByteArray) -> Unit) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioConfig.SAMPLE_RATE, 1)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, AudioConfig.OPUS_BITRATE)
        fmt.setInteger(MediaFormat.KEY_COMPLEXITY, COMPLEXITY)
        try {
            try {
                fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (_: Exception) {
                // The AOSP encoder ignores a mode it does not do; another may refuse it. Same format, no mode.
                codec.reset()
                fmt.removeKey(MediaFormat.KEY_BITRATE_MODE)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /** Queues one 20 ms frame and emits whatever packets the encoder has finished. */
    fun encode(pcm: ByteArray) {
        val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(pcm)
            codec.queueInputBuffer(idx, 0, pcm.size, ptsUs, 0)
            ptsUs += AudioConfig.FRAME_MS * 1000L
        }
        drain()
    }

    /**
     * Hands every finished packet to [onPacket]; codec-config output is skipped, receivers
     * synthesise their own. The first poll waits [OUTPUT_TIMEOUT_US] for the frame just queued,
     * the rest only take what is ready.
     */
    private fun drain() {
        var timeoutUs = OUTPUT_TIMEOUT_US
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (idx < 0) continue                       // format / buffers changed: nothing to read
            timeoutUs = 0
            val out = codec.getOutputBuffer(idx)
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (out != null && info.size > 0 && !isConfig) {
                val bytes = ByteArray(info.size)
                out.position(info.offset)
                out.get(bytes)
                onPacket(bytes)
            }
            codec.releaseOutputBuffer(idx, false)
        }
    }

    /** Stops and frees the MediaCodec; the encoder cannot be reused afterwards. */
    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 20_000L
        /** Half a frame: long enough for the software encoder to finish, short enough never to hold up the mic. */
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val COMPLEXITY = 5
    }
}
