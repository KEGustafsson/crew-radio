package fi.crewradio

/**
 * The receive-side decisions for one packet, in the order the engine must make them, with no
 * engine, codec or transport around them: pure Kotlin, unit-tested against the claims the
 * documentation makes about them.
 *
 *  1. The global rate budget ([RateLimiter.allowGlobal]), before the packet is opened: the
 *     sender id it claims is unauthenticated, so nothing is charged to any sender yet.
 *  2. [open]: the AEAD check. A packet without the crew's key stops here, so a forgery cannot
 *     occupy a (sender, seq) slot in the seen-cache, cost the real sender its budget, or reach
 *     the relay, the roster or a decoder. A failure charges the junk budget instead
 *     ([RateLimiter.allowJunk]); exhausting it is reported as [Why.JUNK_FLOOD].
 *  3. The timestamp ([Packet.isFresh]): a packet more than [Packet.REPLAY_WINDOW_S] off this
 *     clock is [Result.Stale] and touches no cache, so a recording cannot be played back later
 *     than that, and an unauthenticated packet cannot inflate the counter or probe the clock.
 *  4. A look at the seen-cache: every frame arrives twice on WLAN (multicast and broadcast) and
 *     again over every other link, so a copy is [Result.Duplicate] before it costs the sender
 *     anything; charging each copy would spend a 75/s budget in seconds.
 *  5. The sender's budget ([RateLimiter.allowSender]): a sender over it writes nothing into the
 *     shared cache, so it cannot churn it either.
 *  6. The seen-cache write; a copy that slipped in between on another transport's thread is
 *     caught here and counted as the duplicate it is.
 *
 * The caches are sized for the replay window (a talker sends 50 frames a second, a node one
 * hello) and, like the sequence high-water marks ([SeqTracker]), live for the process, not the
 * session: leaving and rejoining the channel must not reopen the window.
 *
 * An accepted packet also carries the ttl to relay it with ([relayTtl]); a duplicate that would
 * travel further than the copy already forwarded is [Result.RelayOnly], because the ttl is the one
 * header byte a relay rewrites and therefore the one an attacker can lower.
 */
class Ingress(
    private val limiter: RateLimiter = RateLimiter(),
    audioCache: Int = AUDIO_CACHE,
    helloCache: Int = HELLO_CACHE,
    private val seq: SeqTracker = SeqTracker(),
    private val helloSeq: SeqTracker = SeqTracker()
) {
    sealed class Result {
        /** New and within budget: [plain] is the payload, [relayTtl] the ttl to forward with, 0 when it is not to be forwarded. */
        class Accept(val plain: ByteArray, val relayTtl: Int) : Result()
        /** Authentic, but already heard on another path. */
        object Duplicate : Result()
        /**
         * Authentic and already heard, but this copy carries a ttl that reaches further than the
         * best one forwarded so far, so it is relayed again — and only relayed: the payload was
         * played the first time. See [admit] for why a duplicate may still be worth forwarding.
         */
        class RelayOnly(val relayTtl: Int) : Result()
        /** Authentic, but its timestamp is outside the replay window. */
        object Stale : Result()
        /** Dropped for the given reason; counts as rejected. */
        class Rejected(val why: Why) : Result()
    }

    enum class Why {
        /** More packets than this phone will look at, whoever they claim to be from. */
        GLOBAL_BUDGET,
        /** Not sealed with our key: another crew's phone, or garbage. */
        UNREADABLE,
        /** Unreadable, and more of it than a stray phone would send; the engine reports. */
        JUNK_FLOOD,
        /** An authentic sender over its own budget. */
        SENDER_BUDGET
    }

    /** Key -> the highest ttl this packet has been forwarded with so far; see [admit]. */
    private class SeenCache(private val capacity: Int) : LinkedHashMap<Long, Int>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?) = size > capacity
        override fun clone(): Any = SeenCache(capacity).also { it.putAll(this) }   // HashMap is Cloneable; keep the bound
    }
    private val seen = SeenCache(audioCache)          // audio frames; their own sequence space
    private val seenHellos = SeenCache(helloCache)    // hellos: 1 Hz per node, numbered independently

    /**
     * Runs the pipeline for a parsed header. [nowMs] is the monotonic clock for the budgets,
     * [nowS] the wall clock in seconds for the timestamp, [maxHops] this phone's own hop limit;
     * [open] is called at most once, after the global budget and before anything else.
     */
    fun admit(
        h: Packet.Header,
        nowMs: Long,
        nowS: Long,
        maxHops: Int,
        open: () -> ByteArray?,
        selfId: Int? = null
    ): Result {
        if (!limiter.allowGlobal(nowMs)) return Result.Rejected(Why.GLOBAL_BUDGET)
        // Our own frame, relayed back by a peer. Dropped here rather than before the pipeline so
        // that a flood claiming our id is still charged the global budget: the id is in the clear
        // in every packet we send, so anyone can copy it, and a check that returns before the
        // budget would be a way in that costs the attacker nothing.
        if (selfId != null && h.senderId == selfId) return Result.Duplicate
        val plain = open() ?: return Result.Rejected(if (limiter.allowJunk(nowMs)) Why.UNREADABLE else Why.JUNK_FLOOD)
        if (!Packet.isFresh(h.time, nowS)) return Result.Stale
        // Look, charge and mark under one lock. Apart they are three steps, and the same frame
        // arriving on two transports at once passes the look on both threads and costs its sender
        // two tokens before either marks it — the very charging-for-copies that emptied a talker's
        // budget in the field. A sender over its budget still writes nothing into the cache.
        val cache = cacheFor(h)
        val k = key(h)
        val ttl = relayTtl(h, maxHops)
        synchronized(cache) {
            val forwarded = cache[k]
            if (forwarded != null) {
                // Heard already, so the payload is not played twice. But the ttl is the one header
                // byte outside the AAD (relays rewrite it), so anyone within radio range can replay
                // a captured frame with it lowered and, arriving first, take the packet's place in
                // this cache with a ttl that relays nothing — the genuine copy behind it is then
                // only a duplicate and the far side of the mesh goes silent. So a copy that would
                // reach further than the best one forwarded is forwarded too. The ttl is capped at
                // the sender's own signed budget ([relayTtl]) and each copy must beat the last, so
                // this costs at most that budget in extra forwards per packet, and a lowered ttl
                // buys nothing.
                if (ttl <= forwarded) return Result.Duplicate
                cache[k] = ttl
                return Result.RelayOnly(ttl)
            }
            if (!limiter.allowSender(h.senderId, nowMs)) return Result.Rejected(Why.SENDER_BUDGET)
            cache.put(k, ttl)
        }
        return Result.Accept(plain, ttl)
    }

    /**
     * Audio frames number themselves consecutively, so a gap is lost audio: admits the frame
     * and calls [onGap] with the count of frames missing before it, inside the same lock, so the
     * caller can reserve their slots atomically with the admission (the same sender's frames
     * arrive on several transport threads at once). False for a late frame: its slot has been
     * concealed already, and a replay of it is refused the same way.
     */
    fun admitAudio(senderId: Int, seq: Int, onGap: (Int) -> Unit): Boolean = synchronized(this.seq) {
        val gap = this.seq.admit(senderId, seq)
        if (gap < 0) return false
        if (gap > 0) onGap(gap)
        true
    }

    /**
     * Hellos number themselves too, one a second per node, so a gap in that sequence is a hello
     * that never arrived: the roster's link meter ([LinkQuality]). Nothing is gated here — the
     * seen-cache in [admit] has already passed the packet — it only says how many are missing
     * before this one, or -1 for one that arrives after a later one.
     */
    fun helloGap(senderId: Int, seq: Int): Int = helloSeq.admit(senderId, seq)

    private fun key(h: Packet.Header) = (h.senderId.toLong() shl 32) or (h.seq.toLong() and 0xFFFF_FFFFL)
    private fun cacheFor(h: Packet.Header) = if (h.codec == Packet.Codec.HELLO) seenHellos else seen

    companion object {
        /** Audio seen-cache entries: over five minutes of one talker, or the replay window for several. */
        const val AUDIO_CACHE = 16_384
        /** Hello seen-cache entries: the replay window for a crew of thirty, or half an hour of one node. */
        const val HELLO_CACHE = 2_048

        /**
         * The ttl a relay forwards a packet with, or 0 to keep it. The ttl is first capped at the
         * budget the sender signed into the packet, so nobody can bump a captured packet's ttl and
         * ride further, then decremented from what came in, never from a smaller number, so the
         * relays a packet passed (`hops - ttl`) stay exact on every roster. A phone's own hop
         * limit means the same for what it forwards as for what it originates: a packet reaches
         * at most [maxHops] hops from its origin, so a relay that would carry it further keeps
         * it (a limit of 1 relays nothing).
         */
        fun relayTtl(h: Packet.Header, maxHops: Int): Int {
            val ttl = minOf(h.ttl, h.hops)
            val relayed = h.hops - ttl                          // relays passed so far; this one would be relayed + 1
            return if (ttl > 1 && relayed + 1 < maxHops) ttl - 1 else 0
        }
    }
}
