package fi.crewradio.rns

/**
 * rnsd's announce rate rule for one destination (`Transport.inbound`, rnsd 1.5.4), kept here so a
 * node knows which of its announces a transport node will pass on. With transport on, every
 * interface gets it by default and it cannot be switched off, only moved (`announce_rate_target`
 * per interface, `default_ar_target`): the first announce opens the record; each later one sooner
 * than [targetMs] after the last one passed is a violation, one later than that forgives one, and
 * past [grace] violations an announce updates the node's own path table but is not rebroadcast,
 * until [targetMs] after the last one passed. So six at once, then one an hour. The plugin's
 * lib/rns/transport.js has the same class.
 */
internal class AnnounceBudget(val targetMs: Long = TARGET_MS, val grace: Int = GRACE) {
    private var last: Long? = null
    var violations = 0
        private set
    private var blockedUntil = Long.MIN_VALUE

    /** What an announce at [now] would meet, nothing recorded: whether it passes, and the violations after it. */
    fun peek(now: Long): Pair<Boolean, Int> {
        val l = last ?: return true to 0
        if (now <= blockedUntil) return false to violations
        val v = if (now - l < targetMs) violations + 1 else maxOf(0, violations - 1)
        return (v <= grace) to v
    }

    /** Records an announce sent at [now]; true when the transport node passes it on. */
    fun record(now: Long): Boolean {
        val l = last
        if (l == null) {
            last = now
            return true
        }
        if (now <= blockedUntil) return false
        val (passes, v) = peek(now)
        violations = v
        if (passes) last = now else blockedUntil = l + targetMs
        return passes
    }

    companion object {
        /** rnsd 1.5.4 with transport on: `Interface.DEFAULT_AR_TARGET` (3600 s); its penalty is 0. */
        const val TARGET_MS = 3_600_000L
        /** ... and `Interface.DEFAULT_AR_GRACE`. */
        const val GRACE = 5
    }
}
