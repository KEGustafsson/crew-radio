package fi.crewradio.transport

/**
 * Which network the Reticulum connection goes over: "whatever the phone has", made precise.
 * Pure, so the rule is unit-tested; [ReticulumTransport] feeds it what ConnectivityManager says.
 *
 *  - A transport node on the internet goes over a network that has actually reached the
 *    internet (validated): the default one if it has, else Wi-Fi or Ethernet that has, else
 *    any that has (mobile data), and only when none has, the default or anything at all. So a
 *    boat Wi-Fi without internet does not swallow the connection while mobile data is up, and the
 *    phone can be on the WLAN aboard and on Reticulum over mobile data at once.
 *  - A transport node at a private address (the boat's own rnsd at 192.168.1.9) is on a local
 *    network, so it goes over Wi-Fi or Ethernet, whatever their internet status.
 */
internal object NetworkChoice {
    class Candidate<T>(val id: T, val local: Boolean, val validated: Boolean, val isDefault: Boolean)

    enum class Target { PRIVATE, PUBLIC, NAME }

    fun <T> pick(candidates: List<Candidate<T>>, target: Target): T? {
        if (target == Target.PRIVATE) {
            val local = candidates.filter { it.local }
            return (local.firstOrNull { it.isDefault } ?: local.firstOrNull())?.id
        }
        val validated = candidates.filter { it.validated }
        return (validated.firstOrNull { it.isDefault } ?: validated.firstOrNull { it.local } ?: validated.firstOrNull()
            ?: candidates.firstOrNull { it.isDefault } ?: candidates.firstOrNull())?.id
    }

    /**
     * What a host string is, without a DNS lookup: an address in a private, link-local, loopback
     * or unique-local range, a public address, or a name (resolved later, on the chosen network).
     */
    fun target(host: String): Target {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        val v4 = h.split('.')
        if (v4.size == 4 && v4.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }) {
            val (a, b) = v4[0].toInt() to v4[1].toInt()
            val private = a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
            return if (private) Target.PRIVATE else Target.PUBLIC
        }
        if (':' in h && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            val lower = h.lowercase()
            val private = lower == "::1" || lower.startsWith("fe8") || lower.startsWith("fe9") || lower.startsWith("fea") ||
                lower.startsWith("feb") || lower.startsWith("fc") || lower.startsWith("fd")
            return if (private) Target.PRIVATE else Target.PUBLIC
        }
        return Target.NAME
    }
}
