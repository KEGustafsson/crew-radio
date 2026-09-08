package fi.crewradio.ask

import java.net.URI
import java.net.URISyntaxException

/**
 * What the crew may type as the boat's Signal K server, and what it means.
 *
 * They will type `northstar.local`, or `192.168.1.9`, or paste
 * `http://192.168.1.9:3000/admin/#/dashboard` out of a browser. All three mean the same server,
 * and none of them is a base URL. Normalising is pure, so the rules are unit-tested and the
 * settings screen can refuse a bad one before it is ever dialled.
 *
 * The default port is Signal K's own 3000: a host with no port is far more likely to be a server
 * on its default port than a web server on 80.
 */
object SignalKUrl {

    const val DEFAULT_PORT = 3000

    /**
     * `http://host:port` with no path, or null when this cannot be a server. Anything after the
     * authority is dropped — a pasted admin-UI link is still a perfectly good way to name a server.
     */
    fun normalise(typed: String?): String? {
        // Only whitespace is trimmed here: taking trailing slashes off first turns "http://" into
        // "http:", which then reads as the host "http". The path is discarded below anyway.
        val text = typed?.trim() ?: return null
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "http://$text"
        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        if (host.any { it.isWhitespace() }) return null
        val port = when {
            uri.port > 0 -> uri.port
            scheme == "https" -> 443
            else -> DEFAULT_PORT
        }
        // URI.getHost keeps the brackets of an IPv6 literal, so they are taken off and put back
        // exactly once — an address bracketed twice is not a URL any more.
        val bare = host.removeSurrounding("[", "]")
        val authority = if (bare.contains(':')) "[$bare]" else bare
        return "$scheme://$authority:$port"
    }

    /** True when [typed] names something we could dial. */
    fun valid(typed: String?): Boolean = normalise(typed) != null

    /** How the settings row shows a server: the host and port, without the scheme. */
    fun describe(typed: String?): String =
        normalise(typed)?.substringAfter("://") ?: ""

    /** The vessel-rooted REST endpoint for one top-level branch, e.g. `navigation`. */
    fun selfBranch(base: String, branch: String): String = "$base/signalk/v1/api/vessels/self/$branch"

    /** Where an access request is made, and where its state is polled. */
    fun accessRequests(base: String): String = "$base/signalk/v1/access/requests"

    /** The Crew Radio plugin's announcement endpoint, for an answer the whole crew should hear. */
    fun pluginSay(base: String): String = "$base/plugins/signalk-crewradio/say"
}
