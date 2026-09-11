package fi.crewradio

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import fi.crewradio.ask.AskController
import fi.crewradio.ask.AskSheet
import fi.crewradio.audio.CallVolume
import fi.crewradio.transport.BluetoothTransport
import fi.crewradio.transport.LanTransport
import fi.crewradio.transport.Transport
import fi.crewradio.transport.WifiAwareTransport
import java.util.Locale

/**
 * Pads a root view with the window insets, on top of whatever padding the layout gave it.
 * From targetSdk 36 the system bars are always drawn over the app, so every screen has to make
 * room for them itself; the base padding is read once, before the first inset arrives.
 */
internal fun View.padForWindowInsets() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
}

/**
 * Thin UI over [PttService]. The activity binds while visible and mirrors the
 * service's engine state, so rotating, backgrounding or reopening the app never
 * interrupts a running session.
 *
 * The "Radio" layout, top to bottom: channel header (crew name, how many aboard, menu),
 * three transport tiles, a strip with the Bluetooth peer, the channel switch, the playback
 * volume (an in-app gain with a mute), and the talk disc taking every pixel that is left. Settings
 * is behind the menu. Everything the user touches is at least 44 dp; the disc is
 * about 90% of the screen width.
 *
 * Permissions are asked for at the moment they are needed — the mic and, for the transports that
 * are switched on, Bluetooth and nearby devices when Connect is pressed or a tile is tapped — and
 * the connect continues by itself once they are granted. A permission turned off for good is said
 * out loud, with a way into the app's settings.
 */
class MainActivity : AppCompatActivity() {

    private var service: PttService? = null
    private val engine: PttEngine? get() = service?.engine

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var crewName: TextView
    private lateinit var channelLabel: TextView
    private lateinit var peersBox: View
    private lateinit var peersIcon: ImageView
    private lateinit var peerCount: TextView
    private lateinit var pttButton: MaterialButton
    private lateinit var channelRow: View
    private lateinit var channelState: TextView
    private lateinit var muteButton: ImageButton
    private lateinit var volumeSlider: Slider
    private lateinit var volumeValue: TextView
    private lateinit var callVolume: CallVolume
    private var syncingVolume = false
    /** A headset button or the phone's own panel moved the level: follow it. */
    private val volumeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { renderVolume() }
    }
    private lateinit var channelSwitch: MaterialSwitch
    private var syncingSwitch = false                  // true while syncUi() moves the switch itself
    private lateinit var peerButton: TextView
    private lateinit var menuButton: ImageButton
    private lateinit var askRow: View

    /** The ask feature's own state; built on bind, because it needs the engine to hold the mic off. */
    private var askController: AskController? = null
    private var askSheet: AskSheet? = null
    private lateinit var tiles: List<Tile>
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var btPeerIndex = 0                        // 0 = listen only, else pairedDevices[index - 1]
    /** No Wi-Fi Aware radio on this phone: the tile is dead and says why. */
    private var hasAware = false

    /** One transport tile: a view, its icon and label, and whether it is on. */
    private inner class Tile(
        val key: String,
        val root: LinearLayout,
        val icon: ImageView,
        val label: TextView,
        val descriptionRes: Int
    ) {
        var available = true
        var on = false
            set(value) {
                field = value
                root.background = ContextCompat.getDrawable(this@MainActivity, if (value) R.drawable.bg_tile_on else R.drawable.bg_tile_off)
                val tint = ContextCompat.getColor(this@MainActivity, if (value) R.color.primary else R.color.text_dim)
                icon.imageTintList = ColorStateList.valueOf(tint)
                label.setTextColor(tint)
                root.contentDescription = getString(descriptionRes)
                ViewCompat.setStateDescription(
                    root,
                    getString(if (!available) R.string.a11y_unavailable else if (value) R.string.a11y_on else R.string.a11y_off)
                )
            }
    }

    private val connection = object : ServiceConnection {
        /** Adopts the service's engine and pushes the stored settings into it; they are the source of truth. */
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PttService.LocalBinder).service
            service = s
            s.statusListener = { _ -> runOnUiThread { syncUi() } }        // the text itself lives on the Status screen
            s.rosterListener = { peers -> runOnUiThread { renderRoster(peers) } }
            renderRoster(s.lastRoster)
            applySettings(s.engine)
            syncUi()
        }

        /**
         * Only reached if the service process dies; controls become no-ops until rebound. The
         * engine goes with it, so the screen is re-rendered rather than left saying ON CHANNEL
         * with a bright disc and no peer row over a session that is gone.
         */
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            renderRoster(emptyList())
            syncUi()
        }
    }

    /**
     * Everything the user is asked for, in one dialog run. What comes back decides what happens
     * next: a granted set continues the connect that asked for it, a refusal that can be asked
     * again is one line, and a refusal for good is one line with a way into the app's settings.
     */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            val wanted = pendingConnect
            pendingConnect = false
            // Always, and before the connect: loadPairedDevices returns nothing while BLUETOOTH_CONNECT
            // is missing, so a connect that runs first would dial no peer and only listen all session.
            refreshPeer()
            val missing = requiredPermissions().filter { !granted(it) }
            // The one refused for good, not merely the first missing: the message names a permission
            // and the settings shortcut is only right for that one.
            val forGood = missing.firstOrNull { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
            when {
                missing.isEmpty() -> if (wanted) service?.let { connect(it) }
                forGood != null -> snack(deniedMessage(forGood), R.string.perm_settings) { openAppSettings() }
                else -> snack(getString(R.string.perm_needed), null) {}
            }
            syncUi()
        }

    /** True while a permission run is on its way back to a Connect the user already pressed. */
    private var pendingConnect = false

    /** Wires the widgets; everything that needs the engine goes through [service], which arrives on bind. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        root = findViewById(R.id.root)
        root.padForWindowInsets()
        crewName = findViewById(R.id.crewName)
        channelLabel = findViewById(R.id.channelLabel)
        peersBox = findViewById(R.id.peersBox)
        peersIcon = findViewById(R.id.peersIcon)
        peerCount = findViewById(R.id.peerCount)
        pttButton = findViewById(R.id.pttButton)
        channelRow = findViewById(R.id.channelRow)
        channelState = findViewById(R.id.channelState)
        muteButton = findViewById(R.id.muteButton)
        volumeSlider = findViewById(R.id.volumeSlider)
        volumeValue = findViewById(R.id.volumeValue)
        channelSwitch = findViewById(R.id.channelSwitch)
        peerButton = findViewById(R.id.peerButton)
        menuButton = findViewById(R.id.menuButton)
        askRow = findViewById(R.id.askRow)
        askRow.contentDescription = getString(R.string.a11y_ask)
        askRow.setOnClickListener { openAsk() }
        hasAware = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        tiles = listOf(
            Tile(Prefs.KEY_USE_LAN, findViewById(R.id.tileLan), findViewById(R.id.tileLanIcon), findViewById(R.id.tileLanLabel), R.string.a11y_tile_lan),
            Tile(Prefs.KEY_USE_BT, findViewById(R.id.tileBt), findViewById(R.id.tileBtIcon), findViewById(R.id.tileBtLabel), R.string.a11y_tile_bt),
            Tile(Prefs.KEY_USE_AWARE, findViewById(R.id.tileAware), findViewById(R.id.tileAwareIcon), findViewById(R.id.tileAwareLabel), R.string.a11y_tile_aware)
        )
        peerButton.contentDescription = getString(R.string.a11y_peer)
        // The channel row is one thing to a screen reader, not a row and a switch that do the same.
        channelSwitch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        channelState.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        channelRow.contentDescription = getString(R.string.a11y_channel)

        // The disc is a circle as big as its area allows: the smaller of width and height, so it
        // stays a disc in landscape too.
        findViewById<View>(R.id.talkArea).addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val size = minOf(r - l, b - t)
            val lp = pttButton.layoutParams
            if (size > 0 && (lp.width != size || lp.height != size)) {
                lp.width = size
                lp.height = size
                pttButton.layoutParams = lp
            }
        }

        menuButton.setOnClickListener { v ->
            PopupMenu(this, v).apply {
                menuInflater.inflate(R.menu.main, menu)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_status -> startActivity(Intent(this@MainActivity, StatusActivity::class.java))
                        R.id.action_settings -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    true
                }
            }.show()
        }

        // Every choice on this screen is remembered, so on the boat it is open the app, press Connect.
        for (tile in tiles) {
            tile.available = tile.key != Prefs.KEY_USE_AWARE || hasAware
            tile.on = tile.available && prefs.bool(tile.key, tile.key == Prefs.KEY_USE_LAN)
            // Left enabled on purpose: a disabled view swallows the touch, and then the tap that is
            // meant to explain why the tile is dead never reaches the listener below. The alpha and
            // the "unavailable" state description carry the state instead.
            tile.root.alpha = if (tile.available) 1f else 0.4f
            tile.root.setOnClickListener {
                if (!tile.available) { snack(getString(R.string.aware_unavailable), null) {}; return@setOnClickListener }
                if (engine?.isConnected == true) return@setOnClickListener   // takes effect on the next Connect anyway
                tile.on = !tile.on
                prefs.put(tile.key, tile.on)
                // Ask for what this transport needs, now, rather than at Connect on the water.
                if (tile.on) askPermissions(thenConnect = false)
                if (tile.key == Prefs.KEY_USE_BT) refreshPeer()
                if (tile.key == Prefs.KEY_USE_AWARE && tile.on) warnIfLocationOff()
            }
        }
        peerButton.setOnClickListener { v -> if (engine?.isConnected != true) showPeerMenu(v) }
        refreshPeer()

        channelRow.setOnClickListener { channelSwitch.toggle() }
        channelSwitch.setOnCheckedChangeListener { _, on ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            val s = service
            when {
                s == null -> syncUi()                                  // not bound yet; snap back
                // askPermissions, never hasPermissions: the latter covers only the required set, so a
                // phone that granted those through a tile would never be asked for POST_NOTIFICATIONS
                // and would lose the status line and the Disconnect action. It connects straight away
                // when nothing is missing, so this is the same for a phone that has everything.
                on && !s.engine.isConnected -> { askPermissions(thenConnect = true); syncUi() }
                !on && s.engine.isConnected -> { s.disconnect(); syncUi() }
            }
        }

        // Volume: the slider is the phone's call volume, what the volume keys would set in a call
        // (on channel they are a talk key); the mute is session state, the engine's, cleared on leaving.
        callVolume = CallVolume(this)
        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || syncingVolume) return@addOnChangeListener
            callVolume.set(callVolume.stream(engine?.bluetoothHeadsetNow == true), value.toInt())
            renderVolume()
        }
        muteButton.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            if (!e.isConnected) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            e.muted = !e.muted
            service?.refreshNotification()
            syncUi()
        }

        // Half duplex is hold-to-talk, which a screen reader's double tap cannot express: with touch
        // exploration on the disc becomes a latch and the click handler below does the toggling.
        pttButton.setOnTouchListener { v, ev ->
            val e = engine ?: return@setOnTouchListener false
            if (touchExploration() || e.mode != PttEngine.Mode.HALF_DUPLEX) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    fingerDown = true
                    e.startTalking()
                    refreshPttLabel()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    fingerDown = false
                    e.stopTalking()
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    v.performClick()                    // the click itself does nothing here; a11y wants it
                    refreshPttLabel()
                }
            }
            true
        }
        pttButton.setOnClickListener {
            val e = engine ?: return@setOnClickListener
            if (e.mode == PttEngine.Mode.FULL_DUPLEX || touchExploration()) {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                e.toggleTalking()
                refreshPttLabel()
            }
        }

        // The platform property, not ViewCompat: that wrapper is deprecated, and this has been on
        // View since API 19, well below our minSdk. setStateDescription below still needs the
        // compat call, since the platform gained that one only in API 30.
        pttButton.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        refreshPttLabel()
    }

    /** Binds while visible; BIND_AUTO_CREATE means the service (and its senderId) exists whenever the UI is up. */
    override fun onStart() {
        super.onStart()
        // A protected system broadcast: only the system can send it, so exporting the receiver exposes nothing.
        ContextCompat.registerReceiver(this, volumeChanged, IntentFilter(CallVolume.VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /** Coming back from the settings screen: push the live-applicable settings into the engine. */
    override fun onResume() {
        super.onResume()
        crewName.text = prefs.crewName.uppercase(Locale.getDefault())
        engine?.let { applySettings(it) }
        refreshPttLabel()
        refreshAsk()
    }

    /** Unbinds without touching the session: a connected service keeps running as a started foreground service. */
    override fun onStop() {
        // The sheet holds the microphone and keeps the engine's voice keying suspended, so a
        // question does not outlive the screen it was asked from.
        askSheet?.dismiss()
        askSheet = null
        askController?.release()
        askController = null
        service?.statusListener = null
        service?.rosterListener = null
        service = null
        unregisterReceiver(volumeChanged)
        unbindService(connection)   // the service keeps running while connected; see PttService
        super.onStop()
    }

    private fun tileOn(key: String) = tiles.first { it.key == key }.on

    /**
     * Hands the service a factory for the selected transports. The factory runs on the service's
     * session thread, after the channel key has been stretched into the packet key, because Wi-Fi
     * Aware's own secrets are derived from it ([ChannelCrypto]); everything the factory needs from
     * this screen is read here, on the main thread, and captured.
     */
    private fun connect(s: PttService) {
        applySettings(s.engine)
        if (tiles.none { it.on }) {
            Toast.makeText(this, R.string.pick_transport, Toast.LENGTH_SHORT).show()
            syncUi()
            return
        }
        if (tileOn(Prefs.KEY_USE_AWARE) && warnIfLocationOff()) { syncUi(); return }   // no session: put the switch back
        val ctx = applicationContext
        val lan = tileOn(Prefs.KEY_USE_LAN)
        val bt = tileOn(Prefs.KEY_USE_BT)
        val aware = tileOn(Prefs.KEY_USE_AWARE)
        val group = prefs.group
        val port = prefs.port
        val peer = pairedDevices.getOrNull(btPeerIndex - 1)
        s.connect { e ->
            val list = mutableListOf<Transport>()
            if (lan) list += LanTransport(ctx, group, port)
            if (bt) list += BluetoothTransport(ctx, peer, e.senderId)
            val crypto = e.crypto
            if (aware && crypto != null) list += WifiAwareTransport(ctx, e.senderId, crypto.awarePassphrase, crypto::awareIdTag)
            list
        }
        syncUi()
    }

    /**
     * The settings that apply without a reconnect: duplex mode, relay, codec, hop limit
     * and the announced name. Changing the mode un-keys the mic, which is what you want.
     * The channel key is deliberately not here: stretching it takes about a second, so the
     * service's session thread does it at Connect and a key changed in Settings takes effect the
     * next time the channel is joined.
     */
    private fun applySettings(e: PttEngine) {
        e.mode = if (prefs.fullDuplex) PttEngine.Mode.FULL_DUPLEX else PttEngine.Mode.HALF_DUPLEX
        e.relay = prefs.relay
        e.codec = if (prefs.opus) Packet.Codec.OPUS else Packet.Codec.PCM
        e.maxHops = prefs.hops
        e.displayName = prefs.name ?: e.defaultName
        e.audioRoute = when (prefs.audioRoute) {
            Prefs.ROUTE_SPEAKER -> fi.crewradio.audio.AudioRoute.Policy.SPEAKER
            Prefs.ROUTE_EARPIECE -> fi.crewradio.audio.AudioRoute.Policy.EARPIECE
            else -> fi.crewradio.audio.AudioRoute.Policy.AUTO
        }
        e.headsetAsCall = prefs.headsetAsCall
        e.headsetVox = prefs.headsetVox
        e.useProximity = prefs.proximitySensor
        service?.cueTones = prefs.cueTones
        service?.refreshHardwareButtons()
    }

    /**
     * Pulls the connect state from the engine into the widgets: the channel switch and its
     * one-line state, like the power switch on a radio. The switch is moved under
     * [syncingSwitch] so its listener does not mistake that for the user.
     */
    private fun syncUi() {
        val connected = engine?.isConnected == true
        val muted = connected && engine?.muted == true
        syncingSwitch = true
        channelSwitch.isChecked = connected
        syncingSwitch = false
        val state = getString(if (muted) R.string.channel_on_muted else if (connected) R.string.channel_on else R.string.channel_off)
        channelState.text = state
        ViewCompat.setStateDescription(channelRow, state)
        channelState.setTextColor(ContextCompat.getColor(this, if (muted) R.color.error else if (connected) R.color.primary else R.color.text_dim))
        renderVolume()
        for (tile in tiles) tile.root.alpha = if (!tile.available) 0.4f else if (connected) 0.55f else 1f
        refreshPeer()
        // The screen stays on only while on channel, and only if the user wants it to.
        if (connected && prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshPttLabel()
    }

    private var fingerDown = false
    /** What the disc last said, so a screen reader is told only when it changes. */
    private var announcedLive: Boolean? = null

    /**
     * Disc caption and colours: teal and TALK while idle, red and ON AIR while the mic is live.
     * ON AIR without a finger on the disc means a talk key latched it, so the hint says how to stop.
     * The accessibility action carries the same words, and going on or off air is announced.
     */
    private fun refreshPttLabel() {
        val e = engine
        val live = e?.isTalking == true
        val touch = touchExploration()
        val (big, hint) = when (e?.mode ?: PttEngine.Mode.HALF_DUPLEX) {
            PttEngine.Mode.HALF_DUPLEX ->
                if (!live) R.string.ptt_talk to (if (touch) R.string.ptt_talk_touch_hint else R.string.ptt_talk_hint)
                else if (touch) R.string.ptt_on_air to R.string.ptt_on_air_touch_hint
                else if (fingerDown) R.string.ptt_on_air to R.string.ptt_on_air_hint
                else R.string.ptt_on_air to R.string.ptt_on_air_latched_hint
            PttEngine.Mode.FULL_DUPLEX -> if (live) R.string.ptt_mic_on to R.string.ptt_mic_on_hint else R.string.ptt_mic_off to R.string.ptt_mic_off_hint
        }
        // Off channel the disc is inert (startTalking has no transports to send to), so the hint
        // says why rather than HOLD, which would be a lie, and what to do about it.
        val small = if (e?.isConnected == true) hint else R.string.ptt_off_channel_hint
        val hintColor = ContextCompat.getColor(this, if (live) R.color.error else R.color.primary_container)
        pttButton.text = SpannableStringBuilder()
            .append(getString(big))
            .append("\n")
            .append(getString(small), RelativeSizeSpan(0.3f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .also { sb ->
                val start = sb.length - getString(small).length
                sb.setSpan(ForegroundColorSpan(hintColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        pttButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (live) R.color.on_air else R.color.primary))
        pttButton.setTextColor(ContextCompat.getColor(this, if (live) R.color.on_air_text else R.color.on_primary))
        pttButton.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, if (live) R.color.error else R.color.outline))
        // Off channel the disc does nothing, so it is dimmed, like the mute glyph on the volume row.
        pttButton.alpha = if (e?.isConnected == true) 1f else 0.55f
        ViewCompat.replaceAccessibilityAction(
            pttButton, AccessibilityActionCompat.ACTION_CLICK,
            getString(if (live) R.string.a11y_talk_stop else R.string.a11y_talk_start)
        ) { v, _ -> v.performClick() }
        // The disc is a polite live region (set in onCreate), so a changed state description is
        // spoken as it happens and is read again whenever the disc is focused. That replaces
        // announceForAccessibility, which is deprecated and says nothing on focus.
        if (announcedLive != live) {
            ViewCompat.setStateDescription(pttButton, getString(if (live) R.string.a11y_on_air else R.string.a11y_listening))
            announcedLive = live
        }
    }

    /**
     * The volume row: the slider shows the call volume of the stream in use (the Bluetooth
     * headset's while it carries the audio), in the phone's own steps, and the number is the
     * step. The mute glyph exists only on channel, so off channel it is dimmed.
     */
    private fun renderVolume() {
        val connected = engine?.isConnected == true
        val muted = connected && engine?.muted == true
        val stream = callVolume.stream(engine?.bluetoothHeadsetNow == true)
        val min = callVolume.min(stream)
        val max = maxOf(callVolume.max(stream), min + 1)
        val level = callVolume.get(stream).coerceIn(min, max)
        syncingVolume = true
        volumeSlider.valueFrom = min.toFloat()
        volumeSlider.valueTo = max.toFloat()
        volumeSlider.value = level.toFloat()
        syncingVolume = false
        muteButton.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        muteButton.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (muted) R.color.error else R.color.secondary))
        muteButton.contentDescription = getString(if (muted) R.string.volume_unmute else R.string.volume_mute)
        muteButton.alpha = if (connected) 1f else 0.55f
        volumeValue.text = if (muted) getString(R.string.volume_muted_value) else getString(R.string.volume_step, level)
        volumeValue.setTextColor(ContextCompat.getColor(this, if (muted) R.color.error else R.color.text))
        volumeSlider.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (muted) R.color.text_dim else R.color.primary))
        volumeSlider.trackActiveTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (muted) R.color.dot_idle else R.color.primary))
    }

    /**
     * The header count, and whoever is talking shown in green on the label line above the
     * channel name. Nothing changes size or position: a layout shift under the talk disc is
     * the last thing a thumb about to press it needs. The full list lives on the Status screen.
     *
     * A crew member running a different build of the app is said on that same line when nobody is
     * talking: the wire format has no legacy mode, so a mismatch is worth seeing before it matters.
     *
     * The bars beside the count are the weakest link on the roster ([Peer.level]): one glance says
     * someone is breaking up without leaving the talk screen, and who it is waits on the Status
     * screen. At one bar the box turns the error colour, whoever is talking; green while someone
     * talks and every link holds; no bars at all with nobody aboard.
     */
    private fun renderRoster(peers: List<Peer>) {
        peerCount.text = getString(R.string.head_count, peers.size)
        val talking = peers.filter { it.talking }
        val green = ContextCompat.getColor(this, R.color.talking)
        val otherBuild = peers.any { it.versionCode != 0 && it.versionCode != BuildConfig.VERSION_CODE }
        when {
            talking.isNotEmpty() -> {
                channelLabel.text = getString(R.string.talking_line, talking.joinToString(", ") { it.label.uppercase(Locale.getDefault()) })
                channelLabel.setTextColor(green)
            }
            otherBuild -> {
                channelLabel.text = getString(R.string.other_build_aboard)
                channelLabel.setTextColor(ContextCompat.getColor(this, R.color.error))
            }
            else -> {
                channelLabel.text = getString(R.string.crew_channel)
                channelLabel.setTextColor(ContextCompat.getColor(this, R.color.text_teal_dim))
            }
        }
        val worst = peers.minOfOrNull { it.level } ?: 0
        val weak = peers.isNotEmpty() && worst <= LinkQuality.WEAK
        peersIcon.setImageLevel(worst)
        val boxColor = when {
            weak -> ContextCompat.getColor(this, R.color.error)
            talking.isNotEmpty() -> green
            else -> 0
        }
        peersIcon.imageTintList = ColorStateList.valueOf(if (boxColor != 0) boxColor else ContextCompat.getColor(this, R.color.primary))
        peerCount.setTextColor(if (boxColor != 0) boxColor else ContextCompat.getColor(this, R.color.text))
        val aboard = resources.getQuantityString(R.plurals.a11y_aboard, peers.size, peers.size)
        peersBox.contentDescription = if (weak) getString(R.string.a11y_weak_link, aboard) else aboard
    }

    // ---- Bluetooth peer -----------------------------------------------------------

    /** The peer strip: which peer Bluetooth will dial; hidden altogether while Bluetooth is off. */
    /**
     * The ask row: hidden altogether unless the setting is on and a Signal K server is set, the
     * same way the Bluetooth peer row is hidden while Bluetooth is off. A crew that does not use
     * Signal K never sees it.
     */
    private fun refreshAsk() {
        val controller = askController ?: AskController(this, prefs) { engine }.also { askController = it }
        askRow.visibility = if (controller.offered()) View.VISIBLE else View.GONE
    }

    /** Opens the ask sheet, or says why it will not open. */
    private fun openAsk() {
        val controller = askController ?: return
        askSheet?.dismiss()
        askSheet = AskSheet.open(this, prefs, controller)
        if (askSheet == null) Toast.makeText(this, R.string.ask_no_server, Toast.LENGTH_SHORT).show()
    }

    /**
     * The Bluetooth peer row: hidden while Bluetooth is off, and hidden again while on channel.
     * The peer is a constructor argument of the transport, so it is chosen before Connect and
     * cannot be changed during a session; on channel it is only a bar that does nothing, and the
     * screen above the disc is better spent on who is talking.
     */
    private fun refreshPeer() {
        if (!tileOn(Prefs.KEY_USE_BT) || engine?.isConnected == true) {
            peerButton.visibility = View.GONE
            return
        }
        peerButton.visibility = View.VISIBLE
        loadPairedDevices()
        val dev = pairedDevices.getOrNull(btPeerIndex - 1)
        peerButton.text = if (dev == null) getString(R.string.peer_listen_only)
        else getString(R.string.peer_line, deviceLabel(dev).uppercase(Locale.getDefault()))
    }

    /** Popup with "listen only" and every bonded device; the choice is remembered. */
    private fun showPeerMenu(anchor: View) {
        loadPairedDevices()
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, getString(R.string.bt_listen_only))
            pairedDevices.forEachIndexed { i, d -> menu.add(0, i + 1, i + 1, deviceLabel(d)) }
            setOnMenuItemClickListener { item ->
                btPeerIndex = item.itemId
                prefs.put(Prefs.KEY_BT_PEER, pairedDevices.getOrNull(btPeerIndex - 1)?.address ?: "")
                refreshPeer()
                true
            }
        }.show()
    }

    /**
     * What to call a bonded device. Reading its name needs BLUETOOTH_CONNECT from Android 12 on;
     * without it the address is all the app may know, and that still identifies the peer.
     */
    private fun deviceLabel(dev: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return dev.address
        return try { dev.name } catch (_: SecurityException) { null } ?: dev.address
    }

    /** Reads the bonded devices and re-finds the remembered one; nothing without the permission. */
    private fun loadPairedDevices() {
        if (bluetoothPermissions().any { !granted(it) }) { pairedDevices = emptyList(); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        pairedDevices = (try { adapter?.bondedDevices?.toList() } catch (_: SecurityException) { null })
            .orEmpty().sortedBy { deviceLabel(it) }
        val remembered = pairedDevices.indexOfFirst { it.address == prefs.string(Prefs.KEY_BT_PEER) }
        btPeerIndex = if (remembered >= 0) remembered + 1 else 0
    }

    // ---- Wi-Fi Aware ----------------------------------------------------------------

    /**
     * Before Android 13 Aware discovery needs location services switched on system-wide, and finds
     * nobody in silence when they are off. Says so once, with the way to turn them on.
     * Returns true when it said something, so a Connect can stop and let the user decide.
     */
    private fun warnIfLocationOff(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        if (lm.isLocationEnabled) return false
        snack(getString(R.string.location_off_for_aware), R.string.location_settings) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        return true
    }

    // ---- permissions --------------------------------------------------------------

    /**
     * Permissions without which Connect cannot work, for the transports that are switched
     * on: the mic always, Bluetooth only with the Bluetooth tile, Aware discovery only with
     * the Aware tile. A LAN-only crew member never has to grant Bluetooth anything.
     */
    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (tileOn(Prefs.KEY_USE_BT)) list += bluetoothPermissions()
        if (tileOn(Prefs.KEY_USE_AWARE)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.NEARBY_WIFI_DEVICES
            else list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list
    }

    /** What listing bonded devices and dialling one need; nothing before Android 12. */
    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /**
     * Asked for alongside the required ones, but the session runs without them:
     * - POST_NOTIFICATIONS: without it the foreground notification is hidden on Android 13+,
     *   but the service still runs. Asked for when the channel is first joined, where the
     *   notification is what the crew is told to read.
     * - BLUETOOTH_SCAN: only used to cancel an in-progress system scan before dialling a peer,
     *   which makes RFCOMM connect faster; [BluetoothTransport] skips that step without it.
     */
    private fun optionalPermissions(connecting: Boolean): List<String> {
        val list = mutableListOf<String>()
        if (connecting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
        if (tileOn(Prefs.KEY_USE_BT) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) list += Manifest.permission.BLUETOOTH_SCAN
        return list
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** True when every required (not optional) permission is granted. */
    private fun hasPermissions() = requiredPermissions().all { granted(it) }

    /**
     * Asks for whatever is still missing. With [thenConnect] the answer continues the Connect the
     * user already pressed, so nobody has to press it twice.
     */
    private fun askPermissions(thenConnect: Boolean) {
        val missing = (requiredPermissions() + optionalPermissions(thenConnect)).filter { !granted(it) }
        if (missing.isEmpty()) {
            if (thenConnect) service?.let { connect(it) }
            return
        }
        pendingConnect = thenConnect
        permissionLauncher.launch(missing.toTypedArray())
    }

    /** Which refusal this is, in the crew's words. */
    private fun deniedMessage(permission: String): String = getString(
        when (permission) {
            Manifest.permission.RECORD_AUDIO -> R.string.perm_mic_denied
            Manifest.permission.BLUETOOTH_CONNECT -> R.string.perm_bt_denied
            else -> R.string.perm_aware_denied
        }
    )

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    /** One line at the bottom of the screen, optionally with something to do about it. */
    private fun snack(text: String, actionRes: Int?, action: () -> Unit) {
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
        if (actionRes != null) bar.setAction(actionRes) { action() }
        bar.show()
    }

    /** True while a screen reader explores by touch, when hold-to-talk cannot be expressed. */
    private fun touchExploration(): Boolean =
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
}
