package fi.crewradio.transport

/**
 * Bounded outbound queue of one stream link: drop-oldest, and a "stuck" verdict.
 *
 * A write on a stream socket blocks when the peer stops reading — an RFCOMM peer walking out
 * of range blocks until link supervision gives up (~20 s), a dead NAN path until TCP's
 * retransmit horizon. Sends used to run on the caller's thread, so one stalled peer froze the
 * capture thread, the heartbeat and every receive thread that relays. Now a caller only
 * [offer]s and the link's own `ptt-*-tx-*` thread [take]s and writes.
 *
 * When the writer stops draining, the queue fills and the oldest frames go: audio older than
 * [CAPACITY] frames (320 ms) is stale anyway. Once it has stayed full for [STUCK_MS] the link is
 * as good as dead: [offer] returns false and the owner closes the link, which frees the writer
 * and makes the reader's `finally` redial.
 *
 * Pure Kotlin so the bound and the stuck rule are unit-tested; the clock is injectable.
 */
internal class SendQueue(
    private val capacity: Int = CAPACITY,
    private val stuckMs: Long = STUCK_MS,
    // Monotonic, not wall clock: a time adjustment while the queue is full would either hold a dead
    // link open until wall time caught up, or close a healthy one early.
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val lock = java.lang.Object()
    private val items = ArrayDeque<ByteArray>()
    private var fullSince = -1L                     // when the queue was last found full with nothing taken since
    private var closed = false

    /** Packets thrown away because the queue was full. */
    var dropped = 0L
        get() = synchronized(lock) { field }
        private set

    val size: Int get() = synchronized(lock) { items.size }
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * Queues [packet], dropping the oldest when full; never blocks. False once the queue has
     * been continuously full for the stuck time, or after [close]: the link is dead, close it.
     */
    fun offer(packet: ByteArray): Boolean = synchronized(lock) {
        if (closed) return false
        if (items.size >= capacity) {
            val now = clock()
            if (fullSince < 0) fullSince = now
            else if (now - fullSince >= stuckMs) return false
            items.removeFirst()
            dropped++
        }
        items.addLast(packet)
        lock.notifyAll()
        true
    }

    /** The next packet to write, waiting while there is none; null once closed (or interrupted). */
    fun take(): ByteArray? = synchronized(lock) {
        while (items.isEmpty() && !closed) {
            try {
                lock.wait()
            } catch (_: InterruptedException) {
                return null
            }
        }
        if (closed) return null
        fullSince = -1
        items.removeFirst()
    }

    /** [take] without waiting: null when empty or closed. */
    fun poll(): ByteArray? = synchronized(lock) {
        if (closed || items.isEmpty()) return null
        fullSince = -1
        items.removeFirst()
    }

    /** Ends the queue: everything queued is dropped and a blocked [take] returns null. */
    fun close() = synchronized(lock) {
        closed = true
        items.clear()
        lock.notifyAll()
    }

    companion object {
        /** 16 frames = 320 ms of audio: enough to ride out a scheduling hiccup, not enough to be heard as lag. */
        const val CAPACITY = 16
        /** A queue full this long means the writer is blocked on a peer that is gone. */
        const val STUCK_MS = 3_000L
    }
}
