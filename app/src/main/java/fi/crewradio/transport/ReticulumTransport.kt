package fi.crewradio.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import fi.crewradio.R
import fi.crewradio.rns.ReticulumNode
import fi.crewradio.rns.RnsPacket
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The channel over Reticulum: one TCP connection to a Reticulum transport node (the boat's
 * rnsd, or a hub ashore reached over mobile data), and Reticulum links to the crew's other
 * Reticulum nodes inside it, each carrying the channel's sealed packets unchanged
 * ([ReticulumNode] has the protocol; the plugin's lib/rns/transport.js is the same design).
 *
 * Reticulum does the multi-hop part itself and every node links to every other, so nothing is
 * relayed within this transport ([relayWithin] is false); a phone running it beside WLAN, or the
 * Signal K plugin, relays between the two like any bridge. The identity is new for every
 * session and never stored.
 *
 * The connection goes over whatever network the phone has that can reach the node
 * ([NetworkChoice]): for a hub on the internet, one that has actually reached the internet (so a
 * boat Wi-Fi without it does not swallow the connection while mobile data is up); for a node at a
 * private address, the Wi-Fi, or with no Wi-Fi the routing table (this phone's own hotspot). A name
 * the chosen network cannot resolve is asked of the local network too. The socket is bound to the
 * network, and when the network goes away the connection is dropped and re-opened over whatever is
 * there then.
 *
 * Threads: `ptt-rns-rx` owns the socket, reads frames and ticks the node once a second (a read
 * timeout); `ptt-rns-tx` writes from a [SendQueue], so a stalled connection never blocks the
 * engine. A connection that fails or drops is re-opened after [Backoff]; the node keeps its peers
 * across it and re-announces.
 */
class ReticulumTransport(
    context: Context,
    private val host: String,
    private val port: Int,
    tag: String
) : Transport {
    override val name = "Reticulum"
    override val relayWithin = false
    override val ready: Boolean get() = connected

    private val appContext = context.applicationContext
    private val backoff = Backoff()
    private val waiter = Waiter()
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var socket: Socket? = null
    @Volatile private var queue: SendQueue? = null
    @Volatile private var bound: Network? = null
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    /** Every network that says it offers the internet, with what it says about itself, kept by [networkCallback]. */
    private val networks = java.util.concurrent.ConcurrentHashMap<Network, NetworkCapabilities>()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val fresh = networks.put(network, caps) == null
            if (fresh && bound == null) waiter.wake()          // something to connect over, now
        }
        override fun onLost(network: Network) {
            networks.remove(network)
            if (network == bound) closeSocket()                // the reader ends, and reconnects over what is left
        }
    }
    private lateinit var onStatus: (String) -> Unit
    private val node = ReticulumNode(
        tag,
        write = { raw -> enqueue(raw) },
        onLinks = { n -> if (running) onStatus(appContext.resources.getQuantityString(R.plurals.status_rns_links, n, n)) }
    )

    private fun str(id: Int, vararg args: Any?): String = appContext.getString(id, *args)

    override fun start(onPacket: (packet: ByteArray, transport: Transport, link: Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onStatus = onStatus
        running = true
        // The default request asks for internet, unrestricted, trusted and not a VPN: the networks worth trying.
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback)
        transportThread("ptt-rns-rx", { onStatus(str(R.string.status_rns_stopped, it.message)) }) { rxLoop(onPacket) }
    }

    override fun send(packet: ByteArray, except: Any?): Boolean = node.send(packet, except)

    override fun confirmPeer(link: Any?) = node.confirm(link)

    override fun stop() {
        running = false
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        node.closeAll()                       // polite closes, flushed by the tx thread before the socket goes
        waiter.wake()
    }

    /** One Reticulum packet to the tx thread; a queue stuck full means the connection is dead, so it is closed and re-opened. */
    private fun enqueue(raw: ByteArray) {
        val q = queue ?: return
        if (!q.offer(RnsPacket.frame(raw))) closeSocket()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
    }

    /** Where the socket goes: bound to [network], or over the routing table when it is null. */
    private class Route(val network: Network?, val address: InetAddress)

    /** The way to the node now and its address resolved on it, or null when there is none. */
    private fun route(): Route? {
        val default = connectivity.activeNetwork
        val candidates = networks.entries.map { (n, caps) ->
            NetworkChoice.Candidate(
                n,
                local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isDefault = n == default
            )
        }
        val name = host.removePrefix("[").removeSuffix("]")
        val target = NetworkChoice.target(host)
        val network = NetworkChoice.pick(candidates, target)
            ?: return if (NetworkChoice.unboundWhenNone(target)) Route(null, InetAddress.getByName(name)) else null
        val address = try {
            network.getByName(name)
        } catch (e: UnknownHostException) {
            if (target != NetworkChoice.Target.NAME) throw e
            val local = NetworkChoice.localRetry(candidates, network) ?: throw e
            return Route(local, local.getByName(name))
        }
        // A name that turns out to be a private address (boat.local) is on the local network after all,
        // or with none behind this phone's own hotspot, like a private address.
        if (target == NetworkChoice.Target.NAME && NetworkChoice.target(address.hostAddress ?: "") == NetworkChoice.Target.PRIVATE) {
            val local = NetworkChoice.pick(candidates, NetworkChoice.Target.PRIVATE) ?: return Route(null, address)
            return Route(local, local.getByName(name))
        }
        return Route(network, address)
    }

    private fun rxLoop(onPacket: (ByteArray, Transport, Any?) -> Unit) {
        if (networks.isEmpty()) waiter.await(FIRST_NETWORK_MS)   // the callback reports the networks a moment after it is registered
        while (running) {
            val s = Socket()
            try {
                val way = route() ?: throw IOException(str(R.string.status_rns_no_network))
                way.network?.bindSocket(s)
                bound = way.network
                s.connect(InetSocketAddress(way.address, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.soTimeout = TICK_MS.toInt()
            } catch (e: Exception) {
                bound = null
                try { s.close() } catch (_: IOException) {}
                if (!running) break
                val wait = backoff.next()
                onStatus(str(R.string.status_rns_cant_connect, "$host:$port", e.message, wait / 1000))
                waiter.await(wait)
                continue
            }
            socket = s
            val q = SendQueue(capacity = QUEUE_FRAMES)
            queue = q
            val out = s.getOutputStream()
            transportThread("ptt-rns-tx", {}) {
                try {
                    while (true) {
                        val frame = q.take() ?: break
                        out.write(frame)
                        out.flush()
                    }
                } catch (_: IOException) {
                    closeSocket()                  // the reader sees it and reconnects
                }
            }
            connected = true
            backoff.reset()
            onStatus(str(R.string.status_rns_connected, "$host:$port"))
            node.connected()
            var why: String? = null
            try {
                readLoop(s, onPacket)
            } catch (e: IOException) {
                why = e.message
            } finally {
                connected = false
                node.disconnected()
                if (!running) sleepQuietly(FLUSH_MS)   // the closes queued by stop() get their moment
                queue = null
                bound = null
                q.close()
                try { s.close() } catch (_: IOException) {}
                socket = null
            }
            if (!running) break
            val wait = backoff.next()
            onStatus(str(R.string.status_rns_lost, "$host:$port", why ?: str(R.string.status_rns_closed), wait / 1000))
            waiter.await(wait)
        }
    }

    /** Reads frames until the connection breaks or the transport stops; the read timeout is the node's clock tick. */
    private fun readLoop(s: Socket, onPacket: (ByteArray, Transport, Any?) -> Unit) {
        val input = s.getInputStream()
        val buf = ByteArray(4096)
        var lastTick = System.currentTimeMillis()
        val deframer = RnsPacket.Deframer { raw ->
            for ((packet, via) in node.onFrame(raw)) onPacket(packet, this, via)   // the node's lock is not held here
        }
        while (running) {
            var n: Int
            try {
                n = input.read(buf)                    // -1 at the end of the stream, checked below
            } catch (_: SocketTimeoutException) {
                n = 0                                  // no data this second: time to tick
            }
            if (n < 0) throw IOException(str(R.string.status_rns_closed))
            if (n > 0) deframer.push(buf, n)
            val now = System.currentTimeMillis()
            if (now - lastTick >= TICK_MS) {
                lastTick = now
                node.tick()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val TICK_MS = 1_000L
        const val FLUSH_MS = 150L
        const val FIRST_NETWORK_MS = 1_000L
        /** Whole Reticulum packets: several links' worth of frames, a PCM frame being two of them. */
        const val QUEUE_FRAMES = 128
    }
}
