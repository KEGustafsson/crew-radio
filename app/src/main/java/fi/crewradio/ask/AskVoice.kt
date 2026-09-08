package fi.crewradio.ask

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Saying the answer on this phone, in "Just me" mode.
 *
 * It goes out as [AudioAttributes.USAGE_VOICE_COMMUNICATION], the same usage the channel's own
 * playback uses, so it follows whatever `AudioRoute` picked: a Bluetooth headset if one is in
 * use, the earpiece at the ear, the loudspeaker otherwise. Given the default usage it would come
 * out of the loudspeaker while the crew member is wearing a headset, which is the one thing this
 * mode exists to avoid.
 *
 * The engine is asked to duck the channel while it speaks, so the answer is not buried under
 * somebody else's transmission.
 */
class AskVoice(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.language = Locale.getDefault().takeIf { supported(it) } ?: Locale.UK
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finished()
                    // Abstract, and deprecated in favour of the two-argument form below; both
                    // exist because which one an engine calls depends on how old it is.
                    override fun onError(utteranceId: String?) = finished()
                    override fun onError(utteranceId: String?, errorCode: Int) = finished()
                })
                pending?.let { pending = null; speak(it, onDone) }
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

    /** Says [text], replacing anything already being said. [whenDone] runs on a engine thread. */
    fun speak(text: String, whenDone: (() -> Unit)? = null) {
        onDone = whenDone
        val engine = tts
        if (engine == null || !ready) {
            // Still starting up: say it as soon as it is ready, which is well within a second.
            pending = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
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
