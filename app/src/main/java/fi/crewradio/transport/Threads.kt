package fi.crewradio.transport

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * A named `ptt-*` transport thread whose uncaught exceptions are reported instead of
 * killing the process.
 *
 * Transport threads talk to the Bluetooth, Wi-Fi Aware and socket stacks, which throw
 * more than [java.io.IOException] — a missing runtime permission surfaces as
 * [SecurityException], a vendor stack can throw almost anything. An uncaught throwable on
 * a plain thread takes the whole app down, so every one of them ends up here and becomes
 * a status line instead.
 */
internal fun transportThread(name: String, onError: (Throwable) -> Unit, body: () -> Unit): Thread =
    thread(name = name) {
        try {
            body()
        } catch (t: Throwable) {
            onError(t)
        }
    }

/** Sleeps [ms]; false if interrupted, which is how a stopping transport ends a retry loop early. */
internal fun sleepQuietly(ms: Long): Boolean =
    try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

/**
 * Runs [block] and turns anything it throws into a status line. For calls made from
 * framework callbacks on the main thread, where an exception would otherwise crash the app.
 */
internal inline fun reporting(onStatus: (String) -> Unit, what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        onStatus("$what: ${t.message}")
    }
}

/** Node ids as they appear in status lines: unsigned hex, e.g. `58738d38`. */
internal fun hex(id: Int): String = id.toUInt().toString(16)

/**
 * A wait that a request cuts short — and that a request made *before* the wait skips.
 *
 * Every transport waits with [Backoff] between attempts, and something (a Wi-Fi change, the
 * Bluetooth adapter coming on, `stop()`) regularly wants the next attempt now. A semaphore
 * drained right before the wait lost the permit a request had just released, so a rejoin
 * still sat out the full first backoff; this remembers one pending wake instead.
 */
internal class Waiter {
    private val lock = ReentrantLock()
    private val wakeSignal = lock.newCondition()
    private var pending = false

    /** Cuts the current wait short, or the next one if none is in progress. */
    fun wake() = lock.withLock {
        pending = true
        wakeSignal.signalAll()
    }

    /** Waits up to [ms] unless a wake is pending or arrives; true if woken (or interrupted), false on timeout. */
    fun await(ms: Long): Boolean = lock.withLock {
        val end = System.nanoTime() + ms * 1_000_000
        while (!pending) {
            val left = (end - System.nanoTime()) / 1_000_000
            if (left <= 0) break                       // so await() is never handed a non-positive timeout
            try {
                // await() reports whether it returned before the deadline. False means the wait
                // ran out, and there is nothing more to wait for — unless a wake arrived at the
                // same moment, which is what the second half of the test is for. Reading the
                // answer here rather than going round again saves a clock read and, more to the
                // point, says which of the two happened instead of leaving it to be inferred.
                if (!wakeSignal.await(left, TimeUnit.MILLISECONDS) && !pending) break
            } catch (_: InterruptedException) {
                pending = false
                return true
            }
        }
        val woken = pending
        pending = false
        woken
    }
}
