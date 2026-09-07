package fi.crewradio.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress

/**
 * UDP on the local network. Every phone joins the same multicast group; whoever is
 * talking sends, everyone else plays. No server, no discovery needed.
 *
 * Multicast alone is unreliable on consumer gear: access points rate-limit or drop
 * traffic to groups nobody has an IGMP querier for, and some routers filter it outright.
 * So every frame also goes to the interface's IPv4 broadcast address, which survives far
 * more networks, and unicast to every address heard from in the last [PEER_TTL_MS] — an
 * access point delivers multicast and broadcast at its lowest rate, unacknowledged, and a
 * phone in the same cabin still loses a few percent, audible as voids; unicast is
 * acknowledged. The socket is bound to the wildcard address, so it picks up every copy;
 * the engine's seen-cache drops whichever arrives second. Client isolation ("AP isolation",
 * most guest Wi-Fi) blocks all of it, and then nothing but Bluetooth or Wi-Fi Aware will do.
 *
 * The socket follows the Wi-Fi network the connectivity callback describes: its interface
 * and address come from the callback's `LinkProperties`, events about any other Wi-Fi network
 * (a hotspot interface next to the station interface) are ignored, and enumerating the
 * interfaces is only the fallback while the callback has not spoken yet.
 *
 * Reconnect: the receive thread owns the socket and re-opens it with [Backoff] whenever
 * it breaks or there is no Wi-Fi yet. A Wi-Fi change (dropped and came back, new address,
 * hotspot came up) closes the socket on purpose so it is re-opened and the group re-joined
 * on the new interface — the kernel forgets memberships when a link goes down — and skips
 * the wait ([Waiter]). [stop] sets the flag and leaves the close to `ptt-lan-stop`.
 */
class LanTransport(
    context: Context,
    private val group: String = "239.255.42.1",
    private val port: Int = 47474
) : Transport {

    override val name = "LAN"
    override val relayWithin = false
    override val ready: Boolean get() = socket != null

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val groupAddr: InetAddress by lazy { InetAddress.getByName(group) }
    private val backoff = Backoff()
    private val waiter = Waiter()                        // cuts a backoff wait short, or skips the next one
    private val lifecycle = Any()                        // orders "publish a socket" against "stop and close it"
    private val peers = PeerTable<InetAddress, Unit>(PEER_TTL_MS, MAX_PEERS)

    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var openedOn: String? = null       // "wlan0/192.168.0.35" while a socket is up
    @Volatile private var ownAddr: InetAddress? = null
    @Volatile private var broadcastAddr: InetAddress? = null
    @Volatile private var wifi: WifiLink? = null         // the Wi-Fi network the callback last described
    @Volatile private var heard = false
    @Volatile private var running = false
    private var lock: WifiManager.MulticastLock? = null
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    private class WifiLink(val network: Network, val lp: LinkProperties)

    /** Where to open the socket: an interface with an IPv4 address, and its broadcast address if it has one. */
    private class Target(val nic: NetworkInterface, val address: InetAddress, val broadcast: InetAddress?)

    /**
     * Tracks one Wi-Fi network and re-opens the socket around its changes. Losing it closes
     * the socket outright, even though a wildcard-bound UDP socket would happily stay open:
     * the kernel drops the multicast membership with the link, and Wi-Fi usually comes back
     * on the same interface with the same DHCP address, so a "did it change?" check alone
     * would never re-join.
     */
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val cur = wifi
            if (cur != null && cur.network != network) return       // a second Wi-Fi network: not the one in use
            wifi = WifiLink(network, lp)
            if (running && describe(lp) != openedOn) rejoin()
        }
        override fun onLost(network: Network) {
            if (wifi?.network != network) return
            wifi = null
            if (!running) return
            onStatus("LAN: Wi-Fi lost, waiting for it")
            rejoin()                                       // rx loop then waits in "no Wi-Fi" until it is back
        }
    }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("ptt-multicast").apply {
            setReferenceCounted(false)
            acquire()
        }
        running = true
        transportThread("ptt-lan-rx", { onStatus("LAN rx stopped: ${it.message}") }) { rxLoop() }
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback
        )
    }

    /** Opens the socket, receives until it breaks, waits, repeats — for as long as the session runs. */
    private fun rxLoop() {
        while (running) {
            val target = try {
                pickTarget()
            } catch (e: Exception) {                   // enumerating interfaces can itself fail mid-change
                val wait = backoff.next()
                onStatus("LAN: can't list interfaces (${e.message}), retry in ${wait / 1000}s")
                waiter.await(wait)
                continue
            }
            if (target == null) {
                onStatus("LAN: no Wi-Fi, waiting")
                waiter.await(backoff.next())
                continue
            }
            val s = try {
                openSocket(target.nic)
            } catch (e: Exception) {
                val wait = backoff.next()
                onStatus("LAN: can't open (${e.message}), retry in ${wait / 1000}s")
                waiter.await(wait)
                continue
            }
            synchronized(lifecycle) {
                if (!running) { s.close(); return }   // stop() ran while we were opening
                socket = s
            }
            ownAddr = target.address
            openedOn = "${target.nic.name}/${target.address.hostAddress}"
            broadcastAddr = target.broadcast
            heard = false
            peers.clear()
            val bc = target.broadcast?.hostAddress?.let { " + $it" } ?: " (multicast only)"
            onStatus("LAN: $group:$port$bc via ${target.nic.name}")
            receiveUntilClosed(s)
            socket = null
            openedOn = null
            if (running) waiter.await(backoff.next())
        }
    }

    /**
     * Bound with SO_REUSEADDR set *before* the bind, so a second listener on the port is possible.
     * Loopback is off: our own multicast frames are not wanted back (the broadcast copy still
     * comes back, and is dropped by address).
     *
     * Deliberately not an `apply` block: inside one, `port` resolves to
     * [java.net.DatagramSocket.getPort] — the *remote* port, -1 on an unconnected socket — instead
     * of this transport's port, and joinGroup then dies with "port out of range:-1".
     */
    private fun openSocket(nic: NetworkInterface): MulticastSocket {
        val s = MulticastSocket(null as SocketAddress?)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            s.timeToLive = 1
            s.broadcast = true
            s.loopbackMode = true                      // true = loopback *disabled*, the API is inverted
            s.networkInterface = nic
            s.joinGroup(InetSocketAddress(groupAddr, port), nic)
        } catch (e: Exception) {
            s.close()                                  // a bound-but-unjoined socket would hold the port
            throw e
        }
        return s
    }

    private fun receiveUntilClosed(s: MulticastSocket) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val from = p.address
                if (from == ownAddr) continue                  // the broadcast copy of our own frame
                peers.put(from, Unit, System.currentTimeMillis())
                if (!heard) {
                    heard = true
                    backoff.reset()                            // a working network: the next reopen starts fast again
                    onStatus("LAN: hearing ${from.hostAddress}")
                }
                onPacket(buf.copyOf(p.length), this, from)
            } catch (e: IOException) {
                if (running && !s.isClosed) onStatus("LAN: socket error (${e.message}), reopening")
                return
            }
        }
    }

    /** Closes the current socket so [rxLoop] re-opens on whatever Wi-Fi now offers, without the usual wait. */
    private fun rejoin() {
        backoff.reset()
        waiter.wake()
        socket?.close()
    }

    /** Sends to the group, to the subnet broadcast address when we know one, and unicast to everyone heard lately. */
    override fun send(packet: ByteArray, except: Any?): Boolean {
        val s = socket ?: return false
        sendTo(s, packet, groupAddr)
        broadcastAddr?.let { sendTo(s, packet, it) }
        for (a in peers.live(System.currentTimeMillis())) if (a != except) sendTo(s, packet, a)
        return true
    }

    private fun sendTo(s: MulticastSocket, packet: ByteArray, to: InetAddress) {
        try {
            s.send(DatagramPacket(packet, packet.size, to, port))
        } catch (_: IOException) { /* transient, drop the frame */ }
    }

    /** Sets the flag and unregisters; the socket close and the lock release run on `ptt-lan-stop`. */
    override fun stop() {
        val s: MulticastSocket?
        synchronized(lifecycle) {
            running = false
            s = socket
            socket = null
        }
        waiter.wake()
        try { connectivity.unregisterNetworkCallback(wifiCallback) } catch (_: Exception) {}
        val l = lock
        lock = null
        heard = false
        transportThread("ptt-lan-stop", { /* a close that failed has nothing left to report */ }) {
            s?.let {
                try { it.leaveGroup(groupAddr) } catch (_: Exception) {}
                it.close()
            }
            l?.let { if (it.isHeld) it.release() }
        }
    }

    /** What the callback's Wi-Fi network offers, else whatever interface enumeration finds. */
    private fun pickTarget(): Target? {
        wifi?.lp?.let { lp ->
            val name = lp.interfaceName
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            if (name != null && la != null) {
                val nic = NetworkInterface.getByName(name)
                if (nic != null) return Target(nic, la.address, broadcastAddressOf(nic) ?: LanAddressing.broadcastOf(la.address, la.prefixLength))
            }
        }
        val nic = pickInterface() ?: return null
        val ia = nic.interfaceAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return Target(nic, ia.address, ia.broadcast ?: LanAddressing.broadcastOf(ia.address, ia.networkPrefixLength.toInt()))
    }

    /** "wlan0/192.168.0.35" for a network's link properties, or null when it has no IPv4 address yet. */
    private fun describe(lp: LinkProperties): String? {
        val nic = lp.interfaceName ?: return null
        val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: return null
        return "$nic/$addr"
    }

    /** Fallback: prefer wlan0; otherwise first up, non-loopback, multicast-capable interface with an IPv4 address. */
    private fun pickInterface(): NetworkInterface? {
        val all = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        val usable = all.filter { nic ->
            nic.isUp && !nic.isLoopback && nic.supportsMulticast() && nic.inetAddresses.toList().any { it is Inet4Address }
        }
        return usable.firstOrNull { it.name.startsWith("wlan") } ?: usable.firstOrNull()
    }

    /** The interface's own idea of its IPv4 broadcast address; null on IPv6-only interfaces and on OEM builds that omit it. */
    private fun broadcastAddressOf(nic: NetworkInterface): InetAddress? =
        nic.interfaceAddresses.firstNotNullOfOrNull { it.broadcast }

    companion object {
        /** A peer heard from this recently gets a unicast copy of every frame. Hellos come every second. */
        const val PEER_TTL_MS = 5_000L
        /** Unicast fan-out is bounded: a flood of source addresses evicts, it does not grow. */
        const val MAX_PEERS = 16
    }
}
