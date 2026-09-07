package fi.crewradio.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One Opus decoder per remote sender, on top of the platform MediaCodec.
 *
 * All phones encode with the same parameters, so instead of shipping codec
 * config over the air the decoder synthesises the OpusHead itself. The AOSP
 * decoder always outputs 48 kHz, so decoded audio is decimated back to the
 * mixer's 16 kHz and re-framed into exact 20 ms frames.
 *
 * The header's pre-skip is the encoder's look-ahead, [PRE_SKIP] samples at 48 kHz
 * (6.5 ms): the decoder drops that much from the start of the stream, so a talker's
 * first frame is not padded with the encoder's warm-up and later frames are not late by it.
 * MediaCodec wants the same figure again as csd-1, in nanoseconds.
 *
 * Not thread-safe: the engine serialises calls per decoder.
 */
class OpusDecoder {

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private var outputRate = 0
    private var decimator: Decimator? = null

    // Decoded 16 kHz samples waiting to be cut into whole frames.
    private var pending = ShortArray(AudioConfig.FRAME_SAMPLES * 8)
    private var pendingLen = 0

    /** Last time a packet was fed in; the engine releases decoders that go quiet. */
    @Volatile var lastUsedNs: Long = System.nanoTime()
        private set

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, AudioConfig.SAMPLE_RATE, 1)
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(channels = 1, inputRate = AudioConfig.SAMPLE_RATE)))
        fmt.setByteBuffer("csd-1", ByteBuffer.wrap(preSkipNs()))         // pre-skip, ns
        fmt.setByteBuffer("csd-2", ByteBuffer.wrap(ByteArray(8)))        // seek pre-roll, ns: never seeking
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    /** Decodes one packet; calls [onFrame] with each complete 640-byte 16 kHz PCM16 frame produced. */
    fun decode(packet: ByteArray, offset: Int, length: Int, onFrame: (ByteArray) -> Unit) {
        lastUsedNs = System.nanoTime()
        val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (idx >= 0) {
            val buf = codec.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(packet, offset, length)
            codec.queueInputBuffer(idx, 0, length, ptsUs, 0)
            ptsUs += AudioConfig.FRAME_MS * 1000L
        }
        drain(onFrame)
    }

    /**
     * Pulls the decoded output, resamples it to 16 kHz and emits complete frames. The first poll
     * waits [OUTPUT_TIMEOUT_US] for the packet just queued, so its audio plays in this slot and
     * not the next; the rest only take what is ready.
     */
    private fun drain(onFrame: (ByteArray) -> Unit) {
        var timeoutUs = OUTPUT_TIMEOUT_US
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                idx < 0 -> {}
                else -> {
                    timeoutUs = 0
                    val out = codec.getOutputBuffer(idx)
                    if (out != null && info.size > 0) {
                        if (outputRate == 0) onFormat(codec.getOutputFormat(idx))
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val samples = ShortArray(info.size / 2)
                        out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                        append(decimator?.process(samples) ?: samples)
                        emitFrames(onFrame)
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    /** Reads the decoder's real output rate (48 kHz on AOSP) and sets up the matching decimator. */
    private fun onFormat(fmt: MediaFormat) {
        val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (channels != 1 || rate % AudioConfig.SAMPLE_RATE != 0) {
            throw IllegalStateException("unsupported decoder output $rate Hz x$channels")
        }
        outputRate = rate
        val factor = rate / AudioConfig.SAMPLE_RATE
        decimator = if (factor > 1) Decimator(factor) else null
    }

    /** Adds 16 kHz samples to the pending buffer, growing it if a burst arrives. */
    private fun append(samples: ShortArray) {
        if (pendingLen + samples.size > pending.size) pending = pending.copyOf((pendingLen + samples.size) * 2)
        System.arraycopy(samples, 0, pending, pendingLen, samples.size)
        pendingLen += samples.size
    }

    /** Cuts the pending samples into whole 20 ms frames and keeps the remainder for next time. */
    private fun emitFrames(onFrame: (ByteArray) -> Unit) {
        var start = 0
        while (pendingLen - start >= AudioConfig.FRAME_SAMPLES) {
            val frame = ByteArray(AudioConfig.FRAME_BYTES)
            ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                .put(pending, start, AudioConfig.FRAME_SAMPLES)
            onFrame(frame)
            start += AudioConfig.FRAME_SAMPLES
        }
        if (start > 0) {
            System.arraycopy(pending, start, pending, 0, pendingLen - start)
            pendingLen -= start
        }
    }

    /** Stops and frees the MediaCodec; the decoder cannot be reused afterwards. */
    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    companion object {
        private const val INPUT_TIMEOUT_US = 20_000L
        /** Half a frame: the software decoder is done well within it, and the engine's thread is not held longer. */
        private const val OUTPUT_TIMEOUT_US = 10_000L

        /** Opus's look-ahead, in samples at 48 kHz: 6.5 ms, what libopus reports for every rate and mode. */
        const val PRE_SKIP = 312
        private const val OPUS_RATE = 48_000L

        /** RFC 7845 identification header, 19 bytes: mapping family 0, [PRE_SKIP], no gain. */
        fun opusHead(channels: Int, inputRate: Int): ByteArray =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray(Charsets.US_ASCII))
                .put(1)                       // version
                .put(channels.toByte())
                .putShort(PRE_SKIP.toShort()) // pre-skip (samples at 48 kHz)
                .putInt(inputRate)
                .putShort(0)                  // output gain, Q7.8 dB
                .put(0)                       // channel mapping family
                .array()

        /** The same pre-skip as MediaCodec's csd-1: a little-endian int64 of nanoseconds. */
        fun preSkipNs(): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(PRE_SKIP * 1_000_000_000L / OPUS_RATE)
                .array()
    }
}
