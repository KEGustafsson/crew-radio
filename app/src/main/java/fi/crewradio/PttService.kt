package fi.crewradio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.IntentCompat
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider
import android.net.wifi.WifiManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import fi.crewradio.audio.Tones
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.crewradio.transport.Transport
import java.util.concurrent.Executors

/**
 * Foreground service that owns the [PttEngine] while connected, so the
 * intercom keeps running with the screen off or the activity gone.
 *
 * Lifecycle: the activity binds while visible (BIND_AUTO_CREATE), so the
 * service exists whenever the UI is up. [connect] additionally promotes it to
 * a started foreground service with a partial wake lock and a low-latency
 * Wi-Fi lock; [disconnect] releases all of that and stops the service, which
 * then dies as soon as the activity unbinds.
 *
 * Joining and leaving run on the service's own `ptt-session` thread: stretching the channel key
 * into the packet key takes about a second, and [PttEngine.connect]/[PttEngine.disconnect] wait
 * for transport threads. Only the foreground promotion stays on the main thread, inside [connect],
 * so the platform's ten seconds to call `startForeground` are never at risk. The transports
 * themselves are built by a factory that runs on that thread, after the key is ready, because
 * Wi-Fi Aware's secrets come from it.
 *
 * The ongoing notification mirrors the engine's status line and carries a
 * Disconnect action, so the session can be ended without reopening the app.
 */
class PttService : Service() {

    inner class LocalBinder : Binder() {
        val service: PttService get() = this@PttService
    }

    lateinit var engine: PttEngine
        private set

    /** Last status string from the engine; the activity shows it when it (re)binds. */
    @Volatile var lastStatus: String = ""
        private set

    /** Set by the bound activity. Called on whichever thread reported the status. */
    @Volatile var statusListener: ((String) -> Unit)? = null

    private val log = ArrayDeque<String>()

    /** The last [LOG_LINES] status lines with a time stamp, oldest first; for the Status screen. */
    val statusLog: List<String> get() = synchronized(log) { log.toList() }

    /** Last roster the engine published; the activity shows it when it (re)binds. */
    @Volatile var lastRoster: List<Peer> = emptyList()
        private set

    /** Set by the bound activity. Called on the engine's heartbeat or a transport thread. */
    @Volatile var rosterListener: ((List<Peer>) -> Unit)? = null

    private val binder = LocalBinder()
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Everything that joins or leaves the channel; see the class comment. */
    private val session = Executors.newSingleThreadExecutor { Thread(it, "ptt-session") }
    /** Screen off while the phone is at the ear, as in a call, so a cheek cannot press the talk button. */
    private var earLock: PowerManager.WakeLock? = null
    private val lockObject = Any()

    /**
     * The proximity lock. Taken and released from the engine's callback on the main thread and
     * from [releaseLocks] on the session thread, hence the monitor.
     */
    // WakelockTimeout: a proximity lock lasts exactly as long as the phone is at the ear, which is
    // not a duration this can guess. Wakelock: a try/finally here would release it on the way out
    // of the acquiring call; it is released by the engine's next callback and by releaseLocks().
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun earWatch(on: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        synchronized(lockObject) {
            if (on) {
                if (earLock == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    earLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ptt:ear").also {
                        it.setReferenceCounted(false)
                        it.acquire()
                    }
                }
            } else {
                earLock?.let { if (it.isHeld) it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
                earLock = null
            }
        }
    }
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()

    /**
     * An EMM changed the managed configuration. Nothing is cached across this: [Prefs] reads the
     * restrictions when it is constructed, so re-reading the talk-button setting is enough here and
     * the activity picks the rest up on its next resume.
     */
    private val restrictionsChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshHardwareButtons()
            onStatus(getString(R.string.status_managed_changed))
        }
    }

    /** Creates the engine and the notification channel; the engine lives as long as the service. */
    override fun onCreate() {
        super.onCreate()
        lastStatus = getString(R.string.status_not_connected)
        engine = PttEngine(this, ::onStatus, ::onRoster)
        engine.onEarWatch = { on -> mainHandler.post { earWatch(on) } }
        createChannel()
        // Not deliverable to a manifest receiver, so it is registered for as long as the service lives.
        ContextCompat.registerReceiver(
            this, restrictionsChanged,
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Hands the activity a local binder; the service is in-process only. */
    override fun onBind(intent: Intent?): IBinder = binder

    /** Handles the notification's Disconnect action; plain starts (from [connect]) need no work here. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) disconnect()
        // A restart after the process was killed has no transports to resume; stay dead.
        return START_NOT_STICKY
    }

    /** Last line of defence: tear the session down if the system destroys the service. */
    override fun onDestroy() {
        unregisterReceiver(restrictionsChanged)
        session.shutdown()          // never shutdownNow: a teardown in flight must finish
        engine.disconnect()
        releaseLocks()
        super.onDestroy()
    }

    /**
     * Joins the channel. Call from the foreground UI: the foreground promotion happens here, on
     * the caller's thread, and everything slow is handed to the session thread — the channel key
     * (about a second of PBKDF2), the transports the [factory] builds from the engine once its
     * [PttEngine.crypto] is ready, and [PttEngine.connect] itself.
     */
    fun connect(factory: (PttEngine) -> List<Transport>) {
        // Started + foreground so the service outlives the activity's unbind.
        ContextCompat.startForegroundService(this, Intent(this, PttService::class.java))
        val connecting = getString(R.string.status_connecting)
        try {
            showForeground(connecting)
        } catch (e: Exception) {   // e.g. ForegroundServiceStartNotAllowedException when not in the foreground
            stopSelf()
            onStatus(getString(R.string.status_cant_start, e.message))
            return
        }
        acquireLocks()
        onStatus(connecting)
        val key = Prefs(this).channelKey
        session.execute {
            try {
                engine.channelKey = key
            } catch (e: Exception) {
                onStatus(getString(R.string.status_key_failed, e.message))
                return@execute
            }
            val list = try {
                factory(engine)
            } catch (e: Exception) {
                onStatus(getString(R.string.status_cant_start, e.message))
                emptyList()
            }
            if (list.isEmpty()) {
                onStatus(getString(R.string.status_no_transport))
                return@execute
            }
            reportedBuilds.clear()
            engine.connect(list)
            mainHandler.post {
                refreshHardwareButtons()
                statusListener?.invoke(lastStatus)
            }
        }
    }

    /** Leaves the channel, releases the locks and drops the foreground; safe to call when already idle. */
    fun disconnect() {
        val wasConnected = engine.isConnected
        stopHardwareButtons()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        session.execute {
            engine.disconnect()
            releaseLocks()
            if (wasConnected) onStatus(getString(R.string.status_disconnected))
            mainHandler.post { statusListener?.invoke(lastStatus) }
            stopSelf()          // last, so the service is not destroyed out from under the teardown
        }
    }

    /** Engine status sink: remembers the line, forwards it to the UI and mirrors it in the notification. */
    private fun onStatus(msg: String) {
        lastStatus = msg
        synchronized(log) {
            log.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()) + "  " + msg)
            while (log.size > LOG_LINES) log.removeFirst()
        }
        statusListener?.invoke(msg)
        if (engine.isConnected) postNotification()
    }

    /**
     * Engine roster sink: remembers the list, forwards it to the UI and puts the head count in the
     * notification title. A crew member on a different build is worth one line in the log, once:
     * the wire format has no legacy mode, so an "OLD BUILD" mark on the roster is the warning and
     * this is how it reaches the status log too.
     */
    private fun onRoster(peers: List<Peer>) {
        lastRoster = peers
        rosterListener?.invoke(peers)
        for (p in peers) {
            if (p.versionCode == 0 || p.versionCode == BuildConfig.VERSION_CODE) continue
            synchronized(reportedBuilds) {
                if (reportedBuilds.size >= MAX_REPORTED_BUILDS || !reportedBuilds.add(p.id)) return@synchronized
                onStatus(getString(R.string.status_other_build, p.label))
            }
        }
        if (engine.isConnected) postNotification()
    }

    /** Ids already named in the log as running another build, so it is said once per peer per session. */
    private val reportedBuilds = HashSet<Int>()

    // ---- hardware talk button --------------------------------------------------------

    /**
     * Keys the mic from physical buttons while on channel, screen on or off. A MediaSession
     * in the playing state receives headset and media buttons; routing its volume to a remote
     * VolumeProvider makes the volume keys arrive as well, which is the only way an app gets
     * them with the screen off. Both toggle the mic: a headset click is a click, and holding
     * a volume key just repeats it. A short buzz confirms on, a double buzz confirms off.
     * Re-read the setting with [refreshHardwareButtons]; released on disconnect.
     */
    fun refreshHardwareButtons() {
        val mode = Prefs(this).hwButton
        if (!engine.isConnected || mode == Prefs.HW_OFF) { stopHardwareButtons(); return }
        val headset = mode == Prefs.HW_HEADSET || mode == Prefs.HW_BOTH
        val volume = mode == Prefs.HW_VOLUME || mode == Prefs.HW_BOTH
        val session = mediaSession ?: MediaSession(this, "CrewRadio").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val ev = keyEvent(intent) ?: return false
                    if (!headsetButtons) return false
                    val ours = when (ev.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> true
                        else -> false
                    }
                    if (!ours) return false
                    when (ev.action) {
                        KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) headsetPress()
                        KeyEvent.ACTION_UP -> headsetRelease()
                    }
                    return true
                }
            })
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            mediaSession = s
        }
        headsetButtons = headset
        // A Bluetooth headset's button comes through Telecom as one press, no release: a plain toggle.
        engine.onTalkKey = { if (headsetButtons) synchronized(hardwareLock) { setMic(!engine.isTalking) } }
        if (volume) {
            session.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) volumePress()             // 0 is the system re-reading the level
                }
            })
        } else {
            session.setPlaybackToLocal(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
        }
        session.isActive = true
    }

    private fun stopHardwareButtons() {
        engine.onTalkKey = null
        mediaSession?.let { it.isActive = false; it.release() }
        mediaSession = null
    }

    @Volatile private var headsetButtons = false
    /** Play [Tones] in the ear on a talk-key change (setting `cue_tones`). */
    @Volatile var cueTones = false
    private val hardwareLock = Any()
    private var lastVolumeEventMs = 0L
    private var headsetDownMs = 0L
    private var headsetKeyed = false

    /**
     * Headset or media button, which reports press and release separately. A press keys the
     * mic if it is off, or un-keys it if it is on; a release after a real hold ([HOLD_MS])
     * un-keys it again, so the button works as push-to-talk on headsets that hold, and as a
     * toggle on those that only click. No debounce: the events are clean already.
     */
    private fun headsetPress() {
        val now = SystemClock.elapsedRealtime()
        synchronized(hardwareLock) {
            if (now - headsetDownMs < BOUNCE_MS) return
            headsetDownMs = now
            headsetKeyed = !engine.isTalking
            setMic(headsetKeyed)
        }
    }

    private fun headsetRelease() {
        val now = SystemClock.elapsedRealtime()
        synchronized(hardwareLock) {
            if (headsetKeyed && engine.isTalking && now - headsetDownMs >= HOLD_MS) setMic(false)
            headsetKeyed = false
        }
    }

    /**
     * Volume key through the remote volume provider, which reports adjustments only, no
     * release, and autorepeats while held. Every event stamps the clock and only one after
     * a quiet [PRESS_GAP_MS] counts, so a hold is one press: mic on, and the next press off.
     */
    private fun volumePress() {
        val now = SystemClock.elapsedRealtime()
        synchronized(hardwareLock) {
            val quiet = now - lastVolumeEventMs >= PRESS_GAP_MS
            lastVolumeEventMs = now
            if (!quiet) return
            setMic(!engine.isTalking)
        }
    }

    /** Keys or un-keys the mic from a hardware key: a buzz on the phone, a cue in the ear, a status line. */
    private fun setMic(on: Boolean) {
        if (on == engine.isTalking) return
        if (on) engine.startTalking() else engine.stopTalking()
        val now = engine.isTalking
        if (on && !now) return                            // mic failed to start: the engine has reported why
        buzz(if (now) longArrayOf(0, 40) else longArrayOf(0, 30, 80, 30))
        if (cueTones) engine.cue(if (now) Tones.micOn() else Tones.micOff())
        onStatus(getString(if (now) R.string.status_mic_on else R.string.status_mic_off))   // also refreshes the disc on screen
    }

    private fun buzz(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) } catch (_: Exception) {}
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)

    // ---- foreground notification -------------------------------------------------

    /** Re-posts the notification after something it shows (the mute) changed outside the engine's own callbacks. */
    fun refreshNotification() {
        if (engine.isConnected) postNotification()
    }

    /** True once startForeground has been called for this session; afterwards it is notify(). */
    @Volatile private var foregroundStarted = false
    private var notifyPending = false          // main thread only
    private var lastNotifyMs = 0L              // main thread only

    /**
     * Updates the ongoing notification. Status lines and roster changes arrive from engine and
     * transport threads and can come several times a second; re-calling `startForeground` for each
     * of them is both the wrong API after the first call and more work than the shade can show, so
     * this coalesces them onto the main thread, at most one post per [NOTIFY_GAP_MS].
     */
    private fun postNotification() {
        if (!foregroundStarted) return
        mainHandler.post {
            if (notifyPending || !foregroundStarted) return@post
            notifyPending = true
            val wait = (lastNotifyMs + NOTIFY_GAP_MS - SystemClock.uptimeMillis()).coerceIn(0L, NOTIFY_GAP_MS)
            mainHandler.postDelayed({
                notifyPending = false
                lastNotifyMs = SystemClock.uptimeMillis()
                if (!foregroundStarted || !engine.isConnected) return@postDelayed
                // Without the permission the notification is simply not shown; the session runs on.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(lastStatus))
                }
            }, wait)
        }
    }

    /** Promotes the service to the foreground. Called once per session, from the activity's thread. */
    private fun showForeground(text: String) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), foregroundTypes())
        foregroundStarted = true
        lastNotifyMs = SystemClock.uptimeMillis()
    }

    /** Foreground service types to declare: always connectedDevice, plus microphone where the API has it. */
    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // Mic access from the background needs the microphone type on Android 11+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return types
    }

    /**
     * Silent, ongoing notification: tap opens the activity, the action disconnects. Marked
     * immediate, because the README tells the crew to read it as soon as they have joined and
     * Android 12+ would otherwise be free to hold it back for ten seconds.
     */
    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val disconnect = PendingIntent.getService(
            this, 1,
            Intent(this, PttService::class.java).setAction(ACTION_DISCONNECT),
            flags
        )
        val online = lastRoster.size
        var title = if (online > 0) resources.getQuantityString(R.plurals.notification_title_online, online, online)
        else getString(R.string.notification_title)
        if (engine.muted) title = getString(R.string.notification_muted_suffix, title)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_disconnect), disconnect)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Low-importance channel so the ongoing notification never makes a sound. */
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    // ---- wake / Wi-Fi locks --------------------------------------------------------

    /**
     * Keeps the CPU and the Wi-Fi radio awake for the session. Idempotent.
     *
     * Wi-Fi needs two locks because of how the platform scopes them:
     * - FULL_LOW_LATENCY only takes effect while the screen is on and the app is in the foreground.
     * - FULL_HIGH_PERF keeps the radio out of power save with the screen off or the app in the
     *   background, which is what LAN multicast and Aware links need during a screen-off session.
     * Holding both is the documented combination: low latency wins while visible, high perf
     * otherwise. Android 14 deprecates HIGH_PERF and silently turns it into a LOW_LATENCY lock,
     * so there it adds nothing and is skipped; screen-off Wi-Fi then runs in normal power save.
     */
    @SuppressLint("WakelockTimeout") // held for the whole session, released in disconnect()
    private fun acquireLocks() {
        synchronized(lockObject) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ptt:engine").also { it.acquire() }
            }
            if (wifiLocks.isEmpty()) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val modes = mutableListOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) modes += highPerfWifiMode()
                for (mode in modes) {
                    wifiLocks += wm.createWifiLock(mode, "ptt:wifi:$mode").also {
                        it.setReferenceCounted(false)
                        it.acquire()
                    }
                }
            }
        }
    }

    /** Isolated so the deprecation (API 34, where it aliases LOW_LATENCY anyway) is suppressed in one place. */
    @Suppress("DEPRECATION")
    private fun highPerfWifiMode(): Int = WifiManager.WIFI_MODE_FULL_HIGH_PERF

    /** Releases whatever [acquireLocks] took, and the proximity lock with it; safe when nothing is held. */
    private fun releaseLocks() {
        earWatch(false)
        synchronized(lockObject) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            for (lock in wifiLocks) if (lock.isHeld) lock.release()
            wifiLocks.clear()
        }
    }

    companion object {
        /**
         * Quiet time that separates two hardware presses. A held key autorepeats only after the
         * long-press timeout (user-adjustable under Accessibility) and then every few tens of
         * ms, so anything within that timeout plus a margin is the same press still held.
         */
        private val PRESS_GAP_MS = android.view.ViewConfiguration.getKeyRepeatTimeout() + 150L
        /** A headset press held at least this long is push-to-talk: its release un-keys the mic. */
        private const val HOLD_MS = 400L
        /** Two headset presses closer than this are contact bounce, not two presses. */
        private const val BOUNCE_MS = 120L
        /** Shortest time between two notification updates; the shade cannot show more anyway. */
        private const val NOTIFY_GAP_MS = 250L
        /** A cap on the "runs another build" set, so a passing stranger cannot grow it forever. */
        private const val MAX_REPORTED_BUILDS = 64
        const val ACTION_DISCONNECT = "fi.crewradio.action.DISCONNECT"
        private const val CHANNEL_ID = "ptt"
        private const val NOTIFICATION_ID = 1
        private const val LOG_LINES = 40
    }
}
