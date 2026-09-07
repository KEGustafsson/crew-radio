package fi.crewradio.transport

/**
 * Who was heard from lately: a bounded map of peer → value with a last-seen time and an expiry.
 *
 * [LanTransport] keeps the source addresses it received from in the last few seconds and sends
 * each frame to them unicast as well (access points drop a few percent of multicast even in the
 * same cabin; the phones drop the copies they get twice). [WifiAwareTransport] keeps the peers
 * discovery has seen, so the ones no longer around are forgotten even on Android 10, which has
 * no `onServiceLost`. Bounded: when full, the peer unseen the longest makes room, so a flood of
 * addresses costs a bounded number of unicast copies and never grows memory.
 *
 * Pure Kotlin, callers pass the clock, so the expiry is unit-tested.
 */
internal class PeerTable<K : Any, V : Any>(private val ttlMs: Long, private val max: Int = 64) {
    private class Entry<V>(var value: V, var lastSeen: Long)

    private val entries = HashMap<K, Entry<V>>()

    /** Records a sighting of [key] at [now], replacing its value; evicts the stalest entry when full. */
    @Synchronized fun put(key: K, value: V, now: Long) {
        val e = entries[key]
        if (e != null) {
            e.value = value
            e.lastSeen = now
            return
        }
        if (entries.size >= max) entries.remove(entries.minByOrNull { it.value.lastSeen }!!.key)
        entries[key] = Entry(value, now)
    }

    /** Refreshes the sighting of [key] without changing its value; false if it is not in the table. */
    @Synchronized fun touch(key: K, now: Long): Boolean {
        val e = entries[key] ?: return false
        e.lastSeen = now
        return true
    }

    @Synchronized fun get(key: K): V? = entries[key]?.value
    @Synchronized fun contains(key: K): Boolean = entries.containsKey(key)
    @Synchronized fun remove(key: K): V? = entries.remove(key)?.value
    @Synchronized fun clear() = entries.clear()

    /** The key whose value satisfies [predicate], if any. */
    @Synchronized fun keyWhere(predicate: (V) -> Boolean): K? = entries.entries.firstOrNull { predicate(it.value.value) }?.key

    val size: Int @Synchronized get() = entries.size
    val keys: List<K> @Synchronized get() = entries.keys.toList()

    /** Forgets and returns the keys unseen for longer than the ttl. */
    @Synchronized fun expire(now: Long): List<K> {
        val gone = entries.filter { now - it.value.lastSeen > ttlMs }.keys.toList()
        for (k in gone) entries.remove(k)
        return gone
    }

    /** The keys seen within the ttl, after forgetting the rest. */
    @Synchronized fun live(now: Long): List<K> {
        expire(now)
        return entries.keys.toList()
    }
}
