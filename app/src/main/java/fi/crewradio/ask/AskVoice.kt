package fi.crewradio.ask

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fi.crewradio.R
import java.util.Locale

/**
 * Saying the answer on this phone: "Just me", and "Whole crew" on a phone that has not joined
 * and so would never hear the boat say it.
 *
 * Which output it uses depends on whether a channel session is up; [applyRoute] explains why.
 * The engine is asked to duck the channel while it speaks, so the answer is not buried under
 * somebody else's transmission.
 */
class AskVoice(context: Context) {

    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var onDone: (() -> Unit)? = null

    /** Whether a channel session owns the audio, which decides where the answer comes out. */
    private var onChannel = false

    init {
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                // The answer is read in the language the phrases and the wording are written in,
                // not the phone's: on a Finnish phone a Finnish voice would read English aloud.
                val wanted = Locale.forLanguageTag(app.getString(R.string.ask_speech_language))
                tts?.setLanguage(wanted.takeIf { supported(it) } ?: Locale.UK)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finished()
                    // Abstract, and deprecated in favour of the two-argument form below; both
                    // exist because which one an engine calls depends on how old it is.
                    @Deprecated("The platform's own deprecation; an older engine still calls this one.")
                    override fun onError(utteranceId: String?) = finished()
                    override fun onError(utteranceId: String?, errorCode: Int) = finished()
                })
                pending?.let { pending = null; speak(it, onChannel, onDone) }
            } else {
                // No engine at all: the sheet still shows the answer, which is most of the value.
                pending = null
                finished()
            }
        }
    }

    private fun supported(locale: Locale): Boolean {
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    /**
     * Where the answer comes out.
     *
     * On channel it is [AudioAttributes.USAGE_VOICE_COMMUNICATION], the usage the channel's own
     * playback uses, so it follows whatever `AudioRoute` picked and does not blast the loudspeaker
     * while somebody is wearing a headset. Off channel there is no route session and no
     * communication mode, and that usage lands on the voice-call stream, which then sits at its
     * minimum on the earpiece: the answer is spoken and nobody hears it. So off channel it goes
     * out as an assistant, on the ordinary media path.
     */
    private fun applyRoute() {
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(
                    if (onChannel) AudioAttributes.USAGE_VOICE_COMMUNICATION
                    else AudioAttributes.USAGE_ASSISTANT
                )
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    /** Says [text], replacing anything already being said. [whenDone] runs on a engine thread. */
    fun speak(text: String, onChannel: Boolean, whenDone: (() -> Unit)? = null) {
        onDone = whenDone
        this.onChannel = onChannel
        val engine = tts
        if (engine == null || !ready) {
            // Still starting up: say it as soon as it is ready, which is well within a second.
            pending = text
            return
        }
        applyRoute()
        // speak() only reports whether the request reached the queue. When it did not there is no
        // progress callback to come, so the caller would wait for an utterance that never starts —
        // and the channel would stay ducked behind it.
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE) != TextToSpeech.SUCCESS) finished()
    }

    private fun finished() {
        val callback = onDone
        onDone = null
        callback?.invoke()
    }

    /** Stops anything in progress and releases the engine. */
    fun stop() {
        pending = null
        onDone = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val UTTERANCE = "crewradio-ask"
    }
}
