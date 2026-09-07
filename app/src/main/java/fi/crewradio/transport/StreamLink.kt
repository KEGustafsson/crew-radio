package fi.crewradio.transport

import fi.crewradio.Packet
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Length-prefixed (uint16 BE) packet framing over a stream socket. Shared by BT and Wi-Fi Aware.
 *
 * Writes go through a [SendQueue]: [offer] never blocks the caller, the link's own tx thread
 * runs [sendLoop] and does the writing. A frame longer than [Packet.MAX_SIZE] or no longer than
 * a bare header is not a packet, and a peer sending one has lost framing: the link is dropped.
 */
class StreamLink(
    val label: String,
    input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit
) {
    private val input = DataInputStream(input)
    private val queue = SendQueue()
    private val closed = AtomicBoolean()

    /** Frames dropped because the peer was not draining fast enough. */
    val dropped: Long get() = queue.dropped
    val isClosed: Boolean get() = closed.get()

    /**
     * Queues [packet] for the tx thread; never blocks. When the queue has been stuck full for
     * [SendQueue.STUCK_MS] the peer is gone: the link is closed here (which frees the blocked
     * writer and ends the reader, whose `finally` redials) and false comes back.
     */
    fun offer(packet: ByteArray): Boolean {
        if (queue.offer(packet)) return true
        close()
        return false
    }

    /** Writes queued packets until the link closes or a write fails; then closes it. Runs on the link's `ptt-*-tx-*` thread. */
    fun sendLoop() {
        try {
            while (true) write(queue.take() ?: return)
        } catch (_: IOException) {
            // the reader sees the same broken socket and tears the link down
        } finally {
            close()
        }
    }

    /** One framed write, synchronous. */
    fun write(packet: ByteArray) {
        synchronized(output) {
            output.write(packet.size ushr 8)
            output.write(packet.size and 0xFF)
            output.write(packet)
            output.flush()
        }
    }

    /** Blocks until the link breaks. */
    fun readLoop(onPacket: (ByteArray) -> Unit) {
        while (true) {
            val len = input.readUnsignedShort()
            if (len <= Packet.HEADER || len > Packet.MAX_SIZE) throw IOException("bad frame length $len")
            val buf = ByteArray(len)
            input.readFully(buf)
            onPacket(buf)
        }
    }

    /** Closes the socket and ends the tx thread; safe to call from anywhere, any number of times. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        queue.close()
        try { closer() } catch (_: Exception) {}
    }
}
