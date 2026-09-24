// The one place in the app where a deprecated platform call is allowed, and why: each one below is
// the only form the platform offers on the API levels it is called on, and every caller reaches it
// behind that API check. A deprecated constant with a stable value is not here: it is written out
// by hand beside its caller and pinned by a unit test (ScoBroadcast, WifiLockModes, LinkQuality.wifiBars).
@file:Suppress("DEPRECATION")

package fi.crewradio

import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.telecom.Connection
import android.telecom.PhoneAccount
import java.net.InetAddress

/**
 * Deprecated platform calls with no replacement on the levels that use them. Nothing here decides
 * anything: the callers keep their API checks and their policy, this only makes the call.
 */
object LegacyPlatform {

    // ---- Bluetooth SCO and speakerphone, API 29 and 30 ------------------------------------------
    // setCommunicationDevice replaces all four from API 31, which AudioRoute uses there.

    /** Opens the SCO link to the Bluetooth headset and routes the call audio over it. */
    fun startBluetoothSco(am: AudioManager) {
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
    }

    /** Closes the SCO link opened by [startBluetoothSco]. */
    fun stopBluetoothSco(am: AudioManager) {
        am.stopBluetoothSco()
        am.isBluetoothScoOn = false
    }

    /** Loudspeaker on, or off for the earpiece or a wired headset. */
    fun setSpeakerphone(am: AudioManager, on: Boolean) {
        am.isSpeakerphoneOn = on
    }

    // ---- mDNS, below API 34 ----------------------------------------------------------------------
    // registerServiceInfoCallback and NsdServiceInfo.hostAddresses replace both from API 34.

    /** One-shot resolve of a discovered service. */
    fun resolveService(manager: NsdManager, info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        manager.resolveService(info, listener)
    }

    /** The single address a pre-34 resolve reports. */
    fun hostOf(info: NsdServiceInfo): InetAddress? = info.host

    // ---- Telecom ---------------------------------------------------------------------------------

    /** Below API 34, where requestCallEndpointChange does not exist yet. */
    fun setAudioRoute(connection: Connection, route: Int) {
        connection.setAudioRoute(route)
    }

    /**
     * Deprecated in favour of androidx.core:core-telecom, which the app does not depend on: a whole
     * new dependency for the one self-managed call the opt-in headset_call setting places. The
     * platform still honours it, and moving to core-telecom is its own piece of work.
     */
    const val CAPABILITY_SELF_MANAGED: Int = PhoneAccount.CAPABILITY_SELF_MANAGED
}
