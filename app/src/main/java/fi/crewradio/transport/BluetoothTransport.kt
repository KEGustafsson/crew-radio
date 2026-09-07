package fi.crewradio.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bluetooth Classic RFCOMM. Every phone listens as a server; optionally also
 * connects to one chosen paired peer. With the engine's relay enabled, a phone
 * holding several links forwards between them, so a chain A-B-C works.
 *
 * One link per pair: when two phones name each other as peer, both dial and both accept,
 * and [BluetoothTieBreak] decides which link survives once the peer's node id is known
 * (from its first direct frame): the lower id keeps the link it dialled, the higher id keeps
 * the one it accepted and stops dialling while that link is alive.
 *
 * Reconnect: the dialled link is re-dialled with [Backoff] whenever it drops, for as long
 * as the session runs, and the server socket is re-created if the adapter is toggled.
 * An adapter that is off at Connect, or turned off later, is waited for: the state
 * broadcast wakes the listener and the dialler when it comes back on ([ready] says whether
 * there is a listener or a link right now). An accepted link is the other side's job to
 * restore — it dialled us, it dials again.
 *
 * Threads: `ptt-bt-listen` accepts, `ptt-bt-connect` dials, and every link has its own
 * `ptt-bt-rx-<addr>` reader and `ptt-bt-tx-<addr>` writer draining a [SendQueue], so [send]
 * never waits on a peer that stopped reading. [stop] only sets flags and hands the socket
 * closes to `ptt-bt-stop`; nothing here blocks the caller.
 *
 * Throughput: 16 kHz PCM16 = 32 kB/s, comfortably inside RFCOMM's practical limit.
 *
 * Every call into the Bluetooth stack is treated as able to throw: on Android 12+ the
 * adapter throws [SecurityException] for a missing runtime permission, and vendor stacks
 * throw their own things. Failures become status lines; they never reach the thread's
 * default handler, which would kill the app. A [SecurityException] is terminal for the loop
 * that hit it — retrying without the permission would only repeat the message.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    context: Context,
    private val peer: BluetoothDevice?,
    private val localId: Int
) : Transport {

    override val name = "BT"
    override val relayWithin = true
    /** A listener up or any link alive; false while the adapter is off, so the hello does not claim BT. */
    override val ready: Boolean get() = server != null || links.isNotEmpty()

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    /** Resolved now, while the adapter is on: with it off, `name` is null and retries would show the MAC. */
    private val peerLabel: String? = peer?.let { label(it) }
    private val links = CopyOnWriteArrayList<Link>()
    private val peerIds = ConcurrentHashMap<String, Int>()        // device address → node id, from its direct frames
    private val lifecycle = Any()                                 // orders "add a link" / "start a dial" against "stop"
    private val listenWaiter = Waiter()
    private val dialWaiter = Waiter()
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var dialActive = false                      // a dial loop is running (guarded by lifecycle)
    @Volatile private var dialing: BluetoothSocket? = null        // mid-connect(); interrupt() does not abort that, close() does
    @Volatile private var running = false
    @Volatile private var receiverRegistered = false
    private lateinit var onPacket: (ByteArray, Transport, Any?) -> Unit
    private lateinit var onStatus: (String) -> Unit

    /** One RFCOMM connection; the token the engine gets as `link`. */
    private class Link(val stream: StreamLink, val device: BluetoothDevice, val isDialed: Boolean) {
        @Volatile var superseded = false                          // closed by the tie-break, not by the peer
    }

    /** The adapter going off drops everything; coming back on wakes the listener and the dialler. */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!running) return
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    onStatus("BT: adapter on")
                    listenWaiter.wake()
                    dialWaiter.wake()
                    peer?.let { redial(it) }
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    onStatus("BT: adapter off, waiting")
                    closeSockets(links.toList(), server, dialing)
                }
            }
        }
    }

    override fun start(onPacket: (ByteArray, Transport, Any?) -> Unit, onStatus: (String) -> Unit) {
        this.onPacket = onPacket
        this.onStatus = onStatus
        if (adapter == null) {
            onStatus("BT: not supported on this phone")
            return
        }
        running = true
        ContextCompat.registerReceiver(
            appContext, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        transportThread("ptt-bt-listen", { onStatus("BT listener stopped: ${it.message}") }) { listenLoop(adapter) }
        peer?.let { redial(it) }
        onStatus(if (adapter.isEnabled) "BT: starting" + (peerLabel?.let { ", connecting to $it" } ?: "") else "BT: adapter off, waiting")
    }

    /**
     * Serves incoming connections for the whole session. The server socket dies when the
     * adapter is toggled; it is then re-created with backoff rather than given up on, and
     * while the adapter is off the loop waits for the state broadcast.
     */
    private fun listenLoop(adapter: BluetoothAdapter) {
        val backoff = Backoff()
        while (running) {
            if (!adapter.isEnabled) {
                listenWaiter.await(backoff.next())
                continue
            }
            val srv = try {
                adapter.listenUsingRfcommWithServiceRecord("PTT", SERVICE_UUID)
            } catch (e: SecurityException) {
                onStatus("BT: no permission to listen (${e.message})")
                return
            } catch (e: Exception) {
                if (!running) return
                val wait = backoff.next()
                onStatus("BT: can't listen (${e.message}), retry in ${wait / 1000}s")
                listenWaiter.await(wait)
                continue
            }
            synchronized(lifecycle) {
                if (!running) { closeQuietly(srv); return }    // stop() raced us; leave no listener behind
                server = srv
            }
            backoff.reset()
            onStatus("BT: listening")
            while (running) {
                val s = try { srv.accept() } catch (_: Exception) { break }
                try {
                    addLink(s, "accepted ${label(s.remoteDevice)}", isDialed = false)
                } catch (e: Exception) {                // the peer hung up before we got its streams; keep serving
                    try { s.close() } catch (_: Exception) {}
                    onStatus("BT: accept failed (${e.message})")
                }
            }
            server = null
            closeQuietly(srv)
            if (!running) return
            if (adapter.isEnabled) onStatus("BT: listener dropped, restarting")
            listenWaiter.await(backoff.next())
        }
    }

    /**
     * Starts a dial loop for [dev] unless one is running or a link we dialled is already up:
     * at start, when the adapter comes on, and whenever a link to it drops. Under [lifecycle],
     * so a dial cannot start after [stop] has run.
     */
    private fun redial(dev: BluetoothDevice) {
        synchronized(lifecycle) {
            if (!running || dialActive) return
            if (links.any { it.isDialed && it.device.address == dev.address }) return
            dialActive = true
        }
        transportThread("ptt-bt-connect", { onStatus("BT connect stopped: ${it.message}") }) {
            try {
                dialLoop(dev)
            } finally {
                synchronized(lifecycle) { dialActive = false }
            }
        }
    }

    /**
     * Dials [dev] until it answers, with backoff: the other phone may not have pressed
     * Connect yet, may be out of range, or (Samsung) may just fail the first attempt.
     * A socket that failed to connect is closed — a leaked one keeps the RFCOMM channel
     * busy and makes every later attempt fail too. Returns once the link is up, or once the
     * tie-break says the peer holds the link; the link's reader calls [redial] when it drops.
     */
    private fun dialLoop(dev: BluetoothDevice) {
        val adapter = adapter ?: return
        cancelDiscoveryQuietly()
        val backoff = Backoff()
        while (running) {
            if (!adapter.isEnabled) {
                dialWaiter.await(backoff.next())
                continue
            }
            if (!BluetoothTieBreak.shouldDial(localId, peerIds[dev.address], acceptedAlive(dev))) return
            var socket: BluetoothSocket? = null
            try {
                socket = dev.createRfcommSocketToServiceRecord(SERVICE_UUID)
                dialing = socket
                if (!running) return                     // stop() ran before it could see this socket
                socket.connect()
                dialing = null
                addLink(socket, "connected to ${peerLabel ?: label(dev)}", isDialed = true)
                return
            } catch (e: SecurityException) {
                dialing = null
                try { socket?.close() } catch (_: Exception) {}
                onStatus("BT: no permission to connect (${e.message})")
                return
            } catch (e: Exception) {
                dialing = null
                try { socket?.close() } catch (_: Exception) {}
                if (!running) return
                val wait = backoff.next()
                onStatus("BT: ${peerLabel ?: label(dev)} not answering, retry in ${wait / 1000}s")
                dialWaiter.await(wait)
            } finally {
                if (!running) { dialing = null; try { socket?.close() } catch (_: Exception) {} }
            }
        }
    }

    /**
     * Best effort: an ongoing system scan slows RFCOMM down, but cancelling it needs
     * BLUETOOTH_SCAN on Android 12+, which is optional here. Without it we simply skip —
     * calling anyway throws [SecurityException] and used to take the app down with it.
     */
    private fun cancelDiscoveryQuietly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
    }

    /** Human name of a device, or its address when the name needs a permission we lack. */
    private fun label(dev: BluetoothDevice): String =
        (try { dev.name } catch (_: SecurityException) { null }) ?: dev.address

    private fun acceptedAlive(dev: BluetoothDevice) = links.any { !it.isDialed && it.device.address == dev.address }

    /**
     * Registers a connected socket as a link, unless [stop] already ran — then it is closed
     * instead. Starts its reader and writer; the reader learns the peer's node id from its
     * first direct frame and runs the tie-break, and its `finally` redials.
     */
    private fun addLink(socket: BluetoothSocket, why: String, isDialed: Boolean) {
        val dev = socket.remoteDevice
        val stream = StreamLink(label(dev), socket.inputStream, socket.outputStream) { socket.close() }
        val link = Link(stream, dev, isDialed)
        synchronized(lifecycle) {
            if (!running) { stream.close(); return }
            if (isDialed && links.any { it.isDialed && it.device.address == dev.address }) { stream.close(); return }
            links.add(link)
        }
        onStatus("BT: $why (${links.size} link${if (links.size == 1) "" else "s"})")
        transportThread("ptt-bt-tx-${dev.address}", { onStatus("BT tx stopped: ${it.message}") }) { stream.sendLoop() }
        transportThread("ptt-bt-rx-${dev.address}", { onStatus("BT rx stopped: ${it.message}") }) {
            try {
                stream.readLoop { p ->
                    if (!peerIds.containsKey(dev.address)) {
                        BluetoothTieBreak.directSender(p)?.let { id ->
                            peerIds[dev.address] = id
                            reconcile(dev)
                        }
                    }
                    onPacket(p, this, link)
                }
            } catch (e: IOException) {
                if (running && !link.superseded) onStatus("BT: ${stream.label} dropped")
            } finally {
                links.remove(link)
                stream.close()
                if (running && peer != null && peer.address == dev.address) redial(peer)
            }
        }
        if (peerIds.containsKey(dev.address)) reconcile(dev)
    }

    /** Both phones dialled each other: keep one link per [BluetoothTieBreak], close the other. */
    private fun reconcile(dev: BluetoothDevice) {
        val peerId = peerIds[dev.address] ?: return
        val same = links.filter { it.device.address == dev.address }
        val dialled = same.filter { it.isDialed }
        val accepted = same.filter { !it.isDialed }
        if (dialled.isEmpty() || accepted.isEmpty()) return
        val doomed = if (BluetoothTieBreak.keepsDialled(localId, peerId)) accepted else dialled
        for (l in doomed) {
            l.superseded = true
            l.stream.close()
        }
        onStatus("BT: one link to ${label(dev)} kept")
    }

    override fun send(packet: ByteArray, except: Any?): Boolean {
        var sent = false
        for (link in links) {
            if (link === except) continue
            sent = true
            link.stream.offer(packet)                   // a stuck link closes itself; its reader tears it down
        }
        return sent
    }

    /** Sets the flags and unregisters; the socket closes (each a Bluetooth IPC) run on `ptt-bt-stop`. */
    override fun stop() {
        val toClose: List<Link>
        val srv: BluetoothServerSocket?
        synchronized(lifecycle) {
            running = false
            toClose = links.toList()
            links.clear()
            srv = server
            server = null
        }
        if (receiverRegistered) {
            receiverRegistered = false
            try { appContext.unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        }
        listenWaiter.wake()
        dialWaiter.wake()
        closeSockets(toClose, srv, dialing)
    }

    /** Closes links, a listener and a connect in flight off the caller's thread. */
    private fun closeSockets(links: List<Link>, srv: BluetoothServerSocket?, dial: BluetoothSocket?) {
        transportThread("ptt-bt-stop", { /* a close that failed has nothing left to report */ }) {
            for (l in links) l.stream.close()
            try { dial?.close() } catch (_: Exception) {}   // aborts a connect() in flight
            closeQuietly(srv)
        }
    }

    private fun closeQuietly(srv: BluetoothServerSocket?) {
        try { srv?.close() } catch (_: Exception) {}
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("9d3f1a52-6c0e-4b7a-9f0c-7a2c1e4d5b61")
    }
}
