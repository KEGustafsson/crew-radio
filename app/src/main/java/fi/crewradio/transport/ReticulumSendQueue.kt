package fi.crewradio.transport

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The outbound queue of the Reticulum connection: what [ReticulumTransport]'s `ptt-rns-tx` thread
 * writes, and the rules that keep an overloaded hub from turning into a relink of every link.
 *
 *  - One item is everything one send produced: all the links' packets for one frame, already
 *    framed. The writer takes every item waiting and puts them out in ONE write and flush, so a
 *    frame for five links is one TCP segment, not five.
 *  - Voice older than [maxAgeMs] when its turn comes is dropped ([aged]): a late frame is worth
 *    nothing to a listener and only deepens the backlog. Hellos, key proofs, link control and
 *    questions are never dropped by age.
 *  - A backlog never closes the connection (the first design closed it after three seconds full,
 *    which turned a busy hub into a full relink of every link). The connection is given up only
 *    when bytes have been waiting and the socket has accepted none for [noProgressMs] ([stalled]),
 *    besides the transport's own dead-link rules.
 *  - The queue is bounded at [capacity] items all the same: the oldest voice goes first ([dropped]),
 *    and only with no voice left the oldest item of any kind.
 *
 * Pure Kotlin so the rules are unit-tested; the clock is monotonic and injectable.
 */
internal class ReticulumSendQueue(
    private val maxAgeMs: Long = VOICE_MAX_AGE_MS,
    private val noProgressMs: Long = NO_PROGRESS_MS,
    private val capacity: Int = CAPACITY,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private class Item(val parts: List<ByteArray>, val voice: Boolean, val at: Long)

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val items = ArrayDeque<Item>()
    private var closed = false
    /** When the bytes being written now were handed to the socket; -1 while no write is in progress. */
    private var writingSince = -1L

    /** Voice dropped because it was older than [maxAgeMs] when its turn came. */
    var aged = 0L
        get() = lock.withLock { field }
        private set

    /** Items dropped because the queue was full. */
    var dropped = 0L
        get() = lock.withLock { field }
        private set

    val size: Int get() = lock.withLock { items.size }

    /** Queues one send's packets ([voice]: audio, which may age out); never blocks, false only once closed. */
    fun offer(parts: List<ByteArray>, voice: Boolean): Boolean = lock.withLock {
        if (closed) return false
        if (parts.isEmpty()) return true
        if (items.size >= capacity) {
            val it = items.iterator()
            var removed = false
            while (it.hasNext()) if (it.next().voice) { it.remove(); removed = true; break }
            if (!removed) items.removeFirst()
            dropped++
        }
        items.addLast(Item(parts, voice, clock()))
        notEmpty.signalAll()
        true
    }

    /**
     * Everything waiting, as one buffer for one write, voice past its age left out; waits while
     * there is nothing to write. Marks the write as begun: call [written] when the socket took it.
     * Null once closed (or interrupted).
     */
    fun take(): ByteArray? {
        lock.lock()
        try {
            while (true) {
                while (items.isEmpty() && !closed) {
                    try {
                        notEmpty.await()
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
                if (closed) return null
                batch()?.let { return it }                      // null: all of it too old, wait for more
            }
        } finally {
            lock.unlock()
        }
    }

    /** Holds [lock]: takes every item, leaves aged voice out, and returns the rest as one buffer, or null when nothing is left. */
    private fun batch(): ByteArray? {
        val now = clock()
        var size = 0
        val parts = ArrayList<ByteArray>()
        while (items.isNotEmpty()) {
            val item = items.removeFirst()
            if (item.voice && now - item.at > maxAgeMs) { aged++; continue }
            for (p in item.parts) { parts.add(p); size += p.size }
        }
        if (parts.isEmpty()) return null
        writingSince = now
        val out = ByteArray(size)
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /** The socket accepted what [take] handed out: progress. */
    fun written() = lock.withLock { writingSince = -1L }

    /** True when bytes have been waiting on the socket, none accepted, for [noProgressMs]: give the connection up. */
    fun stalled(): Boolean = lock.withLock { writingSince >= 0 && clock() - writingSince >= noProgressMs }

    /** Ends the queue: everything queued is dropped and a blocked [take] returns null. */
    fun close() = lock.withLock {
        closed = true
        items.clear()
        notEmpty.signalAll()
    }

    companion object {
        /** Voice later than this when its turn to be written comes is dropped. */
        const val VOICE_MAX_AGE_MS = 200L
        /** A socket that has accepted nothing of what waits for this long is given up and redialled. */
        const val NO_PROGRESS_MS = 10_000L
        /** Items (one send each); far more than a healthy connection ever holds. */
        const val CAPACITY = 1024
    }
}
