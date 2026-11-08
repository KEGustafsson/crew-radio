package fi.crewradio

/**
 * Who is on this boat with us, and which node is the boat's forwarder to Reticulum: the shared
 * rules every client (this app, the Signal K plugin, the ESP32 gauges) follows, so a boat puts
 * each frame through the hub once instead of once per bridge.
 *
 * A sender is *local* while, within the last [LOCAL_MS], a hello of its own reached us over a
 * local transport (WLAN, Bluetooth, Wi-Fi Aware) unrelayed: `hops == ttl` in its header, so
 * nobody forwarded it. For each local sender the transports byte of that hello is kept.
 *
 * The forwarder is elected without a message: the candidates are this node, while its own
 * [Hello.RNS_UP] is set, and every local sender whose last local hello had [Hello.RNS_UP]. The
 * highest rank wins ([Hello.FWD_PREFER] 2, neither 1, [Hello.FWD_AVOID] 0); a tie goes to the
 * lowest sender id compared as an *unsigned* 32-bit number, as every client compares it. Each
 * node works this out from what it hears, so views can disagree for a moment; the forwarding
 * rules ([Forwarding]) are written so that costs a duplicate, never a gap.
 *
 * Pure Kotlin and thread-safe (hellos arrive on every transport's thread); the clock is the
 * caller's monotonic milliseconds. At most [capacity] senders are tracked: their hellos are
 * authenticated, but a crew is small and a ceiling costs nothing; when full, the one heard
 * longest ago makes room for a newcomer.
 */
class LocalPeers(private val localMs: Long = LOCAL_MS, private val capacity: Int = MAX_SENDERS) {
    private class Seen(var at: Long, var transports: Int)
    private val seen = HashMap<Int, Seen>()

    /**
     * A hello from [sender] carrying [transports], with its header's [hops] and [ttl], heard over a
     * local transport when [viaLocal]. Only a local, unrelayed one makes (or keeps) the sender local.
     */
    @Synchronized
    fun heardHello(sender: Int, transports: Int, hops: Int, ttl: Int, viaLocal: Boolean, now: Long) {
        if (!viaLocal || hops != ttl) return
        sweep(now)
        val s = seen[sender]
        if (s != null) {
            s.at = now
            s.transports = transports
            return
        }
        // Full: the entry heard longest ago makes room (the ESP32's rule). A newcomer is never
        // refused, so a sender that really is on the boat cannot be shut out by a full table.
        if (seen.size >= capacity) seen.entries.minByOrNull { it.value.at }?.let { seen.remove(it.key) }
        seen[sender] = Seen(now, transports)
    }

    /** True while [sender] is local: a local, unrelayed hello of its own within [localMs]. */
    @Synchronized
    fun isLocal(sender: Int, now: Long): Boolean = fresh(sender, now) != null

    /** True when [sender] is local and its last local hello said it is on Reticulum itself. */
    @Synchronized
    fun rnsUp(sender: Int, now: Long): Boolean = fresh(sender, now)?.let { (it.transports and Hello.RNS_UP) != 0 } == true

    /** The boat's forwarder as this node sees it: [me] with our own hello bits [myTransports], or a local sender; null when nobody is on Reticulum. */
    @Synchronized
    fun forwarder(me: Int, myTransports: Int, now: Long): Int? {
        var best: Int? = null
        var bestRank = -1
        fun consider(id: Int, transports: Int) {
            if ((transports and Hello.RNS_UP) == 0) return
            val r = rank(transports)
            if (r > bestRank || (r == bestRank && Integer.compareUnsigned(id, best!!) < 0)) {
                best = id
                bestRank = r
            }
        }
        consider(me, myTransports)
        for ((id, s) in seen) if (id != me && now - s.at < localMs) consider(id, s.transports)
        return best
    }

    /** True when this node is the boat's forwarder. */
    fun isForwarder(me: Int, myTransports: Int, now: Long): Boolean = forwarder(me, myTransports, now) == me

    /** How many senders are local now; for diagnostics. */
    @Synchronized
    fun count(now: Long): Int = seen.values.count { now - it.at < localMs }

    private fun fresh(sender: Int, now: Long): Seen? = seen[sender]?.takeIf { now - it.at < localMs }

    private fun sweep(now: Long) {
        seen.entries.removeAll { now - it.value.at >= localMs }
    }

    companion object {
        /** How long one local, unrelayed hello keeps its sender local. */
        const val LOCAL_MS = 15_000L
        const val MAX_SENDERS = 64

        /** [Hello.FWD_PREFER] 2, neither 1, [Hello.FWD_AVOID] 0. */
        fun rank(transports: Int): Int = when {
            (transports and Hello.FWD_PREFER) != 0 -> 2
            (transports and Hello.FWD_AVOID) != 0 -> 0
            else -> 1
        }
    }
}
