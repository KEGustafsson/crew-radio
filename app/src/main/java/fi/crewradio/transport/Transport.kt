package fi.crewradio.transport

/**
 * A bidirectional link carrier. Implementations must be safe to call send()
 * from any thread and must deliver onPacket() from their own receive thread.
 *
 * `link` is an opaque token identifying the specific peer connection a packet
 * arrived on, so the relay can avoid echoing it straight back. Broadcast
 * transports (LAN multicast) pass the datagram's source address instead.
 */
interface Transport {
    val name: String

    /** True if forwarding a packet to *other* links of this same transport makes sense (BT, Aware). False for multicast. */
    val relayWithin: Boolean

    /**
     * True while this transport can actually carry packets right now — a listener up, a socket
     * open or a link alive — which is what the hello may claim. A Bluetooth transport whose
     * adapter is off is started but not ready. Defaults to "as soon as started".
     */
    val ready: Boolean get() = true

    /**
     * Called once a packet that arrived on [link] has passed the AEAD, so a transport that keeps
     * a table of peers learns only from senders that hold the channel key. Anything learned from
     * an unopened datagram is learned from whoever shouted loudest. Default: nothing to learn.
     */
    fun confirmPeer(link: Any?) {}

    fun start(onPacket: (packet: ByteArray, transport: Transport, link: Any?) -> Unit, onStatus: (String) -> Unit)
    /** Queues for every link but [except] and returns at once; true if the packet went to at least one. Send failures are transient and count as sent. */
    fun send(packet: ByteArray, except: Any? = null): Boolean
    /** Returns within a few milliseconds: flags are set here, sockets are closed by the transport's own threads. */
    fun stop()
}
