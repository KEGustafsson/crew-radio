package fi.crewradio.ask

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hearing the question, on this phone only.
 *
 * On-device recognition is not a preference here, it is the whole design. At sea there is no
 * internet, so a recogniser that needs one does not work when it is wanted; and a crew channel is
 * not something to send to somebody else's servers. Where the phone cannot do it locally the
 * feature is switched off and says so, rather than quietly falling back to the network.
 *
 * `SpeechRecognizer` is main-thread only, every call and every callback, and it holds the
 * microphone while it listens — which is why the engine's voice-keying monitor is suspended
 * around this (see `PttEngine.setAsking`). Two `AudioRecord` clients do not share a microphone,
 * and worse, a live gate could key the channel with the question.
 */
class AskRecognizer(private val context: Context) {

    interface Listener {
        /** What it has heard so far; replaced as it changes its mind. */
        fun onPartial(text: String)

        /** Microphone level, 0..1, about ten times a second. */
        fun onLevel(level: Float)

        /** Best guess first. All of them are matched, not only the first. */
        fun onResults(hypotheses: List<String>)

        /** Nothing usable. [message] is already a sentence for the crew. */
        fun onFailed(message: String)
    }

    private var recognizer: SpeechRecognizer? = null

    /** Starts listening. Main thread. */
    fun start(listener: Listener) {
        stop()
        // The SDK check is repeated here, rather than left to available(), so it guards the call
        // to createOnDeviceSpeechRecognizer for the compiler and for lint as well as at runtime.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !available(context)) {
            return listener.onFailed(UNSUPPORTED)
        }
        val speech = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            // Vendors have shipped stubs that throw here rather than reporting unavailable.
            return listener.onFailed(e.message ?: UNSUPPORTED)
        }
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onRmsChanged(rmsdB: Float) {
                // The framework reports roughly -2..10 dB; anything below the floor is silence.
                listener.onLevel(((rmsdB - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                hypotheses(partialResults).firstOrNull()?.let { listener.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                val found = hypotheses(results)
                if (found.isEmpty()) listener.onFailed(NOTHING_HEARD) else listener.onResults(found)
            }

            override fun onError(error: Int) {
                listener.onFailed(reason(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // The top guess is often the wrong half of a near-homophone pair, and AskIntents
            // matches every hypothesis, so more of them is free accuracy.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            speech.startListening(intent)
        } catch (e: SecurityException) {
            stop()
            listener.onFailed(e.message ?: UNSUPPORTED)
        }
    }

    /** Stops and releases the microphone. Main thread. Safe to call twice. */
    fun stop() {
        val speech = recognizer ?: return
        recognizer = null
        try {
            speech.cancel()
        } catch (_: Exception) {
            // Already gone; nothing holds the microphone either way.
        }
        speech.destroy()
    }

    /** A reason code as something worth reading. The strings are resolved by the caller. */
    private fun reason(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOTHING_HEARD
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> NO_PERMISSION
        else -> "$RECOGNITION_FAILED ($error)"
    }

    companion object {
        /** Marker results the caller turns into strings; kept out of resources because they are not shown as-is. */
        const val UNSUPPORTED = "unsupported"
        const val NOTHING_HEARD = "nothing-heard"
        const val NO_PERMISSION = "no-permission"
        const val RECOGNITION_FAILED = "recognition-failed"

        const val MAX_RESULTS = 5
        private const val QUIET_DB = -2f
        private const val LOUD_DB = 10f

        /**
         * True when this phone can recognise speech without a network. The API arrived in Android
         * 12; below that there is no on-device guarantee, so the feature stays off rather than
         * sending the crew's questions somewhere.
         */
        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        private fun hypotheses(bundle: Bundle?): List<String> =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.filter { it.isNotBlank() }
                ?: emptyList()
    }
}
