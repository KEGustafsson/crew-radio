package fi.crewradio

import android.os.Build
import fi.crewradio.transport.NetworkChoice
import java.net.URI

/**
 * Android 17's local network protection, decided in one pure place. An app that targets API 37
 * (this one does) needs the runtime permission ACCESS_LOCAL_NETWORK, part of the Nearby devices
 * group, for any traffic to or from a local address: UDP unicast, multicast and broadcast (the
 * whole WLAN transport), a TCP connection to the boat's own rnsd, HTTP to the boat's Signal K
 * server, and mDNS. Without it TCP times out and UDP fails with EPERM, deep in the stack, so it
 * has to be asked for before those are used, not diagnosed after. Earlier releases grant it with
 * INTERNET, so below [Build.VERSION_CODES.CINNAMON_BUN] nothing is ever needed.
 *
 * Bluetooth, Wi-Fi Aware (its own peer-to-peer network) and a Reticulum hub on the internet do
 * not touch the local network and never need it.
 */
internal object LocalNetwork {
    /** Manifest.permission.ACCESS_LOCAL_NETWORK (API 37), written out so no older-API reference inlines it; pinned by the test. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    enum class Need {
        /** Not asked for. */
        NONE,
        /** Asked for with the others, but the session starts without it (a name that may be local or not). */
        OPTIONAL,
        /** The session cannot work without it. */
        REQUIRED,
    }

    /** What joining the channel needs, for the transports switched on: WLAN, and Reticulum's node. */
    fun forChannel(sdk: Int, wlan: Boolean, reticulumHost: String?): Need = when {
        sdk < Build.VERSION_CODES.CINNAMON_BUN -> Need.NONE
        wlan -> Need.REQUIRED
        reticulumHost == null -> Need.NONE
        else -> when (NetworkChoice.target(reticulumHost)) {
            NetworkChoice.Target.PRIVATE -> Need.REQUIRED
            // boat.local or a name the boat's router hands out is local; hub.example.org is not.
            // Only the lookup can tell, so it is asked for without holding the join up.
            NetworkChoice.Target.NAME -> Need.OPTIONAL
            NetworkChoice.Target.PUBLIC -> Need.NONE
        }
    }

    /**
     * Whether asking the boat (the Signal K server at [serverUrl]) needs it: a server at a private
     * address or by name (almost always on the boat's LAN) does, a public address does not.
     * With no server yet, the search for one is mDNS on the local network, so it does.
     */
    fun forServer(sdk: Int, serverUrl: String?): Boolean {
        if (sdk < Build.VERSION_CODES.CINNAMON_BUN) return false
        val host = serverUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: return true
        return NetworkChoice.target(host) != NetworkChoice.Target.PUBLIC
    }
}
