package fi.crewradio.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi Aware (NAN): infrastructure-free, no access point, no pairing.
 *
 * Every phone publishes AND subscribes to the same service. Each side carries
 * its node id and an HMAC of it under the channel key in the service-specific info
 * ([AwareSsi]); a discovery whose tag does not verify is ignored, so a stranger publishing
 * the service name costs nothing. When a subscriber discovers a peer it initiates a data
 * path only if its own id is lower than the peer's, so each pair gets exactly one link.
 * Data paths are TCP over the NAN IPv6 link, framed by [StreamLink]. Combined with the
 * engine's relay this forms an app-level flooding mesh: A-B-C works even if A and C can't
 * see each other.
 *
 * The listener is bound in [start], on [port] or any free one, so the port is known before
 * the publish advertises it. It is bound to the wildcard address (the NAN interface only
 * exists while a data path is up), so [admit] checks every accepted connection instead: both
 * ends link-local IPv6 and ours on an Aware interface (named by the data paths'
 * `LinkProperties`, or `aware_*`), at most [MAX_LINKS] links, and a link that sends nothing
 * in its first [FIRST_FRAME_MS] is dropped. Every link reads with a [READ_TIMEOUT_MS]
 * timeout and TCP keep-alive: hellos come every second, so silence means the path is dead.
 *
 * Reconnect, at three levels:
 * - A link to a peer we dialled is one [Dial]. When it drops, times out or its data path
 *   is lost, the dial ends once and the next one is scheduled with [Backoff], for as long
 *   as discovery still sees that peer. `onServiceLost` (Android 11+) forgets the peer; a
 *   peer not rediscovered for [PEER_TTL_MS] is forgotten anyway, for Android 10. At most
 *   [MAX_DIALS] dials are in flight; the rest wait their turn.
 * - Accepted links are the other side's to restore: it dialled us, it dials again.
 * - The whole Aware session dies when Wi-Fi is turned off. The state broadcast and a
 *   backoff re-attach bring it back, republishing and resubscribing from scratch; a
 *   publish or subscribe that fails is retried the same way.
 *
 * Threads: `ptt-aware-accept` serves the listener, `ptt-aware-dial-<peer>` connects, and every
 * link has a `ptt-aware-rx-<peer>` reader and a `ptt-aware-tx-<peer>` writer draining a
 * [SendQueue], so [send] never waits on a dead path. [stop] sets flags and leaves the closes
 * to `ptt-aware-stop`.
 *
 * Requires Android 10+ (API 29) for WifiAwareNetworkInfo; the publisher uses
 * accept-any on Android 12+, otherwise it waits for a wake-up message (the SSI again,
 * checked the same way) and requests the path per peer.
 */
@SuppressLint("MissingPermission")
class WifiAwareTransport(
    context: Context,
    private val localId: Int,
    private val passphrase: String,
    private val idTag: (Int) -> ByteArray,
    private val port: Int = 0
) : Transport {

    override val name = "Aware"
    override val relayWithin = true
    override val ready: Boolean get() = session != null || links.isNotEmpty()

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var ssi = ByteArray(0)                                // id || tag, built in start()

    @Volatile private var running = false
    @Volatile private var localPort = 0                           // what the listener got; advertised to peers
    @Volatile private var session: WifiAwareSession? = null
    @Volatile private var publish: PublishDiscoverySession? = null
    @Volatile private var subscribe: SubscribeDiscoverySession? = null
    @Volatile private var server: ServerSocket? = null
    private val links = CopyOnWriteArrayList<Link>()
    private val responderCallbacks = CopyOnWriteArrayList<ConnectivityManager.NetworkCallback>()
    private val peers = PeerTable<Int, PeerHandle>(PEER_TTL_MS, MAX_PEERS)   // what discovery has seen lately
    private val dials = ConcurrentHashMap<Int, Dial>()            // peers we are dialling or linked to
    private val backoffs = ConcurrentHashMap<Int, Backoff>()
    private val awareIfaces = ConcurrentHashMap.newKeySet<String>()   // NAN interface names the data paths reported
    private val attachBackoff = Backoff()
    private val discoveryBackoff = Backoff()
    private val attaching = AtomicBoolean()                       // one WifiAwareManager.attach() in flight at most
    private val lifecycle = Any()                                 // orders "add a link" against "stop and close them all"
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    /** One TCP data-path connection; the token the engine gets as `link`. */
    private class Link(val stream: StreamLink) {
        @Volatile var heard = false
    }

    /** Aware comes and goes with Wi-Fi; re-attach when it is back, drop everything when it is gone. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!running) return
            if (manager?.isAvailable == true) {
                if (session == null) attach()
            } else if (session != null) {
                dropSession()
                onStatus("Aware: unavailable (Wi-Fi off?), waiting")
            }
        }
    }

    /** Forgets peers not rediscovered lately (and their backoffs), once a minute; a linked peer counts as seen. */
    private val sweep = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            for (d in dials.values) if (d.link != null) peers.touch(d.peerId, now)
            for (id in peers.expire(now)) {
                dials.remove(id)?.abandon()
                backoffs.remove(id)
                onStatus("Aware: ${hex(id)} not seen for ${PEER_TTL_MS / 60_000} min, forgotten")
            }
            backoffs.keys.retainAll(peers.keys.toSet())
            handler.postDelayed(this, SWEEP_MS)
        }
    }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        if (manager == null) {
            onStatus("Aware: not supported on this phone")
            return
        }
        ssi = AwareSsi.encode(localId, idTag)
        val srv = listen()                                        // throws: the engine reports and drops us
        running = true
        localPort = srv.localPort
        server = srv
        transportThread("ptt-aware-accept", { onStatus("Aware accept stopped: ${it.message}") }) { acceptLoop(srv) }
        ContextCompat.registerReceiver(
            appContext, stateReceiver,
            IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.postDelayed(sweep, SWEEP_MS)
        attach()
    }

    // ---- session ------------------------------------------------------------------

    /**
     * Attaches, or re-attaches after the session died; waits with backoff while Aware is
     * unavailable. The retry timer and the state broadcast can both land here, so only one
     * attach() is ever in flight, and a callback from an attach that is no longer current
     * (its session already replaced) is ignored rather than allowed to tear down the new one.
     */
    private fun attach() {
        if (!running || session != null) return
        if (!attaching.compareAndSet(false, true)) return
        val m = manager
        if (m == null || !m.isAvailable) {
            attaching.set(false)
            if (m != null) retryAttach("Aware: unavailable (Wi-Fi off?), waiting")
            return
        }
        try {
            m.attach(object : AttachCallback() {
                private var mine: WifiAwareSession? = null

                override fun onAttached(s: WifiAwareSession) {
                    attaching.set(false)
                    if (!running || session != null) { s.close(); return }   // stopped, or another attach won
                    mine = s
                    session = s
                    attachBackoff.reset()
                    startPublish(s)
                    startSubscribe(s)
                    onStatus("Aware: attached, discovering…")
                }
                override fun onAttachFailed() {
                    attaching.set(false)
                    retryAttach("Aware: attach failed, retrying")
                }
                override fun onAwareSessionTerminated() {
                    if (!running || mine == null || session !== mine) return   // stale: not the session in use
                    dropSession()
                    retryAttach("Aware: session ended, re-attaching")
                }
            }, handler)
        } catch (t: Throwable) {                                  // SecurityException: terminal, reported once
            attaching.set(false)
            onStatus("Aware attach: ${t.message}")
        }
    }

    private fun retryAttach(why: String) {
        if (!running) return
        onStatus(why)
        handler.postDelayed({ attach() }, attachBackoff.next())
    }

    /** Publish or subscribe failed or ended: try again with backoff, on the same session only. */
    private fun retryDiscovery(s: WifiAwareSession, why: String, again: (WifiAwareSession) -> Unit) {
        if (!running || session !== s) return
        onStatus(why)
        handler.postDelayed({ if (running && session === s) again(s) }, discoveryBackoff.next())
    }

    /** Responder requests belong to the publish session; drop them whenever it goes. */
    private fun clearResponders() {
        for (cb in responderCallbacks) unregister(cb)
        responderCallbacks.clear()
    }

    /** Forgets peers, dials, discovery sessions and links: all of it hangs off the session. */
    private fun dropSession() {
        clearResponders()
        for (d in dials.values) d.abandon()
        dials.clear()
        peers.clear()
        publish?.close(); publish = null
        subscribe?.close(); subscribe = null
        session?.close(); session = null
        for (link in links) link.stream.close()     // their NAN interface is gone anyway
    }

    private fun startPublish(s: WifiAwareSession) {
        val cfg = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(ssi)
            .build()
        try {
            s.publish(cfg, object : DiscoverySessionCallback() {
                override fun onPublishStarted(ps: PublishDiscoverySession) {
                    publish = ps
                    discoveryBackoff.reset()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Responder accepting any initiator: one request covers all peers, and it
                        // survives individual data paths coming and going.
                        reporting(onStatus, "Aware responder") {
                            val spec = WifiAwareNetworkSpecifier.Builder(ps)
                                .setPskPassphrase(passphrase).setPort(localPort).build()
                            requestResponder(spec)
                        }
                    }
                }
                override fun onSessionConfigFailed() {
                    retryDiscovery(s, "Aware: publish failed, retrying") { startPublish(it) }
                }
                override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
                    if (AwareSsi.decode(message, idTag) == null) return   // not one of ours: no path for it
                    reporting(onStatus, "Aware responder") {
                        val ps = publish ?: return@reporting
                        val spec = WifiAwareNetworkSpecifier.Builder(ps, peer)
                            .setPskPassphrase(passphrase).setPort(localPort).build()
                        requestResponder(spec)
                    }
                }
                override fun onSessionTerminated() {
                    publish = null
                    clearResponders()                   // the restart registers fresh ones
                    retryDiscovery(s, "Aware: publish ended, restarting") { startPublish(it) }
                }
            }, handler)
        } catch (t: Throwable) {
            retryDiscovery(s, "Aware publish: ${t.message}") { startPublish(it) }
        }
    }

    private fun startSubscribe(s: WifiAwareSession) {
        val cfg = SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build()
        try {
            s.subscribe(cfg, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(ss: SubscribeDiscoverySession) {
                    subscribe = ss
                    discoveryBackoff.reset()
                }

                override fun onServiceDiscovered(peer: PeerHandle, ssi: ByteArray?, filters: List<ByteArray>?) {
                    val peerId = AwareSsi.decode(ssi, idTag) ?: return     // not ours, or a forged id: ignored
                    if (peerId == localId) return
                    peers.put(peerId, peer, System.currentTimeMillis())    // a fresh handle each time it (re)appears
                    // Tie-break: lower id initiates, so each pair gets exactly one link.
                    if (localId < peerId) dial(peerId)
                }

                /** Android 11+: the peer went out of range. Forget it; rediscovery starts a new dial. */
                override fun onServiceLost(peer: PeerHandle, reason: Int) {
                    val id = peers.keyWhere { it == peer } ?: return
                    peers.remove(id)
                    dials.remove(id)?.abandon()
                    if (running) onStatus("Aware: lost ${hex(id)}")
                }

                override fun onSessionConfigFailed() {
                    retryDiscovery(s, "Aware: subscribe failed, retrying") { startSubscribe(it) }
                }

                override fun onSessionTerminated() {
                    subscribe = null
                    retryDiscovery(s, "Aware: subscribe ended, restarting") { startSubscribe(it) }
                }
            }, handler)
        } catch (t: Throwable) {
            retryDiscovery(s, "Aware subscribe: ${t.message}") { startSubscribe(it) }
        }
    }

    // ---- data paths ---------------------------------------------------------------

    private fun request(spec: WifiAwareNetworkSpecifier): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

    /** Responder side: keep the request registered; peers dial our [server] when their path is up. */
    private fun requestResponder(spec: WifiAwareNetworkSpecifier) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { noteInterface(lp) }
        }
        responderCallbacks.add(cb)
        connectivity.requestNetwork(request(spec), cb)
    }

    /** Remembers the NAN interface a data path runs on, for [admit]. */
    private fun noteInterface(lp: LinkProperties) {
        lp.interfaceName?.let { awareIfaces.add(it) }
    }

    /**
     * Initiator side: one [Dial] per peer at a time, at most [MAX_DIALS] in flight; the dial
     * schedules its own successor when it ends, and a peer over the cap is retried later.
     */
    private fun dial(peerId: Int) {
        if (!running) return
        val peer = peers.get(peerId) ?: return
        val ss = subscribe ?: return
        if (dials.containsKey(peerId)) return
        if (dials.size >= MAX_DIALS) {
            handler.postDelayed({ dial(peerId) }, backoffFor(peerId).next())
            return
        }
        val d = Dial(peerId)
        if (dials.putIfAbsent(peerId, d) != null) return
        onStatus("Aware: connecting to ${hex(peerId)}")
        try {
            ss.sendMessage(peer, 0, ssi)                  // wakes pre-Android-12 publishers
            val spec = WifiAwareNetworkSpecifier.Builder(ss, peer).setPskPassphrase(passphrase).build()
            connectivity.requestNetwork(request(spec), d, DIAL_TIMEOUT_MS)
        } catch (e: Exception) {
            d.fail("Aware: request for ${hex(peerId)} failed (${e.message})")
        }
    }

    private fun backoffFor(peerId: Int) = backoffs.computeIfAbsent(peerId) { Backoff() }

    private fun unregister(cb: ConnectivityManager.NetworkCallback) {
        try { connectivity.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    /**
     * One attempt to hold a link to [peerId]: request the data path, dial the peer's port
     * when it appears, then keep both until something ends it. Ends exactly once, whichever
     * of the link reader, the network callback or the timeout gets there first; a socket
     * that lands after that is closed by [addLink], never registered.
     */
    private inner class Dial(val peerId: Int) : ConnectivityManager.NetworkCallback() {
        private val dialing = AtomicBoolean()
        val finished = AtomicBoolean()
        @Volatile var link: StreamLink? = null

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = noteInterface(lp)

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
            val addr = info.peerIpv6Addr ?: return
            if (finished.get() || !dialing.compareAndSet(false, true)) return
            if (info.port <= 0) { fail("Aware: ${hex(peerId)} advertises no port"); return }
            transportThread("ptt-aware-dial-${hex(peerId)}", { fail("Aware: dial died (${it.message})") }) {
                var sock: Socket? = null
                try {
                    sock = network.socketFactory.createSocket()
                    sock.connect(InetSocketAddress(addr, info.port), DIAL_TIMEOUT_MS)
                    backoffFor(peerId).reset()
                    addLink(sock, "connected to ${hex(peerId)}", this)
                } catch (e: IOException) {
                    try { sock?.close() } catch (_: IOException) {}
                    fail("Aware: dial ${hex(peerId)} failed (${e.message})")
                }
            }
        }
        override fun onLost(network: Network) = fail("Aware: path to ${hex(peerId)} lost")
        override fun onUnavailable() = fail("Aware: ${hex(peerId)} did not answer")

        /** Ends this attempt and, while discovery still sees the peer, schedules the next one. */
        fun fail(why: String) {
            if (!finish()) return
            if (!running) return
            onStatus(why)
            if (peers.contains(peerId)) handler.postDelayed({ dial(peerId) }, backoffFor(peerId).next())
        }

        /** Ends this attempt silently, for teardown. */
        fun abandon() { finish() }

        private fun finish(): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            unregister(this)
            dials.remove(peerId, this)
            synchronized(lifecycle) { link?.close() }   // ordered against addLink, which checks `finished` under the same lock
            return true
        }
    }

    // ---- links --------------------------------------------------------------------

    /** The listener, on [port] or (0) any free one; re-bound to the same port after a failure, since that is what peers were told. */
    private fun listen(): ServerSocket {
        val srv = ServerSocket()
        try {
            srv.reuseAddress = true
            srv.bind(InetSocketAddress(if (localPort != 0) localPort else port))
        } catch (e: IOException) {
            srv.close()
            throw e
        }
        return srv
    }

    /** Serves incoming data-path connections for the whole session, starting with [first]; re-listens if the socket dies. */
    private fun acceptLoop(first: ServerSocket) {
        val backoff = Backoff()
        var srv: ServerSocket? = first
        while (running) {
            if (srv == null) {
                srv = try {
                    listen()
                } catch (e: SecurityException) {
                    onStatus("Aware: no permission to listen (${e.message})")
                    return
                } catch (e: IOException) {
                    val wait = backoff.next()
                    onStatus("Aware: can't listen on $localPort (${e.message}), retry in ${wait / 1000}s")
                    if (!sleepQuietly(wait)) return
                    continue
                }
                synchronized(lifecycle) {
                    if (!running) { closeQuietly(srv); return }
                    server = srv
                }
            }
            backoff.reset()
            try {
                while (running) {
                    val s = try { srv.accept() } catch (_: IOException) { break }
                    try {
                        admit(s)
                    } catch (e: Exception) {            // the peer reset between accept and setup; keep serving
                        try { s.close() } catch (_: IOException) {}
                        onStatus("Aware: accept failed (${e.message})")
                    }
                }
            } finally {
                server = null
                closeQuietly(srv)
            }
            srv = null
            if (running && !sleepQuietly(backoff.next())) return
        }
    }

    /**
     * An accepted connection becomes a link only if it came over a NAN data path: the
     * listener is on the wildcard address and the port is reachable from the WLAN too.
     * Content is protected by the AEAD either way; this keeps a stranger from holding a
     * reader thread and a copy of the traffic.
     */
    private fun admit(s: Socket) {
        val why = when {
            !onAwarePath(s) -> "not an Aware path"
            links.size >= MAX_LINKS -> "link limit"
            else -> null
        }
        if (why != null) {
            s.close()
            onStatus("Aware: refused ${s.inetAddress.hostAddress} ($why)")
            return
        }
        addLink(s, "accepted ${s.inetAddress.hostAddress}", null)
    }

    /** Both ends link-local IPv6, and ours on an interface a data path reported (or one named like one). */
    private fun onAwarePath(s: Socket): Boolean {
        val remote = s.inetAddress as? Inet6Address ?: return false
        val local = s.localAddress as? Inet6Address ?: return false
        if (!remote.isLinkLocalAddress || !local.isLinkLocalAddress) return false
        val nic = local.scopedInterface?.name
            ?: (try { NetworkInterface.getByInetAddress(local)?.name } catch (_: Exception) { null })
            ?: return false
        return nic in awareIfaces || nic.startsWith("aware")
    }

    /**
     * Registers a connected socket as a link, unless [stop] already ran or the [Dial] that
     * made it has ended — then it is closed instead. Starts its reader and writer.
     */
    private fun addLink(socket: Socket, why: String, dial: Dial?) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = if (dial == null) FIRST_FRAME_MS else READ_TIMEOUT_MS   // accepted: nothing in 10 s means not a peer of ours
        val label = socket.inetAddress.hostAddress ?: "?"
        val stream = StreamLink(label, socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        val link = Link(stream)
        synchronized(lifecycle) {
            if (!running || dial?.finished?.get() == true || links.size >= MAX_LINKS) { stream.close(); return }
            dial?.link = stream
            links.add(link)
        }
        onStatus("Aware: $why (${links.size} link${if (links.size == 1) "" else "s"})")
        val peer = dial?.let { hex(it.peerId) } ?: label
        transportThread("ptt-aware-tx-$peer", { onStatus("Aware tx stopped: ${it.message}") }) { stream.sendLoop() }
        transportThread("ptt-aware-rx-$peer", { onStatus("Aware rx stopped: ${it.message}") }) {
            try {
                stream.readLoop { p ->
                    if (!link.heard) {
                        link.heard = true
                        socket.soTimeout = READ_TIMEOUT_MS        // from here on a second's hellos keep it alive
                    }
                    onPacket(p, this, link)
                }
            } catch (e: SocketTimeoutException) {
                if (running && dial == null) onStatus("Aware: $label silent, dropped")
            } catch (e: IOException) {
                if (running && dial == null) onStatus("Aware: $label dropped")
            } finally {
                links.remove(link)
                stream.close()
                dial?.fail("Aware: ${hex(dial.peerId)} dropped, reconnecting")
            }
        }
    }

    override fun send(packet: ByteArray, except: Any?): Boolean {
        var sent = false
        for (link in links) {
            if (link === except) continue
            sent = true
            link.stream.offer(packet)                   // a stuck link closes itself; its reader tears it down
        }
        return sent
    }

    /** Sets the flags and unregisters; the closes (sockets and the Aware session, all IPC) run on `ptt-aware-stop`. */
    override fun stop() {
        val toClose: List<Link>
        val srv: ServerSocket?
        synchronized(lifecycle) {
            running = false
            toClose = links.toList()
            links.clear()
            srv = server
            server = null
        }
        handler.removeCallbacksAndMessages(null)          // pending re-attach, re-dial and sweep timers
        try { appContext.unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        transportThread("ptt-aware-stop", { /* a close that failed has nothing left to report */ }) {
            for (l in toClose) l.stream.close()
            closeQuietly(srv)
            dropSession()
            backoffs.clear()
        }
    }

    private fun closeQuietly(srv: ServerSocket?) {
        try { srv?.close() } catch (_: IOException) {}
    }

    companion object {
        const val SERVICE_NAME = "crew_radio"
        /** Concurrent links, dialled and accepted together. */
        const val MAX_LINKS = 16
        /** Data-path requests in flight at once; the framework refuses past a hundred outstanding. */
        const val MAX_DIALS = 8
        /** Discovered peers remembered at once. */
        const val MAX_PEERS = 64
        /** A peer not rediscovered for this long is forgotten (Android 10 has no `onServiceLost`). */
        const val PEER_TTL_MS = 5 * 60_000L
        /** Hellos come every second; five of them missing means the path is dead. */
        const val READ_TIMEOUT_MS = 5_000
        /** An accepted link that has sent nothing yet gets this long to prove itself. */
        const val FIRST_FRAME_MS = 10_000
        private const val SWEEP_MS = 60_000L
        private const val DIAL_TIMEOUT_MS = 20_000
    }
}
