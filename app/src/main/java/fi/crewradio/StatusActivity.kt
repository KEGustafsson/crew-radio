package fi.crewradio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import fi.crewradio.audio.CallVolume
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale

/**
 * Everything the main screen deliberately leaves out, in the main screen's own language:
 * the same header, cards with a teal label, label-value rows, the crew as dot/name/meta
 * rows, packet counters as tiles, and the status log last.
 *
 * The tree is built once, in [buildCards], and every tick only sets text on the views that are
 * already there; the three lists whose length changes (the crew, the network interfaces and the
 * log) are rebuilt only when their content changes, so the scroll position stays where the reader
 * left it. The one tappable row is "Check for updates".
 */
class StatusActivity : AppCompatActivity() {

    private var service: PttService? = null
    private lateinit var prefs: Prefs
    private lateinit var inflater: LayoutInflater
    private lateinit var crewName: TextView
    private lateinit var statePill: TextView
    private lateinit var cards: LinearLayout
    private lateinit var callVolume: CallVolume
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2_000)
        }
    }

    // The views the tick writes into, kept so nothing is inflated twice.
    private val values = HashMap<Int, TextView>()          // label string id -> value view
    private val tileValues = HashMap<Int, TextView>()      // tile label string id -> value view
    private lateinit var crewAside: TextView
    private lateinit var crewRows: LinearLayout
    private var crewKey = ""
    private lateinit var netAside: TextView
    private lateinit var nicRows: LinearLayout
    private var nicKey = ""
    /** The bars at the end of the WI-FI SIGNAL value: the same level-list as the roster's. */
    private lateinit var wifiBars: Drawable

    /**
     * The phone's link to the access point, in dBm, from the Wi-Fi network's capabilities: the one
     * radio level Android hands out (nothing of the kind exists for a Bluetooth Classic link, and
     * Aware gives a distance at best). Watched by transport, not the default network: a boat AP
     * with no internet is often not the default. Null until heard, and again when Wi-Fi goes.
     */
    @Volatile private var wifiRssi: Int? = null
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            val rssi = nc.signalStrength
            wifiRssi = if (rssi == Int.MIN_VALUE) null else rssi
        }
        override fun onLost(network: Network) { wifiRssi = null }
    }
    private lateinit var packetsAside: TextView
    private lateinit var phoneAside: TextView
    private lateinit var logRows: LinearLayout
    private var logKey = ""
    private lateinit var gateRow: View

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PttService.LocalBinder).service
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_status)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<View>(R.id.root).padForWindowInsets()
        prefs = Prefs(this)
        inflater = LayoutInflater.from(this)
        callVolume = CallVolume(this)
        crewName = findViewById(R.id.crewName)
        statePill = findViewById(R.id.statePill)
        cards = findViewById(R.id.cards)
        crewName.text = prefs.crewName.uppercase(Locale.getDefault())
        buildCards()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback
        )
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        try { connectivity.unregisterNetworkCallback(wifiCallback) } catch (_: IllegalArgumentException) {}
        wifiRssi = null
        unbindService(connection)
        service = null
        super.onStop()
    }

    // ---- the tree, built once ------------------------------------------------------

    private fun buildCards() {
        card(R.string.card_crew).let { (aside, rows) ->
            crewAside = aside
            crewRows = rows
        }

        card(R.string.card_this_phone).let { (aside, rows) ->
            phoneAside = aside
            for (label in listOf(
                R.string.kv_my_name, R.string.kv_mode, R.string.kv_relay, R.string.kv_codec,
                R.string.kv_audio, R.string.kv_call_volume, R.string.kv_hop_limit, R.string.kv_version
            )) rows.addView(kv(label))
            gateRow = kv(R.string.kv_voice_gate).also { it.visibility = View.GONE; rows.addView(it) }
            // The one row on this screen that does something: the Releases page in a browser.
            val updates = kv(R.string.kv_updates)
            values[R.string.kv_updates]?.text = getString(R.string.value_check_updates)
            values[R.string.kv_updates]?.setTextColor(color(R.color.primary))
            updates.isClickable = true
            updates.isFocusable = true
            updates.contentDescription = getString(R.string.value_check_updates)
            updates.setOnClickListener { openReleases() }
            rows.addView(updates)
        }

        card(R.string.card_network).let { (aside, rows) ->
            netAside = aside
            nicRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            rows.addView(nicRows)
            rows.addView(kv(R.string.kv_wifi_signal))
            wifiBars = ContextCompat.getDrawable(this, R.drawable.ic_signal)!!.mutate()
            values[R.string.kv_wifi_signal]?.apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, wifiBars, null)
                compoundDrawablePadding = dp(10)
            }
            for (label in listOf(R.string.kv_multicast, R.string.kv_aware, R.string.kv_channel_key, R.string.kv_bluetooth)) {
                rows.addView(kv(label))
            }
        }

        card(R.string.card_packets).let { (aside, rows) ->
            packetsAside = aside
            rows.addView(tiles(R.string.tile_received, R.string.tile_sent, R.string.tile_relayed))
            rows.addView(tiles(R.string.tile_duplicates, R.string.tile_concealed, R.string.tile_hellos))
            rows.addView(tiles(R.string.tile_rejected, R.string.tile_clock, R.string.tile_underruns))
        }

        card(R.string.card_log).let { (_, rows) -> logRows = rows }
    }

    // ---- rendering ----------------------------------------------------------------

    private fun render() {
        val s = service
        val e = s?.engine
        val on = e?.isConnected == true

        statePill.text = getString(if (on) R.string.channel_on else R.string.state_off)
        statePill.setTextColor(color(if (on) R.color.primary else R.color.text_dim))

        // Crew
        val peers = e?.rosterNow ?: emptyList()
        crewAside.text = getString(R.string.aboard, peers.size)
        val key = peers.joinToString("|") { "${it.id}/${it.label}/${it.talking}/${it.via}/${it.hops}/${it.transports}/${it.versionCode}/${it.level}" } + "/$on"
        if (key != crewKey) {
            crewKey = key
            crewRows.removeAllViews()
            if (peers.isEmpty()) crewRows.addView(note(getString(if (on) R.string.roster_alone else R.string.status_off_hint)))
            else for (p in peers) crewRows.addView(peerRow(p))
        } else {
            // Only the "heard N s ago" moves; rewrite that line in place.
            for (i in peers.indices) {
                val row = crewRows.getChildAt(i) ?: break
                row.findViewById<TextView>(R.id.detail).text = peerDetail(peers[i])
            }
        }

        // This phone
        phoneAside.text = e?.senderId?.let { hex(it) }.orEmpty()
        set(R.string.kv_my_name, prefs.name ?: e?.defaultName ?: getString(R.string.value_dash))
        set(R.string.kv_mode, getString(if (prefs.fullDuplex) R.string.value_full_duplex else R.string.value_half_duplex))
        set(R.string.kv_relay, getString(if (prefs.relay) R.string.value_on else R.string.value_off))
        set(R.string.kv_codec, getString(if (prefs.opus) R.string.value_opus else R.string.value_pcm))
        set(R.string.kv_audio, if (on) e.audioRouteNow else routeLabel())      // `on` implies a non-null engine
        val stream = callVolume.stream(e?.bluetoothHeadsetNow == true)
        val muted = on && e.muted
        set(R.string.kv_call_volume, getString(
            if (muted) R.string.value_volume_muted else R.string.value_volume,
            callVolume.get(stream), callVolume.max(stream)
        ))
        set(R.string.kv_hop_limit, getString(R.string.value_number, prefs.hops))
        set(R.string.kv_version, getString(R.string.value_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA))
        val peak = e?.micPeakNow ?: -1
        gateRow.visibility = if (peak >= 0) View.VISIBLE else View.GONE
        if (peak >= 0) set(R.string.kv_voice_gate, getString(
            R.string.value_gate,
            getString(if (e?.voiceArmed == true) R.string.value_gate_armed else R.string.value_gate_away),
            peak, e?.gateOpenRms ?: 0
        ))

        // Network
        netAside.text = e?.activeTransports?.joinToString(" + ")?.uppercase(Locale.ROOT).orEmpty()
        val nics = interfaces()
        val nicsKey = nics.joinToString("|") { "${it.first}=${it.second}" }
        if (nicsKey != nicKey) {
            nicKey = nicsKey
            nicRows.removeAllViews()
            for ((nic, addr) in nics) nicRows.addView(kvLiteral(nic.uppercase(Locale.ROOT), addr))
        }
        val rssi = wifiRssi
        set(R.string.kv_wifi_signal, if (rssi == null) getString(R.string.value_dash) else getString(R.string.value_dbm, rssi))
        wifiBars.level = if (rssi == null) 0 else wifiLevel(rssi)
        wifiBars.setTint(color(if (rssi != null && wifiBars.level <= LinkQuality.WEAK) R.color.error else R.color.primary))
        set(R.string.kv_multicast, getString(R.string.value_endpoint, prefs.group, prefs.port))
        set(R.string.kv_aware, fi.crewradio.transport.WifiAwareTransport.SERVICE_NAME)
        // Enough to compare across phones, not enough to copy.
        set(R.string.kv_channel_key, getString(R.string.value_key_ends, prefs.channelKey.takeLast(4)))
        set(R.string.kv_bluetooth, bluetoothName())

        // Packets
        val st = e?.stats()
        packetsAside.text = if (on) getString(R.string.value_since_join, kb((st?.rxBytes ?: 0) + (st?.txBytes ?: 0))) else ""
        setTile(R.string.tile_received, st?.rxPackets)
        setTile(R.string.tile_sent, st?.txPackets)
        setTile(R.string.tile_relayed, st?.relayed)
        setTile(R.string.tile_duplicates, st?.duplicates)
        setTile(R.string.tile_concealed, st?.concealed)
        setTile(R.string.tile_hellos, st?.hellos)
        setTile(R.string.tile_rejected, st?.rejected)
        setTile(R.string.tile_clock, st?.stale)
        setTile(R.string.tile_underruns, st?.underruns)

        // Log
        val lines = s?.statusLog.orEmpty().asReversed()
        val lineKey = lines.joinToString("\n")
        if (lineKey != logKey) {
            logKey = lineKey
            logRows.removeAllViews()
            if (lines.isEmpty()) logRows.addView(note(getString(R.string.value_dash)))
            else for (line in lines) logRows.addView(logLine(line))
        }
    }

    /** The platform's own signal-bar scale for [rssi], on the roster's 0 to [LinkQuality.BARS]. */
    private fun wifiLevel(rssi: Int): Int {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val max = wifi.maxSignalLevel
            if (max <= 0) 0 else wifi.calculateSignalLevel(rssi) * LinkQuality.BARS / max
        } else {
            @Suppress("DEPRECATION")   // the static form is the only one before API 30
            WifiManager.calculateSignalLevel(rssi, LinkQuality.BARS + 1)
        }
    }

    /** What the audio-output setting says while the channel is off and there is no route in use. */
    private fun routeLabel(): String {
        val values = resources.getStringArray(R.array.audio_route_values)
        val labels = resources.getStringArray(R.array.audio_route_short)
        val i = values.indexOf(prefs.audioRoute)
        return labels.getOrElse(if (i >= 0) i else 0) { labels[0] }
    }

    private fun openReleases() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.releases_url).toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- the pieces the tree is made of --------------------------------------------

    /** Adds a card with a title and returns its aside (right-hand) label and its row container. */
    private fun card(title: Int): Pair<TextView, LinearLayout> {
        val v = inflater.inflate(R.layout.card_status, cards, false)
        v.findViewById<TextView>(R.id.title).setText(title)
        cards.addView(v)
        return v.findViewById<TextView>(R.id.aside) to v.findViewById(R.id.rows)
    }

    /** A label-value row whose value this screen writes by its label's resource id. */
    private fun kv(label: Int): View {
        val v = inflater.inflate(R.layout.row_kv, cards, false)
        v.findViewById<TextView>(R.id.key).setText(label)
        values[label] = v.findViewById(R.id.value)
        return v
    }

    /** The same row for something with no fixed label: an interface name, which the phone names. */
    private fun kvLiteral(key: String, value: String): View {
        val v = inflater.inflate(R.layout.row_kv, cards, false)
        v.findViewById<TextView>(R.id.key).text = key
        v.findViewById<TextView>(R.id.value).text = value
        return v
    }

    private fun set(label: Int, value: String) { values[label]?.text = value }
    private fun setTile(label: Int, value: Long?) { tileValues[label]?.text = getString(R.string.value_number, value ?: 0L) }

    private fun peerRow(p: Peer): View {
        val v = inflater.inflate(R.layout.row_status_peer, cards, false)
        v.findViewById<TextView>(R.id.name).text = p.label
        val meta = v.findViewById<TextView>(R.id.meta)
        val dot = v.findViewById<View>(R.id.dot)
        if (p.talking) {
            meta.setText(R.string.meta_talking)
            meta.setTextColor(color(R.color.talking))
            dot.backgroundTintList = ColorStateList.valueOf(color(R.color.talking))
        } else {
            val via = p.via.uppercase(Locale.ROOT)
            // A plurals resource, not two hand-rolled forms: Polish, Russian and Arabic need more
            // than two, and a translator cannot add them to a when.
            meta.text = if (p.hops <= 0) via
            else resources.getQuantityString(R.plurals.peer_meta_hops, p.hops, via, p.hops)
            meta.setTextColor(color(R.color.text_dim))
            dot.backgroundTintList = null
        }
        // How many of its packets get here: red when it is breaking up, green with the rest of the row while it talks.
        val level = v.findViewById<ImageView>(R.id.level)
        level.setImageLevel(p.level)
        level.imageTintList = ColorStateList.valueOf(color(when {
            p.level <= LinkQuality.WEAK -> R.color.error
            p.talking -> R.color.talking
            else -> R.color.primary
        }))
        level.contentDescription = getString(R.string.a11y_link_level, p.level, LinkQuality.BARS)
        // Every phone on the crew must run the same build: the wire format has no legacy mode.
        val build = v.findViewById<TextView>(R.id.build)
        val theirs = p.versionCode
        if (theirs != 0 && theirs != BuildConfig.VERSION_CODE) {
            build.setText(if (theirs < BuildConfig.VERSION_CODE) R.string.peer_old_build else R.string.peer_newer_build)
            build.visibility = View.VISIBLE
        } else {
            build.visibility = View.GONE
        }
        v.findViewById<TextView>(R.id.detail).text = peerDetail(p)
        return v
    }

    private fun peerDetail(p: Peer): String = getString(
        R.string.peer_detail, Hello.describe(p.transports).ifEmpty { getString(R.string.value_dash) }, hex(p.id), ago(p.seenAgoMs)
    )

    private fun tiles(vararg labels: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        labels.forEachIndexed { i, label ->
            val t = inflater.inflate(R.layout.tile_stat, row, false)
            t.findViewById<TextView>(R.id.label).setText(label)
            tileValues[label] = t.findViewById(R.id.value)
            (t.layoutParams as LinearLayout.LayoutParams).marginStart = if (i == 0) 0 else dp(8)
            row.addView(t)
        }
        return row
    }

    private fun logLine(line: String): View = TextView(this).apply {
        text = line
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 12f
        setTextColor(color(R.color.text_dim))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun note(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(color(R.color.text_dim))
        setPadding(0, dp(8), 0, dp(6))
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hex(id: Int) = id.toUInt().toString(16)
    private fun kb(bytes: Long) =
        if (bytes < 10_000) getString(R.string.value_bytes, bytes) else getString(R.string.value_kilobytes, bytes / 1024)
    private fun ago(ms: Long) =
        if (ms < 1_000) getString(R.string.value_just_now) else getString(R.string.value_seconds_ago, ms / 1000)

    /** Every interface that is up with an address: wlan0, the Aware data interface, a hotspot. */
    private fun interfaces(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.interfaceAddresses.mapNotNull { ia ->
                    val a = ia.address
                    when {
                        a is Inet4Address -> nic.name to getString(R.string.value_address, a.hostAddress, ia.networkPrefixLength)
                        a is Inet6Address && nic.name.startsWith("aware") -> nic.name to (a.hostAddress?.substringBefore('%') ?: "")
                        else -> null
                    }
                }
            }
            .ifEmpty { listOf(getString(R.string.kv_wifi) to getString(R.string.value_no_interface)) }
    } catch (_: Exception) {
        listOf(getString(R.string.kv_wifi) to getString(R.string.value_unknown))
    }

    @SuppressLint("MissingPermission")   // BLUETOOTH_CONNECT is asked for by MainActivity; the read is in a try/catch
    private fun bluetoothName(): String {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return getString(R.string.value_none)
        if (!adapter.isEnabled) return getString(R.string.value_off)
        return try { adapter.name } catch (_: SecurityException) { null } ?: getString(R.string.value_on)
    }
}
