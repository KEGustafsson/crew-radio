package fi.crewradio.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import fi.crewradio.R
import fi.crewradio.rns.AskCarry
import fi.crewradio.rns.ReticulumNode
import fi.crewradio.rns.RnsPacket
import java.io.IOException
import java.io.OutputStream
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
 * timeout); `ptt-rns-tx` writes from a [ReticulumSendQueue], so a stalled connection never blocks
 * the engine: everything waiting goes out in one write, voice too late to matter is dropped, and a
 * backlog alone never closes the connection, only a socket that has accepted nothing for
 * [ReticulumSendQueue.NO_PROGRESS_MS]. A connection that fails or drops is re-opened after
 * [Backoff]; the node keeps its peers across it and re-announces.
 *
 * Split horizon: the engine tells [learnSender] who sent an authentic, unrelayed packet on which
 * link, so each link knows its far end's sender id, and [sendAudio] leaves out the links to
 * senders that hear us on the boat already.
 */
class ReticulumTransport(
    context: Context,
    private val host: String,
    private val port: Int,
    tag: String,
    confirmKey: ByteArray
) : Transport {
    override val name = "Reticulum"
    override val relayWithin = false
    override val ready: Boolean get() = connected

    private val appContext = context.applicationContext
    /** host:port as the crew reads it, an IPv6 address in brackets. */
    private val where = if (':' in host) "[$host]:$port" else "$host:$port"
    private val backoff = Backoff()
    private val waiter = Waiter()
    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var socket: Socket? = null
    @Volatile private var queue: ReticulumSendQueue? = null
    /** Voice frames dropped as too late, or for room, on connections that have ended; [voiceDropped] adds the current one's. */
    private val droppedBefore = java.util.concurrent.atomic.AtomicLong()
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
        confirmKey,
        write = { raw -> enqueue(listOf(raw), false) },
        onLinks = { n -> if (running) onStatus(appContext.resources.getQuantityString(R.plurals.status_rns_links, n, n)) },
        writeBatch = { packets, voice -> enqueue(packets, voice) }
    )

    private fun str(id: Int, vararg args: Any?): String = appContext.getString(id, *args)

    override fun start(onPacket: (packet: ByteArray, transport: Transport, link: Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onStatus = onStatus
        running = true
        // Internet, unrestricted and trusted: the networks worth trying. A VPN too (the default
        // request leaves them out): a phone whose traffic must go through one, or a hub reached
        // only through one, would otherwise be bound past it.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, networkCallback)
        transportThread("ptt-rns-rx", { onStatus(str(R.string.status_rns_stopped, it.message)) }) { rxLoop(onPacket) }
    }

    override fun send(packet: ByteArray, except: Any?): Boolean = node.send(packet, except)

    /** An audio frame on every confirmed link except those whose far end [isLocal] names (split horizon). */
    fun sendAudio(packet: ByteArray, isLocal: (Int) -> Boolean): Boolean = node.send(packet, null, isLocal)

    override fun learnSender(link: Any?, senderId: Int) = node.learnFarEnd(link, senderId)

    /** Voice dropped by the send queue this session (too late to be worth writing, or for room). */
    val voiceDropped: Long get() = droppedBefore.get() + (queue?.let { it.aged + it.dropped } ?: 0L)

    /** True while at least one link has proved the key: somebody a question could reach. */
    val canAsk: Boolean get() = running && connected && node.linkCount > 0

    /**
     * Connected to the hub with at least one confirmed link whose far end is not on the boat
     * ([isLocal]; a far end not known yet counts as remote): what the hello's RNS_UP says.
     */
    fun linkedOffBoat(isLocal: (Int) -> Boolean): Boolean = connected && node.hasRemoteLink(isLocal)

    /**
     * Asks the boat's Crew Radio plugin a question over the links ([AskCarry] request message) and
     * blocks for the answer: asked again, same id, halfway through [timeoutMs] if nothing has come
     * back (a lost part loses the whole message). A refusal by then is the answer: the other links
     * are most likely phones, which never answer. Null when there is no confirmed link or nothing
     * answered. Blocking: the `ptt-ask` thread, never the main one.
     */
    internal fun ask(message: ByteArray, timeoutMs: Long = ASK_TIMEOUT_MS): AskCarry.Reply? {
        if (!running) return null
        val q = node.ask(message) ?: return null
        try {
            if (q.await(timeoutMs / 2)) return q.reply
            q.refusal?.let { return it }
            q.repeat()
            q.await(timeoutMs - timeoutMs / 2)
            return q.reply ?: q.refusal
        } finally {
            q.forget()
        }
    }

    override fun stop() {
        // The polite closes first, while the reader still runs: it clears the links the moment it
        // sees `running` false, and the tx thread flushes these before the socket goes.
        node.closeAll()
        running = false
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        closeSocketIfConnecting()
        waiter.wake()
    }

    /** A connect still in progress is cut short; a connected socket closes after the flush. */
    private fun closeSocketIfConnecting() {
        if (!connected) closeSocket()
    }

    /** One send's Reticulum packets to the tx thread, framed. A backlog never closes the connection ([ReticulumSendQueue]). */
    private fun enqueue(packets: List<ByteArray>, voice: Boolean) {
        val q = queue ?: return
        q.offer(packets.map { RnsPacket.frame(it) }, voice)
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
                local = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)),
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
        // The address is already in hand; the boat's router may have no upstream DNS to ask again.
        if (target == NetworkChoice.Target.NAME && NetworkChoice.target(address.hostAddress ?: "") == NetworkChoice.Target.PRIVATE) {
            return Route(NetworkChoice.pick(candidates, NetworkChoice.Target.PRIVATE), address)
        }
        return Route(network, address)
    }

    private fun rxLoop(onPacket: (ByteArray, Transport, Any?) -> Unit) {
        if (networks.isEmpty()) waiter.await(FIRST_NETWORK_MS)   // the callback reports the networks a moment after it is registered
        while (running) {
            val s = Socket()
            socket = s                                   // published first, so stop() can cut a connect short
            val out: OutputStream
            try {
                val way = route() ?: throw IOException(str(R.string.status_rns_no_network))
                way.network?.let { n ->
                    try {
                        n.bindSocket(s)
                        bound = n
                    } catch (_: IOException) {
                        bound = null                     // refused (a lockdown VPN): the routing table decides
                    }
                }
                s.connect(InetSocketAddress(way.address, port), CONNECT_TIMEOUT_MS)
                if (!running) throw IOException(str(R.string.status_rns_closed))
                s.tcpNoDelay = true
                s.soTimeout = TICK_MS.toInt()
                failFast(s)
                out = s.getOutputStream()
            } catch (e: Exception) {
                bound = null
                socket = null
                try { s.close() } catch (_: IOException) {}
                if (!running) break
                val wait = backoff.next()
                onStatus(str(R.string.status_rns_cant_connect, where, e.message, wait / 1000))
                waiter.await(wait)
                continue
            }
            val q = ReticulumSendQueue()
            queue = q
            transportThread("ptt-rns-tx", {}) {
                try {
                    while (true) {
                        val batch = q.take() ?: break   // everything waiting: one flush
                        // In chunks, each one accepted counting as progress: a slow socket that is
                        // still taking bytes is not the stall that ends the connection.
                        var at = 0
                        while (at < batch.size) {
                            val n = minOf(WRITE_CHUNK, batch.size - at)
                            out.write(batch, at, n)
                            at += n
                            q.progressed()
                        }
                        out.flush()
                        q.written()
                    }
                } catch (_: IOException) {
                    try { s.close() } catch (_: IOException) {}   // its own socket, never a newer one: the reader sees it and reconnects
                }
            }
            connected = true
            val connectedAt = System.nanoTime()
            onStatus(str(R.string.status_rns_connected, where))
            node.connected()
            var why: String? = null
            try {
                readLoop(s, q, onPacket)
            } catch (e: IOException) {
                why = e.message
            } finally {
                connected = false
                node.disconnected()
                if (!running) sleepQuietly(FLUSH_MS)   // the closes queued by stop() get their moment
                queue = null
                bound = null
                droppedBefore.addAndGet(q.aged + q.dropped)
                q.close()
                try { s.close() } catch (_: IOException) {}
                socket = null
            }
            if (!running) break
            // Only a connection that held resets the backoff: one accepted and dropped at once (a
            // port forward with nothing behind it, rnsd restarting) must not be redialled every second.
            if (System.nanoTime() - connectedAt >= STABLE_MS * 1_000_000) backoff.reset()
            val wait = backoff.next()
            onStatus(str(R.string.status_rns_lost, where, why ?: str(R.string.status_rns_closed), wait / 1000))
            waiter.await(wait)
        }
    }

    /**
     * Reads frames until the connection breaks or the transport stops; the read timeout is the
     * node's clock tick, and the moment to see whether the writer has been stuck for
     * [ReticulumSendQueue.NO_PROGRESS_MS], the one backlog that ends the connection.
     */
    private fun readLoop(s: Socket, q: ReticulumSendQueue, onPacket: (ByteArray, Transport, Any?) -> Unit) {
        val input = s.getInputStream()
        val buf = ByteArray(4096)
        var lastTick = System.nanoTime() / 1_000_000
        val deframer = RnsPacket.Deframer { raw ->
            try {
                for ((packet, via) in node.onFrame(raw)) onPacket(packet, this, via)   // the node's lock is not held here
            } catch (_: RuntimeException) {
                // One bad frame must not end reception for the session (LanTransport does the same).
            }
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
            val now = System.nanoTime() / 1_000_000       // monotonic: a clock stepped back must not stop the ticks
            if (now - lastTick >= TICK_MS) {
                lastTick = now
                if (q.stalled()) throw IOException(str(R.string.status_rns_no_progress, ReticulumSendQueue.NO_PROGRESS_MS / 1000))
                node.tick()
            }
        }
    }

    /**
     * A connection that dies without a word (a marina uplink gone while the Wi-Fi stays up, a NAT
     * mapping dropped, the hub losing power) is given up once data has gone unacknowledged for
     * [USER_TIMEOUT_MS], instead of after TCP's quarter of an hour of retransmissions; a confirmed
     * link carries a hello every second, so there is always data to notice it by. Best effort.
     */
    private fun failFast(s: Socket) {
        try {
            s.keepAlive = true
            ParcelFileDescriptor.fromSocket(s).use {
                Os.setsockoptInt(it.fileDescriptor, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            // Not supported here: TCP's own limits apply.
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val USER_TIMEOUT_MS = 20_000
        const val STABLE_MS = 30_000L
        const val TICK_MS = 1_000L
        const val FLUSH_MS = 150L
        const val FIRST_NETWORK_MS = 1_000L
        /** A question's whole wait: a hub round trip is a second or two, and it is asked twice. */
        const val ASK_TIMEOUT_MS = 8_000L
        /** A batch goes to the socket this much at a time, so the stall check sees a slow write move. */
        const val WRITE_CHUNK = 1024
    }
}
