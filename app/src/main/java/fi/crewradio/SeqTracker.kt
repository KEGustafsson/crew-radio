package fi.crewradio

/**
 * Per-sender audio sequence admission, the pure part of loss detection: given the sequence
 * number of a packet that just arrived, how many frames went missing before it.
 *
 * Wrap-aware: distances are the signed 32-bit difference, so a sender's counter rolling
 * over from Int.MAX_VALUE is a distance of one, not a jump backwards. One lock for the
 * read-compare-write, because the same sender's packets arrive on several transport
 * threads; the caller keeps its follow-up (reserving the slots) inside [synchronized] on
 * this object if that has to be atomic with the admission.
 *
 * The high-water marks are kept for the life of the process, not the session or the roster
 * entry: a sender that went quiet, or a receiver that left and rejoined the channel, would
 * otherwise admit a recording of the sender's last packets as new. Only the least recently
 * heard sender is forgotten, when more than [capacity] have been heard.
 */
class SeqTracker(private val capacity: Int = DEFAULT_CAPACITY) {
    private val last = object : LinkedHashMap<Int, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Int>?) = size > capacity
    }

    /**
     * Admits [seq] from [senderId]: the count of missing sequence numbers before it (0 when
     * it is the next expected one, or the first we hear), or -1 when it is late - a number
     * we have already moved past - and should be dropped.
     */
    @Synchronized
    fun admit(senderId: Int, seq: Int): Int {
        val prev = last[senderId]
        if (prev != null) {
            val distance = seq - prev          // wraps with the counter
            if (distance <= 0) return -1
            last[senderId] = seq
            return distance - 1
        }
        last[senderId] = seq
        return 0
    }

    companion object {
        /** Senders remembered at once; a crew is a handful, the rest is room for ids that come and go. */
        const val DEFAULT_CAPACITY = 256
    }
}
