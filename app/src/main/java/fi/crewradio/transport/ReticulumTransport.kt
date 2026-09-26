package fi.crewradio.transport

import android.content.Context
import fi.crewradio.R
import fi.crewradio.rns.ReticulumNode
import fi.crewradio.rns.RnsPacket
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

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
        transportThread("ptt-rns-rx", { onStatus(str(R.string.status_rns_stopped, it.message)) }) { rxLoop(onPacket) }
    }

    override fun send(packet: ByteArray, except: Any?): Boolean = node.send(packet, except)

    override fun confirmPeer(link: Any?) = node.confirm(link)

    override fun stop() {
        running = false
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

    private fun rxLoop(onPacket: (ByteArray, Transport, Any?) -> Unit) {
        while (running) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.soTimeout = TICK_MS.toInt()
            } catch (e: Exception) {
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
        /** Whole Reticulum packets: several links' worth of frames, a PCM frame being two of them. */
        const val QUEUE_FRAMES = 128
    }
}
