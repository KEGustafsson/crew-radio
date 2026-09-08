package fi.crewradio.ask

import android.content.Context
import android.os.Handler
import android.os.Looper
import fi.crewradio.Prefs
import fi.crewradio.PttEngine
import fi.crewradio.R
import java.util.concurrent.Executors

/**
 * One question, start to finish: hear it, work out what it asks for, read it off the boat, put
 * words round it, and say it — here, or to the whole crew.
 *
 * Everything the crew sees goes through [State], which is delivered on the main thread; the
 * network happens on a `ptt-ask` thread, because a socket on the main thread is how an app stops
 * responding on a boat with a flaky access point.
 *
 * The microphone is the delicate part. While this is listening, the engine's voice-keying monitor
 * must not be running: two `AudioRecord` clients do not share a microphone, and a live gate would
 * key the channel with the question. So the engine is put into `asking` for the whole session and
 * taken out of it in exactly one place — [finish] — whatever happened in between.
 */
class AskController(
    private val context: Context,
    private val prefs: Prefs,
    /**
     * The engine, looked up when it is needed rather than held: the screen is built before the
     * service binds, and the session can end under an open sheet.
     */
    private val engineOf: () -> PttEngine?,
) {

    /** Where the question has got to. Every one of these is a thing the sheet can draw. */
    sealed interface State {
        /** Listening. [heard] is what the recogniser has decided so far, and may still change. */
        data class Listening(val heard: String) : State

        /** Heard, and now reading the boat. */
        data class Working(val heard: String) : State

        /**
         * Answered. [sentence] is what was said; [detail] names the source and its age, so the
         * crew can see a reading came from the GPS rather than the compass.
         */
        data class Answered(val heard: String, val sentence: String, val detail: String) : State

        /**
         * Nothing usable. [message] already reads as a sentence.
         *
         * [retry] is true when asking again could work — misheard, a server that did not answer,
         * a speech pack still arriving. It is false only for the two the crew must leave the app
         * to fix, a phone with no on-device recognition and a refused microphone, where an "Ask
         * again" button would just fail the same way.
         */
        data class Failed(val heard: String, val message: String, val retry: Boolean = true) : State

        /** Typing instead of speaking. */
        data object Typing : State
    }

    /** Who hears it. The setting's two values, overridable for one question from the sheet. */
    enum class Mode { JUST_ME, CREW }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "ptt-ask") }
    private val recognizer = AskRecognizer(context)
    private var voice: AskVoice? = null
    private var onState: ((State) -> Unit)? = null
    private var onLevel: ((Float) -> Unit)? = null

    /** Bumped by every start and by [finish]; a result from an abandoned question is dropped. */
    private var generation = 0

    var mode: Mode = modeOf(prefs.askMode)
        private set

    private fun modeOf(stored: String): Mode =
        if (stored == Prefs.ASK_MODE_CREW) Mode.CREW else Mode.JUST_ME

    /** True when the row should be on the main screen at all. */
    fun offered(): Boolean = prefs.askEnabled && prefs.askServer != null

    /** True when this phone can hear a question; false means the sheet opens straight into typing. */
    fun canListen(): Boolean = AskRecognizer.available(context)

    /**
     * Starts a question. [onState] and [onLevel] are called on the main thread until [finish].
     * With [typed] true the microphone is never opened, which is the path that works on a phone
     * without on-device recognition and in a cockpit too noisy to be heard in.
     *
     * [keepMode] carries the mode of the question just asked into this one, which is what a
     * second question in the same sheet wants: having chosen "Whole crew" for a question,
     * asking again should not quietly drop back to the setting. A sheet opened afresh from the
     * row always starts from the setting.
     */
    fun start(typed: Boolean, keepMode: Boolean, onState: (State) -> Unit, onLevel: (Float) -> Unit) {
        finish()
        val gen = ++generation
        this.onState = onState
        this.onLevel = onLevel
        if (!keepMode) mode = modeOf(prefs.askMode)
        // Off channel there is nobody to say it to, whatever the setting or the last question
        // chose, so the question is Just me and the pill is dimmed to say so.
        if (!crewPossible()) mode = Mode.JUST_ME
        // The channel's own microphone use stops for the duration, whichever way the question comes in:
        // it also stops a half-duplex phone transmitting over its own answer.
        engineOf()?.setAsking(true)
        if (typed || !canListen()) {
            deliver(gen, State.Typing)
            return
        }
        deliver(gen, State.Listening(""))
        recognizer.start(object : AskRecognizer.Listener {
            override fun onPartial(text: String) = deliver(gen, State.Listening(text))
            override fun onLevel(level: Float) {
                main.post { if (gen == generation) this@AskController.onLevel?.invoke(level) }
            }
            override fun onResults(hypotheses: List<String>) = ask(gen, hypotheses)
            override fun onFailed(message: String) =
                deliver(gen, State.Failed("", explain(message), retry = retryable(message)))
        })
    }

    /** A typed question: the same path with the microphone left out. */
    fun submit(text: String) {
        if (text.isBlank()) return
        ask(generation, listOf(text))
    }

    /**
     * Whether "Whole crew" is on offer at all.
     *
     * That mode has the boat say the answer over the channel. A phone that has not joined is not
     * in that conversation, so off channel the question is Just me and the pill does not move.
     */
    fun crewPossible(): Boolean = engineOf()?.isConnected == true

    /** Flips who hears this one answer. The setting is not touched. */
    fun toggleMode() {
        if (!crewPossible()) return
        mode = if (mode == Mode.CREW) Mode.JUST_ME else Mode.CREW
    }

    /**
     * Ends the question and gives the microphone and the channel back. Called when the sheet
     * closes, however it closes — including when the activity goes away mid-question.
     */
    fun finish() {
        generation++
        onState = null
        onLevel = null
        recognizer.stop()
        voice?.stop()
        voice = null
        engineOf()?.setAsking(false)
    }

    /** Frees everything for good; the controller is not usable afterwards. */
    fun release() {
        finish()
        // finish() only cancels: this is where the recogniser is unbound, so it survives an
        // "Ask again" but not the screen closing.
        recognizer.release()
        work.shutdownNow()
    }

    // ---- the question itself ------------------------------------------------------

    private fun ask(gen: Int, hypotheses: List<String>) {
        if (gen != generation) return
        recognizer.stop()                                  // the microphone is not needed past this point
        val intents = AskVocabulary.intents(context)
        val match = intents.match(hypotheses, prefs.crewName)
        if (match.quantities.isEmpty()) {
            deliver(gen, State.Failed(match.transcript, context.getString(R.string.ask_say_again)))
            return
        }
        deliver(gen, State.Working(match.transcript))
        val base = prefs.askServer
        if (base == null) {
            deliver(gen, State.Failed(match.transcript, context.getString(R.string.ask_no_server)))
            return
        }
        val token = prefs.askToken
        val unitPrefs = prefs.askUnits
        val wanted = mode
        work.execute {
            val client = SignalKClient(base, token)
            when (val read = client.read(Quantity.subtreesOf(match.quantities))) {
                is SignalKClient.Result.Failed -> deliver(gen, State.Failed(match.transcript, explain(read.failure)))
                is SignalKClient.Result.Ok -> {
                    val answer = AskAnswer.build(
                        match.quantities, read.value, System.currentTimeMillis(), unitPrefs, prefs.askInstances,
                    )
                    val sentence = AskWording.sentence(answer, AskVocabulary.of(context))
                    val detail = detailOf(answer)
                    if (wanted == Mode.CREW) {
                        val line = context.getString(R.string.ask_crew_answer, prefs.speakerName, match.transcript, sentence)
                        when (val said = client.say(line)) {
                            is SignalKClient.Result.Ok -> {
                                // The boat says it over the channel. A phone that has not joined
                                // hears nothing of its own answer, so it says it here as well.
                                speakHere(gen, sentence, onlyOffChannel = true)
                                deliver(gen, State.Answered(match.transcript, sentence, detail))
                            }
                            is SignalKClient.Result.Failed -> {
                                // The crew did not get it, so at least the person who asked does.
                                speakHere(gen, sentence)
                                deliver(
                                    gen,
                                    State.Answered(
                                        match.transcript, sentence,
                                        context.getString(R.string.ask_say_failed) + " " + explain(said.failure),
                                    ),
                                )
                            }
                        }
                    } else {
                        speakHere(gen, sentence)
                        deliver(gen, State.Answered(match.transcript, sentence, detail))
                    }
                }
            }
        }
    }

    /**
     * Says the answer on this phone. [onlyOffChannel] holds it back on a phone that is on the
     * channel, which is what "Whole crew" wants: there the boat says it over the air and saying
     * it here too would double it. The engine is read on the main thread, never on the worker.
     */
    private fun speakHere(gen: Int, sentence: String, onlyOffChannel: Boolean = false) {
        main.post {
            if (gen != generation) return@post
            val engine = engineOf()
            val onChannel = engine?.isConnected == true
            if (onlyOffChannel && onChannel) return@post
            val speaker = voice ?: AskVoice(context).also { voice = it }
            // The channel is ducked so the answer is not buried under somebody else's transmission.
            engine?.duck(true)
            speaker.speak(sentence, onChannel) { main.post { engineOf()?.duck(false) } }
        }
    }

    /** Where the answer came from and how old it was: the first real reading in it. */
    private fun detailOf(answer: AskAnswer.Answer): String {
        val (path, age) = when (val first = answer.items.firstOrNull { it !is AskAnswer.Item.Missing }) {
            is AskAnswer.Item.Value -> first.path to first.ageSec
            is AskAnswer.Item.Relative -> first.path to first.ageSec
            is AskAnswer.Item.Position -> first.path to first.ageSec
            is AskAnswer.Item.Duration -> first.path to first.ageSec
            else -> return ""
        }
        return context.getString(R.string.ask_source, path, age.toInt())
    }

    private fun explain(failure: SignalKClient.Failure): String = context.getString(
        when (failure) {
            SignalKClient.Failure.UNREACHABLE -> R.string.ask_unreachable
            SignalKClient.Failure.UNAUTHORIZED -> R.string.ask_unauthorized
            SignalKClient.Failure.BAD_RESPONSE -> R.string.ask_bad_response
        }
    )

    /** Whether asking again is worth a button: everything except the two the crew must leave to fix. */
    private fun retryable(marker: String): Boolean =
        marker != AskRecognizer.UNSUPPORTED && marker != AskRecognizer.NO_PERMISSION

    private fun explain(marker: String): String = when (marker) {
        AskRecognizer.UNSUPPORTED -> context.getString(R.string.ask_no_recognition)
        AskRecognizer.NOTHING_HEARD -> context.getString(R.string.ask_say_again)
        AskRecognizer.NO_PERMISSION -> context.getString(R.string.ask_mic_denied)
        AskRecognizer.LANGUAGE_DOWNLOADING -> context.getString(R.string.ask_language_downloading)
        // Everything else is the recogniser refusing to start rather than failing to hear:
        // an error code from onError, or the message of whatever createOnDeviceSpeechRecognizer
        // threw. "Say again." would hide it and the crew would keep asking into a dead microphone.
        else -> context.getString(R.string.ask_recognition_failed, marker)
    }

    private fun deliver(gen: Int, state: State) {
        main.post { if (gen == generation) onState?.invoke(state) }
    }
}
