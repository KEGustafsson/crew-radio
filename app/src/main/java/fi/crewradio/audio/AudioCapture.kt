package fi.crewradio.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread

/**
 * Pulls 20 ms PCM16 frames from the mic and hands them to [onFrame].
 * VOICE_COMMUNICATION source enables the platform AEC/NS where available.
 *
 * A read that fails (ERROR_DEAD_OBJECT after an audio-server restart, or the input taken by a
 * phone call) does not come back on its own: the worker stops pulling and [onError] is called
 * once, from the capture thread, with a short reason for the status line. The engine then
 * un-keys or restarts the monitor; [stop] still owns every release, so a failed capture is torn
 * down the same way as a good one, and [start] after a failure first clears the old record.
 */
class AudioCapture(
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit = {},
) {

    /** `AudioCapture { frame -> ... }`: the trailing lambda is the frame sink, the failure goes unreported. */
    constructor(onFrame: (ByteArray) -> Unit) : this(onFrame, {})

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        if (record != null) stop()            // a capture that died on a read: release it before opening again
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, AudioConfig.FRAME_BYTES * 4)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord init failed")
        }
        record = rec
        // Hardware/platform echo cancellation is what makes full duplex on speakerphone usable.
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        running = true
        rec.startRecording()
        worker = thread(name = "ptt-capture") {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val buf = ByteArray(AudioConfig.FRAME_BYTES)     // handed on: onFrame keeps it
                var got = 0
                var n = 0
                while (running && got < buf.size) {
                    n = rec.read(buf, got, buf.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got == buf.size) {
                    onFrame(buf)
                } else if (running && n <= 0) {
                    // A recording record never returns nothing from a blocking read: the input is
                    // gone. An urgent-audio thread must not spin on it, so this is the end of the
                    // worker; stop() (ours or the engine's) releases the record.
                    running = false
                    onError("mic read failed ($n)")
                }
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
        aec?.release(); aec = null
        ns?.release(); ns = null
        record?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        record = null
    }
}
