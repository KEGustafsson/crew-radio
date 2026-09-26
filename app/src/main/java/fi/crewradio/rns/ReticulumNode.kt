package fi.crewradio.rns

/**
 * The Reticulum end node behind [fi.crewradio.transport.ReticulumTransport], without the socket:
 * announces, peers, links and the carrying of channel packets, driven by [onFrame], [tick] and
 * [send], writing whole Reticulum packets through [write]. Pure Kotlin with an injectable clock,
 * so the whole handshake runs in unit tests over a fake medium. The plugin's
 * lib/rns/transport.js is the same design:
 *
 *  - Our destination is `crewradio.channel.<tag>`, the tag from the packet key
 *    ([fi.crewradio.ChannelCrypto.reticulumTag]); the identity is made fresh for each session.
 *  - We announce on connect and every [ANNOUNCE_MS]. Of two nodes the one whose destination hash
 *    sorts lower dials; the other answers a newcomer's announce with its own, soon.
 *  - A link carries hellos from the start and everything else once the far end has sent a packet
 *    that opened with the channel key ([confirm]).
 *
 * Thread-safe: every entry point takes the node's lock. [onFrame] returns the channel packets it
 * received instead of calling out, so the engine is never entered with this lock held.
 */
internal class ReticulumNode(
    tag: String,
    private val write: (ByteArray) -> Unit,
    private val onLinks: (Int) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    val identity: RnsIdentity = RnsIdentity.generate(),
    private val jitter: () -> Long = { 500L + (Math.random() * 1500).toLong() }
) {
    /** One link's state; the transport hands it to the engine as the packet's `link`. */
    inner class Entry(val link: RnsLink, val peer: String?) {
        val key: String = link.id.toHex()
        val createdAt = clock()
        var lastIn = clock()
        var confirmed = false
        val joiner = Carry.Joiner()
        var cutId = 0
        private var tokens = BURST.toDouble()
        private var refilled = clock()

        /** A per-link budget: 400 packets a second, bursts of 800, before the engine sees anything. */
        fun allow(now: Long): Boolean {
            tokens = minOf(BURST.toDouble(), tokens + (now - refilled) * RATE / 1000.0)
            refilled = now
            if (tokens < 1) return false
            tokens -= 1
            return true
        }
    }

    private class Peer(var announce: RnsIdentity.Announce) {
        var transportId: ByteArray? = null
        var hops = 0
        var seenAt = 0L
        var emitted = 0L
        var dialAt = 0L
        var backoffMs = 1000L
        var link: Entry? = null
    }

    private class Pending(val request: RnsLink.Request, val peer: String, val sentAt: Long)

    val nameHash: ByteArray = RnsIdentity.nameHash("crewradio.channel.$tag")
    val destination: ByteArray = RnsIdentity.destinationHash(nameHash, identity.hash)
    private val peers = HashMap<String, Peer>()
    private val links = HashMap<String, Entry>()
    private val pending = HashMap<String, Pending>()
    private var connected = false
    private var lastAnnounce = 0L
    private var reannounceAt = 0L

    @get:Synchronized val linkCount: Int get() = links.values.count { it.link.active }
    @get:Synchronized val peerCount: Int get() = peers.size

    /** For tests: whether a peer is known, and the links held, confirmed or not. */
    @Synchronized internal fun knows(peerDestination: ByteArray) = peers.containsKey(peerDestination.toHex())
    @Synchronized internal fun entries(): List<Entry> = links.values.toList()

    /** The transport node is reachable: announce at once. */
    @Synchronized fun connected() {
        connected = true
        announce()
    }

    /** The connection is gone, and every link with it; the peers stay, their paths run through the same transport node. */
    @Synchronized fun disconnected() {
        connected = false
        links.clear()
        pending.clear()
        for (p in peers.values) { p.link = null; p.dialAt = 0 }
    }

    /** Closes every link politely before the connection goes. */
    @Synchronized fun closeAll() {
        for (e in links.values) write(e.link.closePacket())
    }

    /**
     * Sends a sealed channel packet on every active link but [except]; hellos also go to links
     * not yet confirmed, so each end can prove it holds the key. True when it went anywhere.
     */
    @Synchronized fun send(packet: ByteArray, except: Any?): Boolean {
        if (!connected) return false
        val hello = packet.size > 3 && packet[3].toInt() == HELLO_CODEC
        var sent = false
        for (e in links.values) {
            if (e === except || !e.link.active || (!e.confirmed && !hello)) continue
            for (part in Carry.cut(packet, e.cutId++)) write(e.link.dataPacket(part))
            sent = true
        }
        return sent
    }

    /** The engine says a packet from [via] opened with the channel key. */
    @Synchronized fun confirm(via: Any?) {
        val e = via as? Entry ?: return
        if (links[e.key] === e) e.confirmed = true
    }

    /** One Reticulum packet from the transport node; returns the channel packets it carried, each with its link. */
    @Synchronized fun onFrame(raw: ByteArray): List<Pair<ByteArray, Entry>> {
        val p = RnsPacket.decode(raw) ?: return emptyList()
        return when (p.packetType) {
            RnsPacket.ANNOUNCE -> { onAnnounce(p); emptyList() }
            RnsPacket.LINKREQUEST -> { onLinkRequest(p); emptyList() }
            RnsPacket.PROOF -> { onProof(p); emptyList() }
            else -> onData(p)
        }
    }

    private fun onAnnounce(p: RnsPacket) {
        if (p.destType != RnsPacket.SINGLE || p.data.size < RnsIdentity.ANNOUNCE_MIN) return
        val nh = p.data.copyOfRange(RnsIdentity.PUBLIC_BYTES, RnsIdentity.PUBLIC_BYTES + RnsIdentity.NAME_HASH_BYTES)
        if (!nh.contentEquals(nameHash)) return                         // another channel: no signature check spent
        if (p.destination.contentEquals(destination)) return            // our own, echoed back
        val key = p.destination.toHex()
        var peer = peers[key]
        val a = RnsIdentity.parseAnnounce(p) ?: return
        if (peer != null && a.emitted < peer.emitted) return            // an older announce replayed
        val fresh = peer == null
        if (peer == null && peers.size >= MAX_PEERS && !evictPeer()) return
        if (peer == null) peer = Peer(a).also { peers[key] = it }
        peer.announce = a
        peer.emitted = a.emitted
        peer.seenAt = clock()
        peer.transportId = if (p.headerType == RnsPacket.HEADER_2) p.transportId else null
        peer.hops = p.hops + 1
        if (weDial(a.destination)) {
            // A newer announce while our link to it has gone quiet: it reconnected, that link is dead.
            peer.link?.let { if (clock() - it.lastIn > 3000) close(it) }
            if (peer.link == null && !pendingFor(key)) { peer.dialAt = 0; linkTo(key, peer) }
        } else if (fresh) {
            reannounceAt = maxOf(reannounceAt, lastAnnounce + REANNOUNCE_MS, clock() + jitter())
        }
    }

    private fun onLinkRequest(p: RnsPacket) {
        if (p.destType != RnsPacket.SINGLE || !p.destination.contentEquals(destination)) return
        val id = RnsLink.linkIdOf(p.raw, p.data.size).toHex()
        if (links.containsKey(id)) return
        if (links.size + pending.size >= MAX_LINKS && !evictLink()) return
        val (link, proof) = RnsLink.accept(identity, p) ?: return
        addLink(link, null)
        write(proof)
    }

    private fun onProof(p: RnsPacket) {
        if (p.destType != RnsPacket.LINK || p.context != RnsPacket.CTX_LRPROOF) return
        val key = p.destination.toHex()
        val pend = pending[key] ?: return
        val link = pend.request.complete(p) ?: return
        pending.remove(key)
        val e = addLink(link, pend.peer)
        peers[pend.peer]?.let { it.link = e; it.backoffMs = 1000 }
        write(link.rttPacket((clock() - pend.sentAt) / 1000.0))
    }

    private fun onData(p: RnsPacket): List<Pair<ByteArray, Entry>> {
        if (p.packetType != RnsPacket.DATA || p.destType != RnsPacket.LINK) return emptyList()
        val e = links[p.destination.toHex()] ?: return emptyList()
        val wasActive = e.link.active
        val event = e.link.handle(p) ?: return emptyList()
        val now = clock()
        e.lastIn = now
        if (!wasActive && e.link.active) linksChanged()
        when (event) {
            is RnsLink.Event.Keepalive -> event.reply?.let { write(it) }
            RnsLink.Event.Close -> forget(e)
            RnsLink.Event.Rtt -> {}
            is RnsLink.Event.Data -> {
                val packet = e.joiner.push(event.plain) ?: return emptyList()
                if (!e.allow(now)) return emptyList()
                return listOf(packet to e)
            }
        }
        return emptyList()
    }

    private fun weDial(peerDestination: ByteArray): Boolean = compare(destination, peerDestination) < 0

    private fun pendingFor(peer: String) = pending.values.any { it.peer == peer }

    /*
     * The name hash we announce under is public, so anyone can announce under it (each with an
     * identity of their own) or open links to us. With a hard cap alone, a stranger who filled
     * the tables first would keep the crew out. So a full table makes room: the link that has
     * waited longest without proving the key, the peer heard from longest ago with no confirmed
     * link. A link that has proved the key, and the peer behind it, is never the one to go.
     */

    /** Closes the oldest unconfirmed link; false when every link is confirmed. */
    private fun evictLink(): Boolean {
        val victim = links.values.filter { !it.confirmed }.minByOrNull { it.createdAt } ?: return false
        close(victim)
        return true
    }

    /** Forgets the stalest peer without a confirmed link, and its request or link; false when there is none. */
    private fun evictPeer(): Boolean {
        val (key, victim) = peers.entries.filter { it.value.link?.confirmed != true }.minByOrNull { it.value.seenAt } ?: return false
        pending.entries.removeAll { it.value.peer == key }
        victim.link?.let { close(it) }
        peers.remove(key)
        return true
    }

    private fun linkTo(key: String, peer: Peer) {
        if (!connected || (links.size + pending.size >= MAX_LINKS && !evictLink())) return
        val req = RnsLink.request(peer.announce, if (peer.hops > 1) peer.transportId else null)
        pending[req.id.toHex()] = Pending(req, key, clock())
        write(req.raw)
    }

    private fun addLink(link: RnsLink, peer: String?): Entry {
        val e = Entry(link, peer)
        links[e.key] = e
        if (link.active) linksChanged()
        return e
    }

    private fun close(e: Entry) {
        write(e.link.closePacket())
        forget(e)
    }

    private fun forget(e: Entry) {
        if (links[e.key] !== e) return
        links.remove(e.key)
        if (e.link.active) linksChanged()
        val peer = e.peer?.let { peers[it] } ?: return
        if (peer.link === e) {
            peer.link = null
            peer.dialAt = clock() + peer.backoffMs
            peer.backoffMs = minOf(peer.backoffMs * 2, 15_000)
        }
    }

    private fun linksChanged() = onLinks(linkCount)

    private fun announce() {
        val (dest, data) = RnsIdentity.buildAnnounce(identity, nameHash)
        write(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, dest, data = data))
        lastAnnounce = clock()
        reannounceAt = 0
    }

    /** Once a second: announces due, requests and links timed out, redials, forgotten peers. */
    @Synchronized fun tick() {
        if (!connected) return
        val now = clock()
        if (now - lastAnnounce >= ANNOUNCE_MS || (reannounceAt != 0L && now >= reannounceAt)) announce()
        val expired = pending.entries.filter { now - it.value.sentAt >= LINK_TIMEOUT_MS }
        for ((id, p) in expired) {
            pending.remove(id)
            peers[p.peer]?.let { it.dialAt = now + it.backoffMs; it.backoffMs = minOf(it.backoffMs * 2, 15_000) }
        }
        for (e in links.values.toList()) {
            if (now - e.lastIn > STALE_MS || (!e.confirmed && now - e.createdAt > CONFIRM_MS)) close(e)
        }
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val (key, peer) = it.next()
            if (peer.link == null && now - peer.seenAt > PEER_FORGET_MS) { it.remove(); continue }
            // Redial while it is still announcing (every ANNOUNCE_MS); after that its next announce does it.
            if (peer.link == null && weDial(peer.announce.destination) && !pendingFor(key) && now >= peer.dialAt &&
                now - peer.seenAt < ANNOUNCE_MS + 60_000
            ) linkTo(key, peer)
        }
    }

    companion object {
        const val ANNOUNCE_MS = 10 * 60_000L
        const val REANNOUNCE_MS = 3_000L
        const val LINK_TIMEOUT_MS = 10_000L
        const val STALE_MS = 12_000L
        const val CONFIRM_MS = 15_000L
        const val PEER_FORGET_MS = 3 * ANNOUNCE_MS
        const val MAX_LINKS = 32
        const val MAX_PEERS = 64
        private const val HELLO_CODEC = 2
        private const val RATE = 400
        private const val BURST = 800

        private fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in a.indices) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return 0
        }
    }
}

private const val HEX = "0123456789abcdef"

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[2 * i] = HEX[v ushr 4]
        out[2 * i + 1] = HEX[v and 15]
    }
    return String(out)
}
