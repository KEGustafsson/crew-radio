package fi.crewradio

/**
 * Who has the channel, with no message and no wire change: listen before talk, and of two who key
 * at once the higher id yields. The same rules in the app, the Signal K plugin and the ESP32
 * gauges, so two talkers no longer double the load on every link.
 *
 * *Another talker is heard* while an authentic, first-copy audio frame from a sender other than
 * us arrived within the last [busyMs] (the roster's burst gap). Then:
 *
 *  1. A key pressed while another talker is heard does not key: [key] answers [Key.Refused] (the
 *     engine plays the busy cue and says who has the channel); the key stays held but silent. A
 *     key still held [overrideMs] after that keys anyway, as an override burst ([due]), which the
 *     collision rule leaves alone.
 *  2. While we talk (not an override), a frame from a talker whose id is lower than ours,
 *     compared as unsigned 32-bit numbers, makes us yield at once ([heard] returns true): the mic
 *     stays off until the key is released ([State.LOCKED]), like the talk time-out. Both ends
 *     compare the same two ids, so exactly one of two colliding talkers survives on every node,
 *     whatever the latency.
 *  3. After a release ([release]) the key may go down again, and rule 1 applies again.
 *
 * Pure: the caller serialises the calls (the engine's talk lock) and passes its monotonic clock.
 */
class Floor(private val me: Int, private val busyMs: Long = BUSY_MS, private val overrideMs: Long = OVERRIDE_MS) {

    enum class State {
        /** The key is up. */
        IDLE,
        /** Held but refused: the channel was busy; on air at [overrideMs] if still held. */
        WAITING,
        /** On air, and yields to a lower id. */
        TALKING,
        /** On air as an override burst (or with the rules off): yields to nobody. */
        OVERRIDE,
        /** Yielded: held, off air, until released. */
        LOCKED
    }

    sealed class Key {
        /** On air now. */
        object OnAir : Key()
        /** Refused: [by] has the channel. */
        class Refused(val by: Int) : Key()
        /** The key was down already; nothing changes. */
        object Held : Key()
    }

    var state = State.IDLE
        private set
    private var refusedAt = 0L
    private var lastOther = 0
    private var lastOtherAt = 0L
    private var heardAny = false

    /** True while the key is down, on air or not. */
    val keyed: Boolean get() = state != State.IDLE
    /** True while we are on air. */
    val onAir: Boolean get() = state == State.TALKING || state == State.OVERRIDE

    /** The other talker heard last, however long ago; null before anybody was. */
    val lastTalker: Int? get() = if (heardAny) lastOther else null

    /** The talker heard within [busyMs], or null when the channel is quiet. */
    fun busy(now: Long): Int? = if (heardAny && now - lastOtherAt <= busyMs) lastOther else null

    /**
     * The key goes down. With [listen] off (full duplex, where talkers are mixed on purpose) it is
     * on air at once, immune to the collision rule.
     */
    fun key(now: Long, listen: Boolean = true): Key {
        if (state != State.IDLE) return Key.Held
        if (!listen) {
            state = State.OVERRIDE
            return Key.OnAir
        }
        val by = busy(now)
        if (by == null) {
            state = State.TALKING
            return Key.OnAir
        }
        state = State.WAITING
        refusedAt = now
        return Key.Refused(by)
    }

    /** When a refused key that is still held goes on air: the moment of the override, or null when nothing waits. */
    fun overrideAt(): Long? = if (state == State.WAITING) refusedAt + overrideMs else null

    /** True when a refused key still held has waited [overrideMs]: it is on air now, as an override burst. */
    fun due(now: Long): Boolean {
        if (state != State.WAITING || now - refusedAt < overrideMs) return false
        state = State.OVERRIDE
        return true
    }

    /** An accepted audio frame from [sender]. True when it makes us yield, now: off air, locked until released. */
    fun heard(sender: Int, now: Long): Boolean {
        if (sender == me) return false
        lastOther = sender
        lastOtherAt = now
        heardAny = true
        if (state == State.TALKING && Integer.compareUnsigned(sender, me) < 0) {
            state = State.LOCKED
            return true
        }
        return false
    }

    /** The key is released (or dropped for any reason). */
    fun release() {
        state = State.IDLE
    }

    companion object {
        /** Another talker is heard for this long after its last frame: the burst gap. */
        const val BUSY_MS = 400L
        /** A refused key still held this long after the busy cue keys anyway. */
        const val OVERRIDE_MS = 1_000L
    }
}
