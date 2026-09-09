package fi.crewradio.transport

/**
 * Per-source ingress budget for a datagram transport, charged on the receive thread before a
 * packet is handed to the engine at all.
 *
 * The engine's own [fi.crewradio.RateLimiter] bounds what this phone will look at in total, but it
 * is charged before the AEAD and is therefore blind to who sent what: it is first come, first
 * served, so one host on the WLAN that simply asks faster takes the whole budget and the crew's
 * own frames are refused behind it. Nothing about the channel key is needed for that — a laptop
 * spraying the multicast group will do. This bucket is what makes the flood cost the flooder:
 * one budget per source address, so a single host can spend only its own share and the global
 * budget is left for everyone else.
 *
 * Sized well above anything real. A talker sends 50 audio frames a second plus a hello, and a peer
 * that relays for the crew carries several talkers at once, so [perSecond] is set to leave even a
 * busy relay untouched while still cutting a flood by an order of magnitude before the engine sees
 * it. The table is bounded by [maxSources] and evicts the least recently used, never refusing a
 * newcomer: a flood that rotates its source address must not be able to lock the crew out by
 * filling the table.
 *
 * Pure Kotlin, unit-tested; the caller supplies the clock and the key.
 */
class SourceLimiter(
    private val perSecond: Double = 500.0,
    private val burst: Double = 1000.0,
    private val maxSources: Int = 64
) {
    private class Bucket(var tokens: Double, var lastMs: Long)

    private val buckets = object : LinkedHashMap<Int, Bucket>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bucket>?) = size > maxSources
    }

    /** True if a packet from [source] at [nowMs] is within that source's own budget. */
    @Synchronized
    fun allow(source: Int, nowMs: Long): Boolean {
        val b = buckets[source] ?: Bucket(burst, nowMs).also { buckets[source] = it }
        val elapsed = (nowMs - b.lastMs).coerceAtLeast(0)
        b.tokens = (b.tokens + elapsed * perSecond / 1000.0).coerceAtMost(burst)
        b.lastMs = nowMs
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    /** Forgets every source; the transport calls this when it re-opens on a new network. */
    @Synchronized
    fun clear() = buckets.clear()
}
