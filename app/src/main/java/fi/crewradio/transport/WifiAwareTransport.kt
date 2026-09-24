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
import fi.crewradio.R
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
    /** Responder requests, one per key: [ANY_PEER] on API 31+, the initiator's node id below it. */
    private val responderCallbacks = ConcurrentHashMap<Any, ConnectivityManager.NetworkCallback>()
    private val peers = PeerTable<Int, PeerHandle>(PEER_TTL_MS, MAX_PEERS)   // what discovery has seen lately
    private val dials = ConcurrentHashMap<Int, Dial>()            // peers we are dialling or linked to
    private val backoffs = ConcurrentHashMap<Int, Backoff>()
    private val awareIfaces = ConcurrentHashMap.newKeySet<String>()   // NAN interface names the data paths reported
    private val attachBackoff = Backoff()
    // One each: shared, a subscribe that keeps starting cleanly would reset the wait for a publish
    // that keeps failing, and the failing side would retry at the minimum interval all session.
    private val publishBackoff = Backoff()
    private val subscribeBackoff = Backoff()
    private val attaching = AtomicBoolean()                       // one WifiAwareManager.attach() in flight at most
    private val lifecycle = Any()                                 // orders "add a link" against "stop and close them all"
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    private fun str(id: Int, vararg args: Any?): String = appContext.getString(id, *args)

    /** A responder request the framework refused, from a discovery callback on the main thread. */
    private fun responderFailed(t: Throwable) = onStatus(str(R.string.status_aware_responder_error, t.message))

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
                onStatus(str(R.string.status_aware_unavailable))
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
                onStatus(str(R.string.status_aware_forgotten, hex(id), PEER_TTL_MS / 60_000))
            }
            backoffs.keys.retainAll(peers.keys.toSet())
            handler.postDelayed(this, SWEEP_MS)
        }
    }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        if (manager == null) {
            onStatus(str(R.string.status_aware_unsupported))
            return
        }
        ssi = AwareSsi.encode(localId, idTag)
        val srv = listen()                                        // throws: the engine reports and drops us
        running = true
        localPort = srv.localPort
        server = srv
        transportThread("ptt-aware-accept", { onStatus(str(R.string.status_aware_accept_stopped, it.message)) }) { acceptLoop(srv) }
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
            if (m != null) retryAttach(str(R.string.status_aware_unavailable))
            return
        }
        try {
            m.attach(object : AttachCallback() {
                private var mine: WifiAwareSession? = null

                override fun onAttached(s: WifiAwareSession) {
                    attaching.set(false)
                    // Under the lock stop() clears `running` with, so its dropSession() sees this session.
                    synchronized(lifecycle) {
                        if (!running || session != null) { s.close(); return }   // stopped, or another attach won
                        mine = s
                        session = s
                    }
                    attachBackoff.reset()
                    startPublish(s)
                    startSubscribe(s)
                    onStatus(str(R.string.status_aware_attached))
                }
                override fun onAttachFailed() {
                    attaching.set(false)
                    retryAttach(str(R.string.status_aware_attach_failed))
                }
                override fun onAwareSessionTerminated() {
                    if (!running || mine == null || session !== mine) return   // stale: not the session in use
                    dropSession()
                    retryAttach(str(R.string.status_aware_session_ended))
                }
            }, handler)
        } catch (t: Throwable) {                                  // SecurityException: terminal, reported once
            attaching.set(false)
            onStatus(str(R.string.status_aware_attach_error, t.message))
        }
    }

    private fun retryAttach(why: String) {
        if (!running) return
        onStatus(why)
        handler.postDelayed({ attach() }, attachBackoff.next())
    }

    /** Publish or subscribe failed or ended: try again with backoff, on the same session only. */
    private fun retryDiscovery(s: WifiAwareSession, backoff: Backoff, why: String, again: (WifiAwareSession) -> Unit) {
        if (!running || session !== s) return
        onStatus(why)
        handler.postDelayed({ if (running && session === s) again(s) }, backoff.next())
    }

    /** Responder requests belong to the publish session; drop them whenever it goes. */
    private fun clearResponders() {
        for (key in responderCallbacks.keys) responderCallbacks.remove(key)?.let(::unregister)
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
                    if (!running || session !== s) { ps.close(); return }   // dropped meanwhile: nothing to clear it later
                    publish = ps
                    publishBackoff.reset()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Responder accepting any initiator: one request covers all peers, and it
                        // survives individual data paths coming and going.
                        reporting(::responderFailed) {
                            val spec = WifiAwareNetworkSpecifier.Builder(ps)
                                .setPskPassphrase(passphrase).setPort(localPort).build()
                            requestResponder(ANY_PEER, spec)
                        }
                    }
                }
                override fun onSessionConfigFailed() {
                    retryDiscovery(s, publishBackoff, str(R.string.status_aware_publish_failed)) { startPublish(it) }
                }
                override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
                    if (!running || session !== s) return
                    val from = AwareSsi.decode(message, idTag) ?: return   // not one of ours: no path for it
                    reporting(::responderFailed) {
                        val ps = publish ?: return@reporting
                        val spec = WifiAwareNetworkSpecifier.Builder(ps, peer)
                            .setPskPassphrase(passphrase).setPort(localPort).build()
                        requestResponder(from, spec)             // the peer redials: replaces its last request
                    }
                }
                override fun onSessionTerminated() {
                    publish = null
                    clearResponders()                   // the restart registers fresh ones
                    retryDiscovery(s, publishBackoff, str(R.string.status_aware_publish_ended)) { startPublish(it) }
                }
            }, handler)
        } catch (t: Throwable) {
            retryDiscovery(s, publishBackoff, str(R.string.status_aware_publish_error, t.message)) { startPublish(it) }
        }
    }

    private fun startSubscribe(s: WifiAwareSession) {
        val cfg = SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build()
        try {
            s.subscribe(cfg, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(ss: SubscribeDiscoverySession) {
                    subscribe = ss
                    subscribeBackoff.reset()
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
                    if (running) onStatus(str(R.string.status_aware_lost, hex(id)))
                }

                override fun onSessionConfigFailed() {
                    retryDiscovery(s, subscribeBackoff, str(R.string.status_aware_subscribe_failed)) { startSubscribe(it) }
                }

                override fun onSessionTerminated() {
                    subscribe = null
                    retryDiscovery(s, subscribeBackoff, str(R.string.status_aware_subscribe_ended)) { startSubscribe(it) }
                }
            }, handler)
        } catch (t: Throwable) {
            retryDiscovery(s, subscribeBackoff, str(R.string.status_aware_subscribe_error, t.message)) { startSubscribe(it) }
        }
    }

    // ---- data paths ---------------------------------------------------------------

    private fun request(spec: WifiAwareNetworkSpecifier): NetworkRequest =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

    /**
     * Responder side: keep the request registered; peers dial our [server] when their path is up.
     * One per [key], the older one released: below API 31 every dial attempt of a peer asks for a
     * fresh request, and piling them up reaches the framework's cap of about a hundred.
     */
    private fun requestResponder(key: Any, spec: WifiAwareNetworkSpecifier) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { noteInterface(lp) }
        }
        responderCallbacks.put(key, cb)?.let(::unregister)
        connectivity.requestNetwork(request(spec), cb)
        if (!running && responderCallbacks.remove(key, cb)) unregister(cb)   // stop() cleared before we added it
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
        onStatus(str(R.string.status_aware_connecting, hex(peerId)))
        try {
            ss.sendMessage(peer, 0, ssi)                  // wakes pre-Android-12 publishers
            val spec = WifiAwareNetworkSpecifier.Builder(ss, peer).setPskPassphrase(passphrase).build()
            connectivity.requestNetwork(request(spec), d, DIAL_TIMEOUT_MS)
        } catch (e: Exception) {
            d.fail(str(R.string.status_aware_request_failed, hex(peerId), e.message))
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
            if (info.port <= 0) { fail(str(R.string.status_aware_no_port, hex(peerId))); return }
            transportThread("ptt-aware-dial-${hex(peerId)}", { fail(str(R.string.status_aware_dial_died, it.message)) }) {
                var sock: Socket? = null
                try {
                    sock = network.socketFactory.createSocket()
                    sock.connect(InetSocketAddress(addr, info.port), DIAL_TIMEOUT_MS)
                    backoffFor(peerId).reset()
                    addLink(sock, str(R.string.status_link_connected, hex(peerId)), this)
                } catch (e: IOException) {
                    try { sock?.close() } catch (_: IOException) {}
                    fail(str(R.string.status_aware_dial_failed, hex(peerId), e.message))
                }
            }
        }
        override fun onLost(network: Network) = fail(str(R.string.status_aware_path_lost, hex(peerId)))
        override fun onUnavailable() = fail(str(R.string.status_aware_no_answer, hex(peerId)))

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
                    onStatus(str(R.string.status_aware_no_listen_permission, e.message))
                    return
                } catch (e: IOException) {
                    val wait = backoff.next()
                    onStatus(str(R.string.status_aware_cant_listen, localPort, e.message, wait / 1000))
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
                        onStatus(str(R.string.status_aware_accept_failed, e.message))
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
            !onAwarePath(s) -> str(R.string.status_aware_not_aware_path)
            links.size >= MAX_LINKS -> str(R.string.status_aware_link_limit)
            else -> null
        }
        if (why != null) {
            s.close()
            onStatus(str(R.string.status_aware_refused, s.inetAddress.hostAddress, why))
            return
        }
        addLink(s, str(R.string.status_link_accepted, s.inetAddress.hostAddress), null)
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
        val full: Boolean
        synchronized(lifecycle) {
            full = links.size >= MAX_LINKS
            if (!running || dial?.finished?.get() == true || full) { stream.close() } else {
                dial?.link = stream
                links.add(link)
            }
        }
        if (!links.contains(link)) {
            // Turned away at the cap: end the dial, or it holds its request and never retries.
            if (full && running) dial?.fail(str(R.string.status_aware_link_limit_reached))
            return
        }
        val n = links.size
        onStatus(appContext.resources.getQuantityString(R.plurals.status_aware_link, n, why, n))
        val peer = dial?.let { hex(it.peerId) } ?: label
        transportThread("ptt-aware-tx-$peer", { onStatus(str(R.string.status_aware_tx_stopped, it.message)) }) { stream.sendLoop() }
        transportThread("ptt-aware-rx-$peer", { onStatus(str(R.string.status_aware_rx_stopped, it.message)) }) {
            try {
                stream.readLoop { p ->
                    if (!link.heard) {
                        link.heard = true
                        socket.soTimeout = READ_TIMEOUT_MS        // from here on a second's hellos keep it alive
                    }
                    onPacket(p, this, link)
                }
            } catch (e: SocketTimeoutException) {
                if (running && dial == null) onStatus(str(R.string.status_aware_silent, label))
            } catch (e: IOException) {
                if (running && dial == null) onStatus(str(R.string.status_aware_dropped, label))
            } finally {
                links.remove(link)
                stream.close()
                dial?.fail(str(R.string.status_aware_reconnecting, hex(dial.peerId)))
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
        /** [requestResponder] key of the API 31+ accept-any request. */
        private const val ANY_PEER = "any"
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
