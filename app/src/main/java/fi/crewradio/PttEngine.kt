package fi.crewradio

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import fi.crewradio.audio.AudioCapture
import fi.crewradio.audio.AudioConfig
import fi.crewradio.audio.AudioRoute
import fi.crewradio.audio.Conceal
import fi.crewradio.audio.MicGate
import fi.crewradio.audio.Mixer
import fi.crewradio.audio.OpusDecoder
import fi.crewradio.audio.OpusEncoder
import fi.crewradio.transport.Transport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * One crew member as the roster sees it. [name] stays null until a hello has been heard (or
 * when the hello carries no name); it is then listed from its audio, by id.
 *
 * [via] is the transport this phone heard it on last; [hops] how many relays that took;
 * [transports] the flags from its hello ([Hello.describe]), i.e. what it is connected to;
 * [versionCode] the build it runs, 0 until a hello arrives or for a node that is not the app;
 * [level] how well its packets reach this phone, 1 to [LinkQuality.BARS] bars ([LinkQuality]).
 */
class Peer(
    val id: Int,
    val name: String?,
    val transports: Int,
    val via: String,
    val hops: Int,
    val talking: Boolean,
    /** Milliseconds since we last heard anything from it, at the time the list was built. */
    val seenAgoMs: Long,
    val versionCode: Int,
    val level: Int
) {
    val label: String get() = name ?: id.toUInt().toString(16)
}

/** Packet counters since Connect; for the Status screen. */
class Stats(
    val rxPackets: Long, val rxBytes: Long,
    val txPackets: Long, val txBytes: Long,
    val relayed: Long, val duplicates: Long, val hellos: Long,
    /** Packets dropped because a sender exceeded its rate budget or failed validation. */
    val rejected: Long,
    /** 20 ms slots the mixer filled with a faded repeat because the packet never came. */
    val concealed: Long,
    /** Authentic packets dropped because their timestamp was more than [Packet.REPLAY_WINDOW_S] off this clock. */
    val stale: Long,
    /** Frames the playback track ran dry for, as the mixer counted them. */
    val underruns: Long
)

/**
 * Glue between mic, codec, transports, relay, roster and mixer.
 *
 * HALF_DUPLEX: mic runs only while the talk button is held; incoming audio is
 *              not played while transmitting (radio behaviour) but is still relayed.
 * FULL_DUPLEX: mic on/off is a toggle; incoming audio always plays and
 *              simultaneous talkers are mixed.
 *
 * Codec: outgoing frames go out as Opus ([Packet.Codec.OPUS]) when [codec] says
 * so and the platform encoder starts, otherwise as raw PCM. Incoming packets are
 * decoded by what their header says, one [OpusDecoder] per remote sender, so a
 * mixed crew of Opus and PCM phones just works.
 *
 * Ingress: every packet carries (senderId, seq), a ttl and the sender's clock. [Ingress] makes
 * the decisions in order (global budget, AEAD, timestamp, seen-cache, sender budget) and the
 * engine counts the outcome; a packet not seen before is played and, if [relay] is on and the
 * ttl allows, forwarded on every transport (excluding the link it came from) with the ttl
 * decremented. Duplicates are dropped by the seen-cache, so a flood across a multi-hop
 * Aware/BT topology terminates. Running several transports at once makes this phone a bridge
 * (e.g. boat Wi-Fi <-> Aware).
 *
 * Roster: while connected a heartbeat thread sends a [Hello] every second, and every
 * hello or audio packet heard refreshes that sender's entry. A sender silent for
 * [PEER_TIMEOUT_MS] is dropped; at most [MAX_NODES] are tracked. Timing uses the
 * monotonic clock, so a wall-clock change never ages or revives anyone (the wall clock is only
 * stamped on packets). [onRoster] fires only when the list actually changes.
 *
 * Threads: [connect] and [disconnect] join transport and capture threads and may block for a
 * moment; call them from the service's session thread, not the main thread (they are safe from
 * any thread). [startTalking], [stopTalking] and [toggleTalking] never block the caller: the
 * talk state flips at once under [talkLock], so [isTalking] is right immediately, and the
 * blocking work (opening the mic, stopping the capture, releasing the codec) runs on the
 * single `ptt-audio-ctl` thread in the order the calls were made. Setting [channelKey] derives
 * the packet key, which takes about a second: do that off the main thread too.
 */
class PttEngine(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onRoster: (List<Peer>) -> Unit = {}
) {

    enum class Mode { HALF_DUPLEX, FULL_DUPLEX }

    @Volatile var mode: Mode = Mode.HALF_DUPLEX
        set(value) {
            if (field != value) {
                field = value
                stopTalking()
                onStatus("Mode: ${value.name.lowercase().replace('_', ' ')}")
            }
        }

    @Volatile var relay: Boolean = true

    /** Outgoing audio codec, PCM or OPUS. Takes effect the next time the mic is keyed. */
    @Volatile var codec: Packet.Codec = Packet.Codec.OPUS

    /** Hop budget stamped on packets this phone originates; as a relay it forwards only packets still within that many hops of their origin ([Ingress.relayTtl]). */
    @Volatile var maxHops: Int = AudioConfig.DEFAULT_TTL

    val senderId: Int = Random.nextInt()
    /** The Android device name, falling back to the model: what [displayName] is unless settings say otherwise. */
    val defaultName: String = deviceName(context)
    /** What the crew sees this phone as. Read at every heartbeat, so a change shows up within a second. */
    @Volatile var displayName: String = defaultName
    val isTalking: Boolean get() = talking
    val isConnected: Boolean get() = transports.isNotEmpty()

    /**
     * True while at least one transport can actually carry a packet - a socket open, a listener
     * up, a link alive - as opposed to [isConnected], which only says a transport object exists.
     * On channel with this false, the crew is talking to nobody and the roster says so far too
     * quietly: the head count drifts to zero and nothing else changes.
     */
    val healthy: Boolean get() = transports.any { it.ready }

    @Volatile private var linksUp = true              // so the first tick with nothing up reports it
    /** The roster as last published; the UI reads this when it (re)binds. */
    val roster: List<Peer> get() = lastRoster
    /** A fresh roster with current ages, for a screen that polls. */
    val rosterNow: List<Peer> get() = buildRoster()
    /** Names of the transports running right now. */
    val activeTransports: List<String> get() = transports.map { it.name }

    private val route = AudioRoute(context, onStatus)

    /**
     * Voice-operated keying (setting `headset_vox`, and always on the earpiece route): the mic
     * is captured all the time and speech keys it, 1.5 s of quiet un-keys it ([MicGate]); the
     * last [PREROLL] frames before the gate opened go out first, so the first syllable is not
     * clipped. A muted headset is quiet, so mute means off air. A key held open for
     * [VOX_TIMEOUT_FRAMES] (steady wind or engine noise above the gate) is dropped with a status
     * line, and the gate has to close on quiet before it keys the mic again.
     */
    @Volatile var headsetVox = false
        set(value) { if (field != value) { field = value; syncMonitor() } }

    // Written under monitorLock, but read without it by the audio-control thread (openTalk) and by
    // the main thread (voiceArmed, micPeakNow): volatile is what gives those reads the writer's edge.
    @Volatile private var monitor: AudioCapture? = null
    private val gate = MicGate()
    private val preroll = ArrayDeque<ByteArray>()
    @Volatile private var gateTalking = false      // the gate keyed the mic, so the gate un-keys it
    private var voxFrames = 0                      // frames sent since the gate keyed the mic; under monitorLock

    private val monitorLock = Any()

    /**
     * On the phone's own mic the level cannot tell your voice from a shipmate's a metre away
     * (the voice-call path levels them out), so the earpiece route arms the voice gate only
     * while the proximity sensor says the phone is at your ear, as a phone call would.
     */
    @Volatile private var atEar = false
    @Volatile private var phoneMic = false
    /** True while the proximity sensor is registered, i.e. the ear is what arms the gate. */
    @Volatile private var earWatched = false
    /**
     * Setting `proximity_sensor`. Off: the sensor is never read, so the phone behaves as one without
     * it: the automatic route stays on the loudspeaker, the screen is not darkened, and the voice
     * gate on the phone's own mic runs only on the earpiece route, armed by level alone.
     */
    @Volatile var useProximity = true
        set(v) {
            if (field == v) return
            field = v
            synchronized(monitorLock) { if (monitor != null && phoneMic) watchProximity(true) }
            syncMonitor()
        }
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private val proximity = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val near = e.values[0] < (e.sensor.maximumRange.coerceAtMost(5f))
            if (near != atEar) {
                atEar = near
                route.atEar = near                                // AUTO: earpiece at the ear, loudspeaker away
                onStatus(if (near) "At the ear: voice keys the mic" else "Away from the ear")
            }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
    }
    /** Told when the engine starts or stops watching the ear; the service darkens the screen at the ear meanwhile. */
    @Volatile var onEarWatch: ((Boolean) -> Unit)? = null

    private fun watchProximity(on: Boolean) {
        val s = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)?.takeIf { useProximity }
        sensors.unregisterListener(proximity)                          // harmless when it is not registered
        val watch = on && s != null
        if (watch) {
            atEar = false                                              // the first reading decides
            sensors.registerListener(proximity, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            atEar = on                                                 // no sensor, or the setting off: the level alone arms the gate
        }
        route.atEar = false
        if (watch != earWatched) { earWatched = watch; onEarWatch?.invoke(watch) }
    }

    /** True while voice keying is armed: always with a headset, at the ear on the earpiece route. */
    val voiceArmed: Boolean get() = monitor != null && (!phoneMic || atEar)
    private val micPeak = java.util.concurrent.atomic.AtomicInteger(-1)

    /** Loudest frame (RMS, 16-bit scale) the voice gate has seen since last asked, -1 when the gate is not running; for the Status screen. */
    val micPeakNow: Int get() = if (monitor == null) -1 else micPeak.getAndSet(0)
    /** The level the voice gate opens at, for the Status screen. */
    val gateOpenRms: Int get() = gate.openRms.toInt()

    /**
     * Starts or stops the always-on headset capture as the setting, the route and the session
     * dictate. The capture's frames and this method serialise on [monitorLock], and a frame from
     * a capture that is no longer [monitor] is dropped, so a late frame after teardown cannot key
     * the mic; the capture itself is stopped outside the lock, since its worker may be waiting for it.
     */
    private fun syncMonitor() {
        while (true) {
            var toStop: AudioCapture? = null
            synchronized(monitorLock) {
                // On the phone itself the ear arms voice keying (earpiece, or auto at the ear); with a
                // Bluetooth headset it is the setting. A wired headset has a button that works, so neither.
                // Without the proximity sensor the automatic route has no ear to go by, so only the
                // earpiece route, chosen on purpose, runs the phone-mic monitor then.
                val phone = !route.headset && (route.policy == AudioRoute.Policy.EARPIECE || (route.policy == AudioRoute.Policy.AUTO && useProximity))
                val want = isConnected && !held && !asking && (phone || (headsetVox && route.bluetoothHeadset))
                val wantPhoneMic = !route.bluetoothHeadset
                // A capture tuned for the other mic is as wrong as one that should not run: a headset
                // that appears or goes away mid-session stops the monitor, and the next pass starts
                // a fresh one with the right gate once the old one has let go of the mic.
                if (monitor != null && (!want || wantPhoneMic != phoneMic)) {
                    toStop = monitor
                    monitor = null
                    if (gateTalking) { gateTalking = false; stopTalking() }
                    else if (talking) stopTalking()               // the mic that was feeding it is going away
                    gate.reset()
                    preroll.clear()
                    if (phoneMic) watchProximity(false)
                } else if (want && monitor == null) {
                    gate.reset()
                    phoneMic = wantPhoneMic
                    gate.tune(phoneMic)
                    if (phoneMic) watchProximity(true)
                    preroll.clear()
                    lateinit var m: AudioCapture
                    m = AudioCapture(
                        onFrame = { pcm -> synchronized(monitorLock) { if (monitor === m) voiceFrame(pcm) } },
                        onError = { why -> monitorFailed(m, why) }
                    )
                    monitor = m                                   // published first: the worker may call back before start() returns
                    // A talk in progress on the engine's own capture (a setting or the route changed
                    // mid-press) hands over to the monitor, which feeds sendFrame while talking: two
                    // captures would send every frame twice and fight over the mic. The capture is
                    // owned by the audio-control thread, which is told, and waited for, here.
                    onAudioCtl { capture?.stop(); capture = null }
                    try {
                        m.start()
                        onStatus(if (phoneMic && earWatched) "Voice keys the mic at the ear" else "Voice keys the mic")
                    } catch (e: Exception) {
                        monitor = null
                        if (phoneMic) watchProximity(false)
                        m.stop()                                  // start() threw, so no worker exists; frees the record
                        onStatus("Mic error: ${e.message}")
                    }
                    return
                } else return
            }
            toStop?.stop()                                        // outside the lock: its worker may be waiting for it
        }
    }

    /** One frame from the headset capture: runs the voice gate, then sends or keeps it for the pre-roll. Holds [monitorLock]. */
    private fun voiceFrame(pcm: ByteArray) {
        val rms = MicGate.rms(pcm)
        micPeak.accumulateAndGet(rms.toInt()) { a, b -> maxOf(a, b) }
        if (phoneMic && !atEar) {                                  // away from the ear: the gate is disarmed, the button still works
            if (gateTalking) { gateTalking = false; stopTalking() }
            gate.reset()
            if (talking) sendFrame(pcm)
            else keepForPreroll(pcm)
            return
        }
        when (gate.feed(rms)) {
            MicGate.Change.OPEN -> if (!talking) {
                gateTalking = true
                voxFrames = 0
                startTalking()
                if (talking) for (f in preroll) sendFrame(f)      // the syllable that opened the gate
                else gateTalking = false                          // the mic did not open; the gate owns nothing
                preroll.clear()                                   // sent, or stale by the next opening
            }
            MicGate.Change.CLOSE -> if (gateTalking) { gateTalking = false; stopTalking() }
            null -> Unit
        }
        if (gateTalking && ++voxFrames >= VOX_TIMEOUT_FRAMES) {
            // The gate stays open in its own view, so it must see quiet (CLOSE) before it can OPEN again.
            gateTalking = false
            stopTalking()
            onStatus("Voice key timed out")
        }
        if (talking) sendFrame(pcm)
        else keepForPreroll(pcm)
    }

    private fun keepForPreroll(pcm: ByteArray) {
        preroll.addLast(pcm)
        while (preroll.size > PREROLL) preroll.removeFirst()
    }

    /** A hardware talk key that reaches the engine through Telecom (a Bluetooth headset's button). Set by the service. */
    @Volatile var onTalkKey: (() -> Unit)? = null
    @Volatile private var held = false

    /**
     * True while the ask sheet has the microphone. Two `AudioRecord` clients do not share a
     * microphone, so the voice-keying monitor has to let go for the duration — and, worse, a live
     * gate would key the channel with the question the crew member is asking their own phone.
     * Treated exactly like a call hold for the mic, but the channel is still heard.
     */
    @Volatile private var asking = false

    fun setAsking(on: Boolean) {
        asking = on
        if (on) stopTalking()
        syncMonitor()
    }

    /**
     * Telecom's view of the session, only while a Bluetooth headset is the route: [AudioRoute]
     * asks for the call when such a headset appears and ends it when it goes; while the call
     * lasts Telecom routes the audio and the headset button arrives as [CallBridge.Listener.onHeadsetButton].
     */
    private val callListener = object : CallBridge.Listener {
        override fun onCallActive() { route.passive = true }
        override fun onCallEnded(reason: String?) {
            route.passive = false
            if (reason != null) onStatus(reason)
            if (isConnected) route.reapply()
        }
        override fun onHeadsetButton() { onTalkKey?.invoke() }
        override fun onHold(held: Boolean) {
            this@PttEngine.held = held
            if (held) stopTalking()
            mixer.muted = held
            onStatus(if (held) "On hold: phone call" else "Back on channel")
            syncMonitor()
        }
        override fun onAudioRoute(label: String) {
            if (label != route.current) { route.current = label; onStatus("Audio: $label") }
        }
    }

    /**
     * Register the session as a call while a Bluetooth headset is in use (setting `headset_call`).
     * Off by default: headsets differ in what their button sends, and a call makes Android drop
     * the media keys some of them send. On, for headsets that send a hang-up instead.
     */
    @Volatile var headsetAsCall = false
        set(value) {
            if (field == value) return
            field = value
            if (isConnected && route.bluetoothPresent) syncCall(true)
        }

    init {
        route.onBluetoothHeadset = { present -> syncCall(present) }
        route.onHeadsetChanged = { syncMonitor() }
    }

    /**
     * Off-thread recovery from a mic that stopped under us. The capture reports its failure from
     * its own worker, which must not be joined from inside itself, so the release and the retry
     * happen here instead; [syncMonitor] then starts a fresh capture if the session still wants one.
     */
    private val recovery: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { Thread(it, "ptt-mic-retry") }

    /**
     * The always-on voice capture stopped (the audio server restarted, or a call took the mic).
     * Drops it, un-keys anything it was keying, and tries again shortly: without this the phone
     * looks like it is on voice keying while no frame can ever arrive.
     */
    private fun monitorFailed(failed: AudioCapture, message: String) {
        val ours = synchronized(monitorLock) {
            if (monitor !== failed) false
            else { monitor = null; gateTalking = false; gate.reset(); preroll.clear(); true }
        }
        if (!ours) return
        stopTalking()
        onStatus("Voice keying stopped: $message")
        recovery.schedule({ failed.stop(); syncMonitor() }, MIC_RETRY_MS, TimeUnit.MILLISECONDS)
    }

    private fun syncCall(bluetoothPresent: Boolean) {
        if (bluetoothPresent && headsetAsCall && isConnected) {
            if (!CallBridge.start(context)) {
                route.passive = false      // Telecom would not take it (a phone call, or no telecom); route ourselves
                route.reapply()
            }
        } else CallBridge.stop()
    }
    /** Headset when connected (default) or always the speaker; applies immediately. */
    var audioRoute: AudioRoute.Policy
        get() = route.policy
        set(value) {
            route.policy = value
            CallBridge.forcedRoute = when (value) {
                AudioRoute.Policy.SPEAKER -> android.telecom.CallAudioState.ROUTE_SPEAKER
                AudioRoute.Policy.EARPIECE -> android.telecom.CallAudioState.ROUTE_EARPIECE
                AudioRoute.Policy.AUTO -> null
            }
            syncMonitor()
        }
    /** Where the voice is going right now, for the Status screen. */
    val audioRouteNow: String get() = route.current
    // A playback track that died and was rebuilt says so on the status line.
    private val mixer = Mixer().also { it.onStatus = onStatus }

    /**
     * The user's mute: received speech is silenced in the mixer (a gain of 0), the cue tones stay,
     * and no system stream is touched. Session state: cleared when the phone leaves the channel.
     * The level itself is the phone's call volume, set by the main screen's slider ([CallVolume]).
     */
    @Volatile var muted = false
        set(v) { field = v; applyGain() }

    /**
     * Quietens the channel while this phone is being spoken to by [fi.crewradio.ask.AskController],
     * so an answer in the ear is not buried under somebody else's transmission. Session state, and
     * never louder than the mute: a muted phone stays muted while it is being answered.
     */
    @Volatile private var ducked = false

    fun duck(on: Boolean) {
        ducked = on
        applyGain()
    }

    private fun applyGain() {
        mixer.gain = if (muted) 0f else if (ducked) DUCK_GAIN else 1f
    }

    /** True while a Bluetooth headset carries the audio; the slider picks its stream by it. */
    val bluetoothHeadsetNow: Boolean get() = route.bluetoothHeadset
    private val transports = CopyOnWriteArrayList<Transport>()

    // ---- talk state ---------------------------------------------------------------
    //
    // `talking` flips under talkLock on the caller's thread; everything that blocks (the mic,
    // the encoder) is owned by the audio-control thread, which runs the opens and closes in
    // the order the calls were made, so start-stop-start ends with one open capture. Frames
    // captured before the encoder is up wait in `pending` and go out first, in order, under
    // sendLock, which also serialises the encoder's use against its release.
    private val talkLock = Any()
    private val sendLock = Any()
    private val audioCtl: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "ptt-audio-ctl") }
    @Volatile private var talking = false
    private var talkGen = 0                            // under talkLock: which start a failed open belongs to
    private var armed = false                          // under talkLock: the encoder for the current talk is up
    private val pending = ArrayDeque<ByteArray>()      // under talkLock: frames captured before armed
    private var capture: AudioCapture? = null          // audio-control thread only
    private var encoder: OpusEncoder? = null           // under sendLock

    private val decoders = ConcurrentHashMap<Int, OpusDecoder>()
    private val decodeFailures = ConcurrentHashMap<Int, Int>()      // consecutive decode errors per sender
    private val undecodable = ConcurrentHashMap<Int, Long>()        // sender -> when (monotonic ms) to try decoding it again
    private val packetCount = AtomicInteger()
    private val audioSeq = AtomicInteger()                           // one per frame, so a gap in it is lost audio
    private val helloSeq = AtomicInteger()                           // hellos count separately; they are not frames
    /**
     * Packet counters for one session. connect() starts a fresh set; a callback from a
     * transport that was still winding down keeps incrementing the old set, which nothing
     * reads any more - so the counters can never be reset underneath a running callback.
     */
    private class Counters {
        val rxPackets = AtomicLong()
        val rxBytes = AtomicLong()
        val txPackets = AtomicLong()
        val txBytes = AtomicLong()
        val relayed = AtomicLong()
        val duplicates = AtomicLong()
        val hellos = AtomicLong()
        val rejected = AtomicLong()
        val stale = AtomicLong()
        fun snapshot(concealed: Long, underruns: Long) = Stats(
            rxPackets.get(), rxBytes.get(), txPackets.get(), txBytes.get(), relayed.get(), duplicates.get(),
            hellos.get(), rejected.get(), concealed, stale.get(), underruns
        )
    }
    @Volatile private var counters = Counters()

    private val nodes = ConcurrentHashMap<Int, Node>()
    /** The receive pipeline: budgets, AEAD, timestamp, seen-caches, sequence marks. Lives with the engine, not the session. */
    private val ingress = Ingress()
    private val staleReportedAt = AtomicLong(-REPORT_INTERVAL_MS)
    private val junkReportedAt = AtomicLong(-REPORT_INTERVAL_MS)
    @Volatile private var heartbeatFailed = false

    /** Seals and opens every packet; null until [channelKey] is set, and then nothing is sent or accepted either. */
    @Volatile var crypto: ChannelCrypto? = null
        private set
    private var cryptoFor: String? = null

    /**
     * The crew's channel key. Deriving the packet key from it takes about a second of CPU
     * ([ChannelCrypto.ITERATIONS]), so the setter is memoised per value and must be called off
     * the main thread; [crypto] is ready when it returns.
     */
    var channelKey: String = ""
        set(value) {
            if (value == field && cryptoFor == value) return
            field = value
            crypto = if (value.isEmpty()) null else ChannelCrypto.forChannelKey(value)
            cryptoFor = value
        }
    private var heartbeat: ScheduledExecutorService? = null
    @Volatile private var lastRoster: List<Peer> = emptyList()
    private var lastRosterKey = ""

    /** Everything we know about one sender; refreshed by its hellos and its audio. */
    private class Node {
        @Volatile var name: String? = null
        @Volatile var transports = 0
        @Volatile var via = "?"
        @Volatile var hops = 0
        @Volatile var versionCode = 0
        @Volatile var lastSeen = 0L
        @Volatile var lastHello = 0L
        @Volatile var lastAudio = 0L
        @Volatile var talking = false
        val link = LinkQuality()
    }

    /**
     * Starts playback and the given transports; any transport that fails to start is reported
     * and dropped. Blocks while the previous session, if any, winds down: call it from the
     * session thread.
     */
    fun connect(list: List<Transport>) {
        disconnect()
        counters = Counters()
        heartbeatFailed = false
        CallBridge.listener = callListener
        route.start()
        mixer.start()
        for (t in list) {
            transports.add(t)
            try {
                t.start(::onPacket, onStatus)
            } catch (e: Exception) {
                // A transport that never started would silently swallow every frame we hand it.
                onStatus("${t.name} failed: ${e.message}")
                transports.remove(t)
                try { t.stop() } catch (_: Exception) {}
            }
        }
        heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "ptt-heartbeat") }.also {
            // A periodic task that throws is cancelled by the executor for good, and this phone
            // would drop off every roster while it can still talk: catch everything, say so once.
            // A fixed delay, not a fixed rate: after a doze the heartbeat should resume, not fire
            // a burst of catch-up hellos at once.
            it.scheduleWithFixedDelay({
                try {
                    tick()
                } catch (e: Throwable) {
                    if (!heartbeatFailed) { heartbeatFailed = true; onStatus("Heartbeat error: ${e.message}") }
                }
            }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        }
        syncMonitor()
    }

    /**
     * Stops everything and releases codecs; safe to call when already idle. Waits, briefly, for
     * the mic to be released, so the caller may drop the foreground microphone afterwards. The
     * seen-caches and sequence marks are kept on purpose: a recording of the last session must
     * not open here. Call it from the session thread.
     */
    fun disconnect() {
        val m = synchronized(monitorLock) { monitor.also { monitor = null; gateTalking = false; if (phoneMic) watchProximity(false) } }
        m?.stop()
        stopTalking()
        onAudioCtl { }                                  // the close above has run when this returns
        heartbeat?.shutdownNow()
        heartbeat = null
        for (t in transports) t.stop()
        transports.clear()
        mixer.stop()
        releaseDecoders()
        nodes.clear()
        publishRoster()
        CallBridge.stop()                               // end the Telecom call before the route it was using goes
        route.stop()
        CallBridge.listener = null
        mixer.muted = false
        held = false
        asking = false
        muted = false
        ducked = false
    }

    fun stats(): Stats = counters.snapshot(mixer.concealedFrames.get(), mixer.underrunFrames.get())

    /**
     * Keys the mic. The state flips here, at once; the encoder (if Opus) and the capture are
     * opened on the audio-control thread, and if the mic cannot be opened the key is dropped
     * again and reported. Returns without blocking.
     */
    fun startTalking() {
        val gen: Int
        synchronized(talkLock) {
            if (talking || transports.isEmpty() || held || asking) return
            talking = true
            armed = false
            pending.clear()
            gen = ++talkGen
        }
        audioCtl.execute { openTalk(gen) }
    }

    /** Audio-control thread: opens the encoder and, unless the monitor feeds us, the mic; then lets the waiting frames go. */
    private fun openTalk(gen: Int) {
        val enc = if (codec == Packet.Codec.OPUS) {
            try {
                OpusEncoder { broadcast(Packet.Codec.OPUS, it) }
            } catch (e: Exception) {
                onStatus("Opus encoder unavailable, sending PCM")
                null
            }
        } else null
        var cap: AudioCapture? = null
        if (monitor == null) {             // otherwise the always-on capture feeds sendFrame while talking
            cap = AudioCapture(
                onFrame = { pcm -> sendFrame(pcm) },
                // Reported from the capture's own worker, so the un-key goes to another thread:
                // stopTalking() ends with a join of exactly that worker.
                onError = { why -> onStatus("Mic stopped: $why"); recovery.execute { stopTalking() } }
            )
            try {
                cap.start()
            } catch (e: Exception) {
                cap.stop()                 // frees an AudioRecord that was created but never started
                enc?.release()
                synchronized(talkLock) {
                    if (talkGen == gen && talking) { talking = false; gateTalking = false; pending.clear() }
                }
                onStatus("Mic error: ${e.message}")
                return
            }
        }
        capture = cap
        val drain: List<ByteArray>
        synchronized(sendLock) {
            encoder = enc
            synchronized(talkLock) {
                armed = talking
                drain = if (talking) pending.toList() else emptyList()
                pending.clear()
            }
            for (f in drain) encodeAndSend(f)
        }
        if (talking) onStatus(if (mode == Mode.FULL_DUPLEX) "Mic on" else "Transmitting")
    }

    /**
     * One captured frame out, in capture order: through the Opus encoder when there is one,
     * else raw; kept back while the encoder is still being opened, dropped once un-keyed.
     */
    private fun sendFrame(pcm: ByteArray) {
        synchronized(sendLock) {
            synchronized(talkLock) {
                if (!talking) return
                if (!armed) {
                    pending.addLast(pcm)
                    while (pending.size > MAX_PENDING) pending.removeFirst()
                    return
                }
            }
            encodeAndSend(pcm)
        }
    }

    /** Holds [sendLock]. */
    private fun encodeAndSend(pcm: ByteArray) {
        val e = encoder
        if (e == null) {
            broadcast(Packet.Codec.PCM, pcm)
        } else {
            try {
                e.encode(pcm)
            } catch (ex: Exception) {
                encoder = null
                e.release()
                onStatus("Opus failed (${ex.message}), sending PCM")
                broadcast(Packet.Codec.PCM, pcm)
            }
        }
    }

    /** Un-keys the mic at once; the capture and encoder are released on the audio-control thread. Returns without blocking. */
    fun stopTalking() {
        synchronized(talkLock) {
            if (!talking) return
            talking = false
            gateTalking = false
            pending.clear()
        }
        audioCtl.execute { closeTalk() }
    }

    /** Audio-control thread: the capture first, outside sendLock (its worker may be waiting for that), then the encoder. */
    private fun closeTalk() {
        capture?.stop()
        capture = null
        synchronized(sendLock) {
            synchronized(talkLock) { armed = false }
            encoder?.release()
            encoder = null
        }
        onStatus(if (mode == Mode.FULL_DUPLEX) "Mic off" else "Listening")
    }

    /** Runs [work] on the audio-control thread and waits for it, bounded; a report if it did not finish in time. */
    private fun onAudioCtl(work: () -> Unit) {
        try {
            audioCtl.submit(work).get(AUDIO_CTL_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            onStatus("Audio control slow: ${e.javaClass.simpleName}")
        }
    }

    /** Full-duplex mic toggle. */
    fun toggleTalking() = if (talking) stopTalking() else startTalking()

    /** Plays a short cue (see [fi.crewradio.audio.Tones]) in the ear, or the speaker; nothing when not on channel. */
    fun cue(frames: List<ByteArray>) = mixer.cue(frames)

    /** Stamps (id, sequence, hop budget, clock) and sends one of our own packets on every transport. */
    private fun broadcast(codec: Packet.Codec, payload: ByteArray) {
        val cr = crypto ?: return                                   // no key, nothing goes on the air
        val s = (if (codec == Packet.Codec.HELLO) helloSeq else audioSeq).getAndIncrement()
        val header = Packet.encode(senderId, s, codec, maxHops, ByteArray(0), time = System.currentTimeMillis() / 1000)
        val packet = header + cr.seal(Packet.aadOf(header), payload)
        val c = counters
        c.txPackets.incrementAndGet()
        c.txBytes.addAndGet(packet.size.toLong())
        for (t in transports) t.send(packet)
    }

    /** Receive path for every transport: [Ingress] decides, then relay within the hop budget, then roster, then decode and play. */
    private fun onPacket(p: ByteArray, from: Transport, link: Any?) {
        if (transports.isEmpty()) return                  // a transport still winding down after disconnect
        val c = counters                                  // this session's set, whatever happens meanwhile
        val h = Packet.parse(p) ?: run { c.rejected.incrementAndGet(); return }
        val cr = crypto ?: return
        val now = SystemClock.elapsedRealtime()
        val plain: ByteArray
        val relayTtl: Int
        when (val r = ingress.admit(
            h, now, System.currentTimeMillis() / 1000, maxHops,
            { cr.open(Packet.aadOf(p), p, Packet.HEADER, p.size - Packet.HEADER) },
            selfId = senderId
        )) {
            is Ingress.Result.Accept -> { plain = r.plain; relayTtl = r.relayTtl }
            // Heard already, but this copy reaches further than the one we forwarded: relay it and
            // nothing else. Whoever sent the shorter copy first does not get to cut the mesh in two.
            is Ingress.Result.RelayOnly -> {
                c.duplicates.incrementAndGet()
                relayPacket(p, r.relayTtl, from, link, c)
                return
            }
            Ingress.Result.Duplicate -> { c.duplicates.incrementAndGet(); return }
            Ingress.Result.Stale -> {
                c.stale.incrementAndGet()
                reportOnce(staleReportedAt, now) { "Clock: ${c.stale.get()} packets more than ${Packet.REPLAY_WINDOW_S} s off" }
                return
            }
            is Ingress.Result.Rejected -> {
                c.rejected.incrementAndGet()
                if (r.why == Ingress.Why.JUNK_FLOOD) reportOnce(junkReportedAt, now) { "Unreadable packets: another key, or a flood" }
                return
            }
        }
        c.rxPackets.incrementAndGet()
        c.rxBytes.addAndGet(p.size.toLong())
        from.confirmPeer(link)                            // the key opened it, so the address is a crew member's

        relayPacket(p, relayTtl, from, link, c)

        if (h.codec == Packet.Codec.HELLO) {
            c.hellos.incrementAndGet()
            val gap = ingress.helloGap(h.senderId, h.seq)
            Hello.decode(plain, 0, plain.size)?.let { heardHello(h.senderId, it, from, h.ttl, gap) }
            return
        }
        val node = heardAudio(h.senderId, from)

        if ((packetCount.incrementAndGet() and 0xFF) == 0) pruneDecoders()
        val playing = !(mode == Mode.HALF_DUPLEX && talking)   // radio semantics: not while we transmit

        // A gap before this frame is lost audio: its slots are reserved in the mixer atomically
        // with the admission, and the link meter is told. A late frame is dropped, its slot was
        // concealed already.
        if (!ingress.admitAudio(h.senderId, h.seq) { gap ->
                node?.link?.audioLost(gap)
                if (playing && gap <= Conceal.MAX_FRAMES) mixer.conceal(h.senderId, gap)
            }) return
        node?.link?.audioHeard()
        if (!playing) return

        when (h.codec) {
            Packet.Codec.PCM -> mixer.push(h.senderId, plain, 0, plain.size)
            Packet.Codec.OPUS -> decodeOpus(h.senderId, plain)
            Packet.Codec.HELLO -> Unit                        // handled above
        }
    }

    /**
     * Forwards an authenticated packet with the ttl [Ingress] worked out, to every other transport
     * and, where forwarding within one makes sense, to its other links. A ttl of 0 or a relay
     * switched off means it stops here.
     */
    private fun relayPacket(p: ByteArray, relayTtl: Int, from: Transport, link: Any?, c: Counters) {
        if (!relay || relayTtl <= 0) return
        Packet.setTtl(p, relayTtl)
        var forwarded = false
        for (t in transports) {
            if (t === from) { if (t.relayWithin && t.send(p, except = link)) forwarded = true }
            else if (t.send(p)) forwarded = true
        }
        if (forwarded) c.relayed.incrementAndGet()
    }

    /** A status line for a condition that recurs on every packet: at most once per [REPORT_INTERVAL_MS], whichever thread sees it. */
    private fun reportOnce(last: AtomicLong, now: Long, message: () -> String) {
        val prev = last.get()
        if (now - prev >= REPORT_INTERVAL_MS && last.compareAndSet(prev, now)) onStatus(message())
    }

    // ---- roster -------------------------------------------------------------------

    /** Heartbeat thread: announce ourselves, drop the silent, clear stale talking marks, publish if anything moved. */
    private fun tick() {
        sendHello()
        val up = healthy
        if (up != linksUp) {
            linksUp = up
            onStatus(if (up) "Links up" else "No link is up: nobody can hear this phone")
        }
        val now = SystemClock.elapsedRealtime()
        var changed = false
        for ((id, n) in nodes) {
            if (now - n.lastSeen > PEER_TIMEOUT_MS) {
                if (nodes.remove(id, n)) changed = true
            } else if (n.talking && now - n.lastAudio > TALK_HOLD_MS) {
                n.talking = false
                changed = true
            }
        }
        // Always: a hello overdue since the last publish has moved a link level even when nothing
        // was dropped, and publishRoster hands out nothing when the rendered list is the same.
        publishRoster()
    }

    private fun sendHello() {
        var flags = 0
        // Only what can actually carry a packet: a Bluetooth transport whose adapter is off is
        // started but not ready, and claiming BT on the roster then sends the crew looking for a
        // link that cannot exist. The flag has always been there; nothing read it.
        for (t in transports) if (t.ready) flags = flags or Hello.bitFor(t.name)
        broadcast(Packet.Codec.HELLO, Hello(displayName, flags, maxHops, BuildConfig.VERSION_CODE).encode())
    }

    /**
     * [ttlLeft] is what arrived on the header; every relay decrements from what it received, so the
     * difference is the hops travelled. [gap] is how many of the sender's hellos went missing before
     * this one ([Ingress.helloGap]), for the link meter.
     */
    private fun heardHello(id: Int, hello: Hello, from: Transport, ttlLeft: Int, gap: Int) {
        val n = nodeFor(id) ?: return
        n.name = hello.name.ifBlank { null }
        n.transports = hello.transports
        n.versionCode = hello.versionCode
        n.via = from.name
        n.hops = (hello.ttl - ttlLeft).coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()
        n.lastSeen = now
        n.lastHello = now
        n.link.helloHeard(gap)
        publishRoster()
    }

    /**
     * Audio is proof of life too, and lights the talking mark; the roster is only republished when
     * that flips. Returns the node, so the caller can tell its link meter about the frame once it
     * is admitted, or null when the roster is full.
     */
    private fun heardAudio(id: Int, from: Transport): Node? {
        val n = nodeFor(id) ?: return null
        val now = SystemClock.elapsedRealtime()
        n.lastSeen = now
        n.lastAudio = now
        n.via = from.name
        if (!n.talking) {
            n.talking = true
            publishRoster()
        }
        return n
    }

    /**
     * The entry for a sender, created on first sight — unless the roster is already full, in
     * which case an unknown sender is ignored. Sender ids are unauthenticated, so without a cap
     * anyone in radio range could grow the list without bound by cycling ids faster than the
     * 4 s expiry. The check and the insert are not one atomic step; a few over the cap is fine.
     */
    private fun nodeFor(id: Int): Node? =
        nodes[id] ?: if (nodes.size >= MAX_NODES) null else nodes.computeIfAbsent(id) { Node() }

    private fun buildRoster(): List<Peer> {
        val now = SystemClock.elapsedRealtime()
        return nodes.entries
            .map { (id, n) ->
                // A node heard only by its audio so far has no hello to be overdue; its last packet stands in.
                val sinceHello = now - (if (n.lastHello != 0L) n.lastHello else n.lastSeen)
                Peer(id, n.name, n.transports, n.via, n.hops, n.talking, now - n.lastSeen, n.versionCode,
                    n.link.level(sinceHello, now - n.lastAudio))
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Rebuilds the list and hands it out only if it differs from the last one published (ages do
     * not count; the link level does, and the heartbeat tick republishes when an overdue hello
     * has moved it).
     */
    @Synchronized
    private fun publishRoster() {
        val list = buildRoster()
        val key = list.joinToString("|") { "${it.id}/${it.name}/${it.transports}/${it.via}/${it.hops}/${it.talking}/${it.versionCode}/${it.level}" }
        if (key == lastRosterKey) return
        lastRosterKey = key
        lastRoster = list
        onRoster(list)
    }

    // ---- codecs -------------------------------------------------------------------

    /**
     * Feeds one Opus packet to the sender's decoder and plays whatever frames come out. A sender
     * whose decoder cannot be created, or fails [MAX_DECODE_FAILURES] times running, is left
     * alone for [UNDECODABLE_MS] (reported once) rather than given a fresh MediaCodec 50 times a
     * second; a decode that works clears its count.
     */
    private fun decodeOpus(sender: Int, p: ByteArray) {
        undecodable[sender]?.let { until ->
            if (SystemClock.elapsedRealtime() < until) return
            undecodable.remove(sender, until)
        }
        val dec = try {
            decoderFor(sender) ?: return                     // over capacity and everyone is talking, or disconnecting: drop
        } catch (e: Exception) {
            giveUp(sender, "Opus decoder unavailable")
            return
        }
        synchronized(dec) {
            try {
                dec.decode(p, 0, p.size) { frame ->
                    mixer.push(sender, frame, 0, frame.size)
                }
                decodeFailures.remove(sender)
            } catch (e: Exception) {
                if (decoders.remove(sender, dec)) dec.release()   // only ours: another thread may have replaced it
                val n = (decodeFailures[sender] ?: 0) + 1
                if (n >= MAX_DECODE_FAILURES) {
                    decodeFailures.remove(sender)
                    giveUp(sender, "Opus decode error: ${e.message}")
                } else decodeFailures[sender] = n
            }
        }
    }

    private fun giveUp(sender: Int, why: String) {
        if (undecodable.size >= MAX_UNDECODABLE) undecodable.clear()
        undecodable[sender] = SystemClock.elapsedRealtime() + UNDECODABLE_MS
        onStatus("$why, ${sender.toUInt().toString(16)} muted ${UNDECODABLE_MS / 1000} s")
    }

    /**
     * The sender's decoder, created on demand. Decoders are a bounded resource (each is a
     * MediaCodec), so at most [MAX_DECODERS] exist; a new sender beyond that evicts the
     * quietest one if it has paused, and is dropped if every slot is actively talking. A packet
     * still in flight while [disconnect] runs must not leave a codec behind in a map that has
     * just been emptied, so one created after the transports went is released at once.
     */
    private fun decoderFor(sender: Int): OpusDecoder? {
        decoders[sender]?.let { return it }
        if (decoders.size >= MAX_DECODERS && !evictQuietest()) return null
        val dec = decoders.computeIfAbsent(sender) { OpusDecoder() }   // atomic: never two codecs for one sender
        if (transports.isEmpty() && decoders.remove(sender, dec)) {
            synchronized(dec) { dec.release() }
            return null
        }
        return dec
    }

    /** Releases the decoder that has been quiet longest, if it has paused at all. */
    private fun evictQuietest(): Boolean {
        val (sender, dec) = decoders.entries.minByOrNull { it.value.lastUsedNs } ?: return false
        if (System.nanoTime() - dec.lastUsedNs < EVICT_IDLE_NS) return false
        if (decoders.remove(sender, dec)) synchronized(dec) { dec.release() }
        return true
    }

    /** Releases decoders of senders that have been silent for a while; they are recreated on demand. */
    private fun pruneDecoders() {
        val now = System.nanoTime()
        for ((sender, dec) in decoders) {
            if (now - dec.lastUsedNs > DECODER_IDLE_NS && decoders.remove(sender, dec)) {
                synchronized(dec) { dec.release() }
            }
        }
    }

    /** Drops every decoder and the failed-sender lists, on disconnect. */
    private fun releaseDecoders() {
        for ((sender, dec) in decoders) {
            if (decoders.remove(sender, dec)) synchronized(dec) { dec.release() }
        }
        decodeFailures.clear()
        undecodable.clear()
    }

    private companion object {
        /**
         * How far the channel is turned down while this phone is being answered: quiet enough to
         * hear the answer over, loud enough that a call for help on the channel still gets through.
         */
        const val DUCK_GAIN = 0.25f
        /** Frames kept from before the voice gate opened and sent first: 100 ms. */
        const val PREROLL = 5
        /** Frames held back while the encoder opens (the pre-roll and a few more); older ones are dropped. */
        const val MAX_PENDING = 25
        /** How long the voice gate may hold the key: 60 s of frames. */
        const val VOX_TIMEOUT_FRAMES = 60_000 / AudioConfig.FRAME_MS
        /** How long after a mic failure the always-on capture is tried again. */
        const val MIC_RETRY_MS = 2_000L
        const val AUDIO_CTL_WAIT_MS = 2_000L
        const val DECODER_IDLE_NS = 30_000_000_000L
        const val EVICT_IDLE_NS = 2_000_000_000L
        const val MAX_DECODERS = 8            // a crew, not a crowd; each one is a MediaCodec instance
        const val MAX_UNDECODABLE = 64
        const val MAX_DECODE_FAILURES = 3
        const val UNDECODABLE_MS = 10_000L
        /** How often a recurring condition (stale packets, unreadable packets) is put on the status line. */
        const val REPORT_INTERVAL_MS = 30_000L

        const val TICK_MS = 1_000L            // hello cadence; also how often talking marks and timeouts are checked
        const val PEER_TIMEOUT_MS = 4_000L    // three missed hellos and a bit
        const val TALK_HOLD_MS = 400L         // how long after the last frame a peer still shows as talking
        const val MAX_NODES = 64              // far more than a crew; a ceiling, not a target

        /** The name Android shows in Settings > About, which the user can change; the model otherwise. */
        fun deviceName(context: Context): String =
            try { Settings.Global.getString(context.contentResolver, "device_name") } catch (_: Exception) { null }
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
