package fi.crewradio

/**
 * Ingress budget for received packets, checked before anything else is spent on them.
 *
 * Three buckets. A global token bucket ([globalPerSecond], [globalBurst]) bounds what this
 * phone will look at in total, whatever sender ids the packets claim: sender ids are chosen by
 * the sender, so a flood that rotates them cannot buy itself more. It is sized to the CPU, not
 * to the traffic: opening a packet costs microseconds, so the ceiling is far above what a crew
 * sends and a keyless flood on the WLAN has to reach thousands of packets a second before it
 * costs a real one anything. Under that, one bucket per sender ([perSecond], [burst]) so a
 * single noisy or broken peer cannot starve the others; a healthy sender needs 50 audio packets
 * a second plus one hello. The sender table is bounded by [maxSenders]: when it is full an
 * unknown sender is refused rather than allocated, and senders idle for [forgetMs] are swept.
 * The third bucket ([junkPerSecond], [junkBurst]) is charged only for packets that fail to
 * open; it drops nothing extra (those packets are gone already) but tells the caller when
 * unreadable traffic exceeds what a stray phone or two would produce, so the crew can be told
 * that someone on the network has a different key, or is flooding.
 * Pure Kotlin, unit-tested; the caller supplies the clock.
 */
class RateLimiter(
    private val perSecond: Double = 75.0,
    private val burst: Double = 150.0,
    private val globalPerSecond: Double = 5000.0,
    private val globalBurst: Double = 2000.0,
    private val junkPerSecond: Double = 200.0,
    private val junkBurst: Double = 400.0,
    private val maxSenders: Int = 128,
    private val forgetMs: Long = 10_000
) {
    private class Bucket(var tokens: Double, var lastMs: Long)

    private val global = Bucket(globalBurst, 0)
    private val junk = Bucket(junkBurst, 0)
    private val buckets = HashMap<Int, Bucket>()
    private var lastSweepMs = 0L

    /** True if the packet from [senderId] at [nowMs] is within both budgets: [allowGlobal] then [allowSender]. */
    fun allow(senderId: Int, nowMs: Long): Boolean = allowGlobal(nowMs) && allowSender(senderId, nowMs)

    /**
     * The global budget alone, for a packet that is not yet authenticated: the sender id it
     * claims is not to be trusted, so nothing is charged to any sender yet.
     */
    @Synchronized
    fun allowGlobal(nowMs: Long): Boolean = take(global, nowMs, globalPerSecond, globalBurst)

    /**
     * Charges the junk budget for a packet that did not open. False once unreadable packets
     * come faster than the budget: the caller reports, it does not drop anything more.
     */
    @Synchronized
    fun allowJunk(nowMs: Long): Boolean = take(junk, nowMs, junkPerSecond, junkBurst)

    /** The per-sender budget, for a packet whose sender id has been authenticated. */
    @Synchronized
    fun allowSender(senderId: Int, nowMs: Long): Boolean {
        if (nowMs - lastSweepMs > forgetMs) {
            buckets.values.removeIf { nowMs - it.lastMs > forgetMs }
            lastSweepMs = nowMs
        }
        val b = buckets[senderId] ?: run {
            if (buckets.size >= maxSenders) return false      // table full: no state for a newcomer
            Bucket(burst, nowMs).also { buckets[senderId] = it }
        }
        return take(b, nowMs, perSecond, burst)
    }

    private fun take(b: Bucket, nowMs: Long, rate: Double, cap: Double): Boolean {
        val elapsed = (nowMs - b.lastMs).coerceAtLeast(0)
        b.tokens = (b.tokens + elapsed * rate / 1000.0).coerceAtMost(cap)
        b.lastMs = nowMs
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }
}
