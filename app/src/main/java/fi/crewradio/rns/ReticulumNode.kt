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
 *  - We announce on connect and every [ANNOUNCE_MS], or every [IDLE_ANNOUNCE_MS] while somebody
 *    is missing ([missing]: no confirmed link, or fewer than the peers we know), so a transport
 *    node that lost our path or a peer that forgot us learns of us in minutes, not ten. Of two
 *    nodes the one whose destination hash sorts lower dials; the other answers the announce of a
 *    newcomer, or of anyone while somebody is missing, with its own, soon.
 *  - A link carries nothing but the two ends' key proofs ([Carry.keyProof], under [confirmKey])
 *    until the far end's has checked out, so a stranger who copies our public name hash and links
 *    in learns nothing but that we exist, and a sealed packet copied from elsewhere proves nothing.
 *  - Floods: an announce under our name and a link request to us each cost a signature, so they
 *    come out of a small budget first, and a link younger than [GRACE_MS] is never evicted.
 *  - Asking the boat ([AskCarry]): [ask] puts a question on every confirmed link and the
 *    plugin's answer, put back together here, completes it. A phone answers nobody's questions.
 *
 * Thread-safe: every entry point takes the node's lock, but the signature and key-agreement work
 * of announces, link requests and proofs is done outside it, so a stranger's flood never holds up
 * [send] on the audio path. [onFrame] returns the channel packets it received instead of calling
 * out, so the engine is never entered with this lock held. Timers run on a monotonic clock: a wall
 * clock stepped back would stop every timer for as long as the step.
 */
internal class ReticulumNode(
    tag: String,
    private val confirmKey: ByteArray,
    private val write: (ByteArray) -> Unit,
    private val onLinks: (Int) -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    val identity: RnsIdentity = RnsIdentity.generate(),
    private val jitter: () -> Long = { 500L + (Math.random() * 1500).toLong() }
) {
    /** One link's state; the transport hands it to the engine as the packet's `link`. */
    inner class Entry(val link: RnsLink, val peer: String?) {
        val key: String = link.id.toHex()
        val createdAt = clock()
        var lastIn = clock()
        var confirmed = false
        var proofAt = 0L
        val joiner = Carry.Joiner()
        val answers = AskCarry.Assembler(AskCarry.ANSWER)
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

    /**
     * A question in flight: asked on [asked] links, done at the first [AskCarry.OK], or once every
     * one of them has answered otherwise (then the first refusal is the answer). [await] blocks.
     */
    inner class Question internal constructor(val id: Int, private val message: ByteArray) {
        private val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var reply: AskCarry.Reply? = null
            private set
        /** The first answer that was not OK, kept in case no link does better. */
        @Volatile var refusal: AskCarry.Reply? = null
            private set
        internal val asked = HashSet<String>()
        private val answered = HashSet<String>()

        /** Waits up to [ms] for the answer; true when there is one (an OK, or every link refusing). */
        fun await(ms: Long): Boolean = done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

        /** Asks again, same id, on every confirmed link: the plugin answers a repeat from memory. */
        fun repeat() {
            synchronized(this@ReticulumNode) { if (reply == null) put(this) }
        }

        /** Stops waiting for it. */
        fun forget() {
            synchronized(this@ReticulumNode) { questions.remove(id) }
        }

        internal fun partsFor(): List<ByteArray> = AskCarry.cut(AskCarry.REQUEST, id, message)

        internal fun offer(link: String, r: AskCarry.Reply) {
            if (reply != null || link !in asked || !answered.add(link)) return
            if (r.status == AskCarry.OK) {
                reply = r
            } else {
                if (refusal == null) refusal = r
                if (!answered.containsAll(asked)) return
                reply = refusal
            }
            questions.remove(id)
            done.countDown()
        }
    }

    /** A token bucket for work a stranger can make us do: [PER_SECOND] a second, bursts of [GATE_BURST]. */
    private inner class Gate {
        private var tokens = GATE_BURST.toDouble()
        private var at = clock()
        fun allow(): Boolean {
            if (left() < 1) return false
            tokens -= 1
            return true
        }

        fun left(): Double {
            val now = clock()
            tokens = minOf(GATE_BURST.toDouble(), tokens + (now - at) * PER_SECOND / 1000.0)
            at = now
            return tokens
        }
    }
    private val announceGate = Gate()
    private val requestGate = Gate()
    /** Announces already checked, by packet hash: the same announce arrives by several paths. */
    private val verified = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > VERIFIED_MAX
    }

    val nameHash: ByteArray = RnsIdentity.nameHash("crewradio.channel.$tag")
    val destination: ByteArray = RnsIdentity.destinationHash(nameHash, identity.hash)
    private val peers = HashMap<String, Peer>()
    private val links = HashMap<String, Entry>()
    private val pending = HashMap<String, Pending>()
    private var connected = false
    private var lastAnnounce = 0L
    private var reannounceAt = 0L
    private val questions = HashMap<Int, Question>()
    private var nextQuestion = (Math.random() * 65536).toInt()

    /** Links that carry channel traffic: up, and their far end has proved the key. */
    @get:Synchronized val linkCount: Int get() = links.values.count { it.link.active && it.confirmed }
    @get:Synchronized val peerCount: Int get() = peers.size

    /** For tests: whether a peer is known, the links held (confirmed or not), our requests waiting. */
    @Synchronized internal fun knows(peerDestination: ByteArray) = peers.containsKey(peerDestination.toHex())
    @Synchronized internal fun entries(): List<Entry> = links.values.toList()
    @get:Synchronized internal val pendingCount: Int get() = pending.size
    @Synchronized internal fun peerSeenAt(peerDestination: ByteArray): Long? = peers[peerDestination.toHex()]?.seenAt
    @Synchronized internal fun peerEmitted(peerDestination: ByteArray): Long? = peers[peerDestination.toHex()]?.emitted
    @Synchronized internal fun budgetLeft(): Pair<Double, Double> = announceGate.left() to requestGate.left()
    /** For tests: when we last announced, and whether an answering announce is due. */
    @get:Synchronized internal val announcedAt: Long get() = lastAnnounce
    @get:Synchronized internal val reannounceDue: Boolean get() = reannounceAt != 0L

    /** The transport node is reachable: announce at once. */
    @Synchronized fun connected() {
        connected = true
        announce()
    }

    /** The connection is gone, and every link with it; the peers stay, their paths run through the same transport node. */
    @Synchronized fun disconnected() {
        connected = false
        // A peer we just had a link with is as fresh as that link, not as its last announce: the
        // dialler redials it as soon as the connection is back ([forget] does the same for one link).
        for (e in links.values) e.peer?.let { peers[it] }?.let { it.seenAt = maxOf(it.seenAt, e.lastIn) }
        links.clear()
        pending.clear()
        for (p in peers.values) { p.link = null; p.dialAt = 0 }
    }

    /** Closes every link politely before the connection goes. */
    @Synchronized fun closeAll() {
        for (e in links.values) write(e.link.closePacket())
    }

    /** Sends a sealed channel packet on every confirmed link but [except]. True when it went anywhere. */
    @Synchronized fun send(packet: ByteArray, except: Any?): Boolean {
        if (!connected) return false
        var sent = false
        for (e in links.values) {
            if (e === except || !e.link.active || !e.confirmed) continue
            for (part in Carry.cut(packet, e.cutId++)) write(e.link.dataPacket(part))
            sent = true
        }
        return sent
    }

    /**
     * Puts a question to the boat ([AskCarry] request message: op and body) on every confirmed
     * link; null when there is none, or the message is too large to carry. The caller waits on the
     * returned question, repeats it once when the wait runs out, and forgets it.
     */
    @Synchronized fun ask(message: ByteArray): Question? {
        if (!connected || links.values.none { it.link.active && it.confirmed }) return null
        var id = nextQuestion++ and 0xFFFF
        while (questions.containsKey(id)) id = nextQuestion++ and 0xFFFF
        val q = Question(id, message)
        try {
            q.partsFor()
        } catch (_: IllegalArgumentException) {
            return null
        }
        questions[id] = q
        put(q)
        return q
    }

    private fun put(q: Question) {
        val parts = q.partsFor()
        for (e in links.values) {
            if (!e.link.active || !e.confirmed) continue
            q.asked += e.key
            for (part in parts) write(e.link.dataPacket(part))
        }
    }

    /** A part of an answer on a confirmed link; one for a question nobody is waiting on is dropped unread. */
    private fun onAnswer(e: Entry, payload: ByteArray) {
        val kind = payload[0].toInt() and 0xFF
        if (kind != AskCarry.ANSWER || !questions.containsKey(AskCarry.idOf(payload))) return
        val (id, message) = e.answers.push(payload) ?: return
        val r = AskCarry.reply(message) ?: return
        questions[id]?.offer(e.key, r)
    }

    /**
     * One Reticulum packet from the transport node; returns the channel packets it carried, each
     * with its link. Not synchronized as a whole: the signature work is done outside the lock.
     */
    fun onFrame(raw: ByteArray): List<Pair<ByteArray, Entry>> {
        val p = RnsPacket.decode(raw) ?: return emptyList()
        when (p.packetType) {
            RnsPacket.ANNOUNCE -> onAnnounce(p)
            RnsPacket.LINKREQUEST -> onLinkRequest(p)
            RnsPacket.PROOF -> onProof(p)
            else -> return synchronized(this) { onData(p) }
        }
        return emptyList()
    }

    private fun onAnnounce(p: RnsPacket) {
        if (p.destType != RnsPacket.SINGLE || p.data.size < RnsIdentity.ANNOUNCE_MIN) return
        val nh = p.data.copyOfRange(RnsIdentity.PUBLIC_BYTES, RnsIdentity.PUBLIC_BYTES + RnsIdentity.NAME_HASH_BYTES)
        if (!nh.contentEquals(nameHash)) return                         // another channel: no signature check spent
        if (p.destination.contentEquals(destination)) return            // our own, echoed back
        val hash = RnsCrypto.sha256(RnsPacket.hashablePart(p.raw)).toHex()
        // A copy of one already checked costs no budget; a flood under our public name, no signature check.
        if (synchronized(this) { verified.containsKey(hash) || !announceGate.allow() }) return
        val a = RnsIdentity.parseAnnounce(p) ?: return                  // the signature, outside the lock
        synchronized(this) {
            verified[hash] = true
            onAnnounce(p, a)
        }
    }

    private fun onAnnounce(p: RnsPacket, a: RnsIdentity.Announce) {
        val key = p.destination.toHex()
        var peer = peers[key]
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
        } else if (fresh || missing()) {
            // It dials us, but first it has to hear of us. A node we already knew too, while somebody
            // is missing: it may be the one that forgot us, and nothing else would tell it.
            reannounceAt = maxOf(reannounceAt, lastAnnounce + REANNOUNCE_MS, clock() + jitter())
        }
    }

    private fun onLinkRequest(p: RnsPacket) {
        if (p.destType != RnsPacket.SINGLE || !p.destination.contentEquals(destination)) return
        val id = RnsLink.linkIdOf(p.raw, p.data.size).toHex()
        // A copy of one we already answered, one with no slot it could have, or a flood of requests:
        // no key agreement for it (and the first two spend no budget).
        if (synchronized(this) { links.containsKey(id) || !canMakeRoom() || !requestGate.allow() }) return
        // The key agreement and the signature, outside the lock; and only a request a link can
        // come of may cost another its slot.
        val (link, proof) = RnsLink.accept(identity, p) ?: return
        synchronized(this) {
            if (!connected || links.containsKey(id)) return
            if (links.size + pending.size >= MAX_LINKS && !evictLink()) return
            addLink(link, null)
            write(proof)
        }
    }

    private fun onProof(p: RnsPacket) {
        if (p.destType != RnsPacket.LINK || p.context != RnsPacket.CTX_LRPROOF) return
        val key = p.destination.toHex()
        val pend = synchronized(this) { pending[key] } ?: return
        val link = pend.request.complete(p) ?: return                   // the signature, outside the lock
        synchronized(this) {
            if (pending[key] !== pend) return                           // timed out or evicted meanwhile
            pending.remove(key)
            val e = addLink(link, pend.peer)
            peers[pend.peer]?.let { it.link = e; it.backoffMs = 1000 }
            write(link.rttPacket((clock() - pend.sentAt) / 1000.0))
            sendProof(e)                                                // after the RTT, which makes it active at the far end
        }
    }

    private fun onData(p: RnsPacket): List<Pair<ByteArray, Entry>> {
        if (p.packetType != RnsPacket.DATA || p.destType != RnsPacket.LINK) return emptyList()
        val e = links[p.destination.toHex()] ?: return emptyList()
        val wasActive = e.link.active
        val event = e.link.handle(p) ?: return emptyList()
        val now = clock()
        e.lastIn = now
        if (!wasActive && e.link.active) sendProof(e)                  // the far end's RTT: the link is up on our side too
        when (event) {
            is RnsLink.Event.Keepalive -> event.reply?.let { write(it) }
            RnsLink.Event.Close -> forget(e)
            RnsLink.Event.Rtt -> {}
            is RnsLink.Event.Data -> {
                if (Carry.isKeyProof(event.plain)) { onKeyProof(e, event.plain, now); return emptyList() }
                if (!e.confirmed) return emptyList()                    // nothing counts before the far end has proved the key
                if (AskCarry.isAsk(event.plain)) { onAnswer(e, event.plain); return emptyList() }
                val packet = e.joiner.push(event.plain) ?: return emptyList()
                if (!e.allow(now)) return emptyList()
                return listOf(packet to e)
            }
        }
        return emptyList()
    }

    /** The far end's key proof: the link is confirmed, and ours goes again if it seems to have missed it. */
    private fun onKeyProof(e: Entry, payload: ByteArray, now: Long) {
        if (!e.link.active || !Carry.proofMatches(payload, confirmKey, e.link.id, !e.link.initiator)) return
        if (e.confirmed) {
            // It keeps sending its proof, so it has not had ours: once a second at most, again.
            if (now - e.proofAt >= 1000) sendProof(e)
            return
        }
        e.confirmed = true
        linksChanged()
    }

    private fun sendProof(e: Entry) {
        if (!e.link.active) return
        write(e.link.dataPacket(Carry.keyProof(confirmKey, e.link.id, e.link.initiator)))
        e.proofAt = clock()
    }

    private fun weDial(peerDestination: ByteArray): Boolean = compare(destination, peerDestination) < 0

    private fun pendingFor(peer: String) = pending.values.any { it.peer == peer }

    /*
     * The name hash we announce under is public, so anyone can announce under it (each with an
     * identity of their own) or open links to us. With a hard cap alone, a stranger who filled
     * the tables first would keep the crew out. So a full table makes room: the link that has
     * waited longest without proving the key, the peer heard from longest ago with no confirmed
     * link. A link that has proved the key, and the peer behind it, is never the one to go; nor is
     * one younger than [GRACE_MS], since a crew link needs a round trip to prove itself and a flood
     * of requests would otherwise push every new one out before it could.
     */

    /** True when there is a free slot, or one [evictLink] may make. */
    private fun canMakeRoom(): Boolean {
        if (links.size + pending.size < MAX_LINKS) return true
        val now = clock()
        return links.values.any { !it.confirmed && now - it.createdAt >= GRACE_MS } || pending.values.any { now - it.sentAt >= GRACE_MS }
    }

    /**
     * Makes one slot: closes the oldest unconfirmed link past its grace, else drops the oldest
     * request of ours past it (announces from strangers can fill the table with those just as
     * well); false when there is neither.
     */
    private fun evictLink(): Boolean {
        val now = clock()
        val victim = links.values.filter { !it.confirmed && now - it.createdAt >= GRACE_MS }.minByOrNull { it.createdAt }
        if (victim != null) {
            close(victim)
            return true
        }
        val oldest = pending.entries.filter { now - it.value.sentAt >= GRACE_MS }.minByOrNull { it.value.sentAt } ?: return false
        pending.remove(oldest.key)
        peers[oldest.value.peer]?.let { it.dialAt = clock() + it.backoffMs; it.backoffMs = minOf(it.backoffMs * 2, 15_000) }
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
        return e
    }

    private fun close(e: Entry) {
        write(e.link.closePacket())
        forget(e)
    }

    private fun forget(e: Entry) {
        if (links[e.key] !== e) return
        links.remove(e.key)
        if (e.link.active && e.confirmed) linksChanged()
        val peer = e.peer?.let { peers[it] } ?: return
        // A peer we just had a link with is fresher than its last announce says: a flood of strangers'
        // announces must not make it the stalest, and forget it, the moment its link drops.
        peer.seenAt = maxOf(peer.seenAt, e.lastIn)
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

    /**
     * Somebody is missing: no confirmed link at all, or fewer than the peers we know. A link we
     * answered does not say whose it is, so the count is all there is to go on; a peer that left
     * counts as missing until it is forgotten.
     */
    @Synchronized internal fun missing(): Boolean {
        val n = linkCount
        return n == 0 || n < peers.size
    }

    /** Once a second: announces due, requests and links timed out, redials, forgotten peers. */
    @Synchronized fun tick() {
        if (!connected) return
        val now = clock()
        val every = if (missing()) IDLE_ANNOUNCE_MS else ANNOUNCE_MS
        if (now - lastAnnounce >= every || (reannounceAt != 0L && now >= reannounceAt)) announce()
        val expired = pending.entries.filter { now - it.value.sentAt >= LINK_TIMEOUT_MS }
        for ((id, p) in expired) {
            pending.remove(id)
            peers[p.peer]?.let { it.dialAt = now + it.backoffMs; it.backoffMs = minOf(it.backoffMs * 2, 15_000) }
        }
        for (e in links.values.toList()) {
            if (now - e.lastIn > STALE_MS || (!e.confirmed && now - e.createdAt > CONFIRM_MS)) { close(e); continue }
            if (!e.confirmed && e.link.active && now - e.proofAt >= PROOF_RESEND_MS) sendProof(e)
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
        const val IDLE_ANNOUNCE_MS = 2 * 60_000L
        const val REANNOUNCE_MS = 3_000L
        const val LINK_TIMEOUT_MS = 10_000L
        const val STALE_MS = 12_000L
        const val CONFIRM_MS = 15_000L
        const val PROOF_RESEND_MS = 2_000L
        const val GRACE_MS = 5_000L
        const val GATE_BURST = 20
        private const val VERIFIED_MAX = 256
        private const val PER_SECOND = 10
        const val PEER_FORGET_MS = 3 * ANNOUNCE_MS
        const val MAX_LINKS = 32
        const val MAX_PEERS = 64
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
