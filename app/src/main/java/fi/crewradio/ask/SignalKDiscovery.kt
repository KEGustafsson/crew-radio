package fi.crewradio.ask

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds the boat's Signal K server on the network, so nobody has to type an address.
 *
 * Signal K servers advertise `_signalk-http._tcp` over mDNS, which is exactly what the settings
 * row wants: open it, and a moment later the server is listed with its host and port. It is a
 * convenience and never a requirement — the crew can always type an address, and a boat network
 * that blocks multicast still works.
 *
 * Discovery is stopped as soon as the screen that started it goes away: mDNS keeps a multicast
 * socket open, and this app already asks a lot of the Wi-Fi radio.
 */
class SignalKDiscovery(context: Context) {

    /** A server as it advertises itself. [url] is ready to store. */
    data class Found(val name: String, val url: String)

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var listener: NsdManager.DiscoveryListener? = null

    /** True when this phone has no mDNS at all; the caller then shows the manual row only. */
    val available: Boolean get() = nsd != null

    /**
     * Starts looking. [onFound] is called on a binder thread, once per server, and may be called
     * again for the same one — the caller keeps a set. [onDone] reports that discovery could not
     * be started at all.
     */
    fun start(onFound: (Found) -> Unit, onDone: (String?) -> Unit) {
        val manager = nsd ?: return onDone("no mDNS on this phone")
        stop()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(manager, info, onFound)
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onDone("discovery failed ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discovery
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: IllegalArgumentException) {
            listener = null
            onDone(e.message)
        }
    }

    fun stop() {
        val manager = nsd ?: return
        listener?.let {
            listener = null
            try {
                manager.stopServiceDiscovery(it)
            } catch (_: IllegalArgumentException) {
                // Already stopped by the framework; nothing to undo.
            }
        }
    }

    /**
     * A found service carries only a name until it is resolved. `resolveService` was replaced on
     * API 34 by a callback that also follows a service changing address, so both paths exist: the
     * newer one where it is there, the older one below it. Each is its own method so the version
     * guard covers the whole body, callbacks included.
     */
    private fun resolve(manager: NsdManager, info: NsdServiceInfo, onFound: (Found) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveWithCallback(manager, info, onFound)
        else resolveLegacy(manager, info, onFound)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveWithCallback(manager: NsdManager, info: NsdServiceInfo, onFound: (Found) -> Unit) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
            override fun onServiceUpdated(updated: NsdServiceInfo) {
                urlOf(updated.hostAddresses, updated.port)?.let { onFound(Found(updated.serviceName, it)) }
                // One address is all the settings row needs; a callback left registered holds a
                // socket open for a server that has already been listed.
                try {
                    manager.unregisterServiceInfoCallback(this)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered by the framework.
                }
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        try {
            manager.registerServiceInfoCallback(info, { it.run() }, callback)
        } catch (_: IllegalArgumentException) {
            // The service went away between being found and being resolved.
        }
    }

    @Suppress("DEPRECATION")   // the callback above only exists from API 34; this is the path below it
    private fun resolveLegacy(manager: NsdManager, info: NsdServiceInfo, onFound: (Found) -> Unit) {
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                urlOf(listOfNotNull(resolved.host), resolved.port)?.let {
                    onFound(Found(resolved.serviceName, it))
                }
            }
        })
    }

    private companion object {
        const val SERVICE_TYPE = "_signalk-http._tcp."

        /**
         * An IPv4 address is what a boat network hands out and what the crew recognises in the
         * settings row, so a service advertising both is listed by its v4 address.
         */
        fun urlOf(addresses: List<InetAddress>, port: Int): String? {
            val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return null
            val host = address.hostAddress ?: return null
            return SignalKUrl.normalise("http://$host:${port.takeIf { it > 0 } ?: SignalKUrl.DEFAULT_PORT}")
        }
    }
}
