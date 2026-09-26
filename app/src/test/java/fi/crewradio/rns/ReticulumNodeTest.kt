package fi.crewradio.rns

import fi.crewradio.Hello
import fi.crewradio.Packet
import fi.crewradio.TestKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nodes on a fake shared medium: every packet one writes, the others read, as on one hub segment. */
class ReticulumNodeTest {
    private var now = 1_000_000L
    private val inbox = HashMap<ReticulumNode, MutableList<Pair<ByteArray, ReticulumNode.Entry>>>()
    private val nodes = ArrayList<ReticulumNode>()
    private val queue = ArrayDeque<Pair<ReticulumNode, ByteArray>>()
    private val crewKey by lazy { TestKeys.crypto.reticulumConfirmKey }
    private val strangerKey = ByteArray(32) { 9 }

    private fun node(tag: String = "a2ddc18dee75e2bd", confirmKey: ByteArray = crewKey): ReticulumNode {
        lateinit var n: ReticulumNode
        n = ReticulumNode(tag, confirmKey, write = { raw -> queue.addLast(n to raw) }, clock = { now }, jitter = { 0 })
        nodes.add(n)
        inbox[n] = ArrayList()
        return n
    }

    /** Delivers everything written, and everything written in answer, until the medium is quiet. */
    private fun settle() {
        while (queue.isNotEmpty()) {
            val (from, raw) = queue.removeFirst()
            for (n in nodes) if (n !== from) inbox[n]!!.addAll(n.onFrame(raw))
        }
    }

    /** Two nodes, the first returned the one that dials; linked (and, with the same key, confirmed). */
    private fun linked(tag: String = "a2ddc18dee75e2bd", secondKey: ByteArray = crewKey): Pair<ReticulumNode, ReticulumNode> {
        val x = node(tag)
        val y = node(tag, secondKey)
        val (a, b) = if (compare(x.destination, y.destination) < 0) x to y else y to x
        a.connected(); b.connected(); settle()
        now += 100; b.tick(); a.tick(); settle()
        return a to b
    }

    private fun sealed(codec: Packet.Codec, payload: ByteArray, seq: Int): ByteArray {
        val header = Packet.encode(7, seq, codec, 4, ByteArray(0), time = System.currentTimeMillis() / 1000)
        return header + TestKeys.crypto.seal(Packet.aadOf(header), payload)
    }

    private fun announce(id: RnsIdentity, nameHash: ByteArray, emitted: Long): ByteArray {
        val (d, data) = RnsIdentity.buildAnnounce(id, nameHash, ByteArray(0), ByteArray(5), emitted)
        return RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, d, data = data)
    }

    /** A link request to [to], as anyone who has heard its announce can make. */
    private fun request(to: ReticulumNode): RnsLink.Request {
        val target = RnsIdentity.parseAnnounce(RnsPacket.decode(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, to.destination,
            data = RnsIdentity.buildAnnounce(to.identity, to.nameHash).second))!!)!!
        return RnsLink.request(target, null)
    }

    @Test
    fun twoNodesLinkProveTheKeyToEachOtherAndCarryTheChannel() {
        val (a, b) = linked()
        assertEquals("each end's key proof checked out at the other", 1, a.linkCount)
        assertEquals(1, b.linkCount)
        val hello = sealed(Packet.Codec.HELLO, Hello("A", 8, 4, 1).encode(), 1)
        val opus = sealed(Packet.Codec.OPUS, ByteArray(60) { 1 }, 2)
        val pcm = sealed(Packet.Codec.PCM, ByteArray(640) { 2 }, 3)
        assertTrue(a.send(hello, null))
        assertTrue(a.send(opus, null))
        assertTrue(a.send(pcm, null))
        settle()
        assertEquals("PCM arrives whole, in two parts", listOf(hello.size, opus.size, pcm.size), inbox[b]!!.map { it.first.size })
        assertFalse("never back where it came from", b.send(opus, inbox[b]!![0].second))
        assertTrue(b.send(opus, null))
        settle()
        assertEquals(1, inbox[a]!!.size)
    }

    @Test
    fun aStrangerWhoCopiesTheNameHashGetsAndPassesNothingHoweverItReplaysOrReflects() {
        linked(secondKey = strangerKey)
        val me = nodes[0]                                       // made with the crew's key; nodes[1] with another
        val stranger = nodes[1]
        assertEquals("linked", 1, me.entries().size)
        assertEquals("but its proof, made with another key, proves nothing", 0, me.linkCount + stranger.linkCount)
        val hello = sealed(Packet.Codec.HELLO, Hello("A", 8, 4, 1).encode(), 1)
        assertFalse("nothing of ours goes to it, hellos included", me.send(hello, null))
        // A hub that reflects what we send on the link back to us: our own proof, our own role.
        now += ReticulumNode.PROOF_RESEND_MS
        me.tick()
        val ours = ArrayList<ByteArray>()
        while (queue.isNotEmpty()) { val (from, raw) = queue.removeFirst(); if (from === me) ours.add(raw) }
        assertTrue("our proof went out again", ours.isNotEmpty())
        for (raw in ours) me.onFrame(raw)
        assertEquals("our proof reflected back proves nothing", 0, me.linkCount)
        // A genuine sealed packet copied from anywhere, on the stranger's link, is dropped unread.
        val link = me.entries().single()
        for (raw in Carry.cut(hello, 0).map { link.link.dataPacket(it) }) assertTrue(me.onFrame(raw).isEmpty())
        // The proofs keep the link fresh, so it is the confirm rule, not silence, that closes it.
        repeat((ReticulumNode.CONFIRM_MS / ReticulumNode.PROOF_RESEND_MS).toInt()) {
            now += ReticulumNode.PROOF_RESEND_MS; me.tick(); stranger.tick(); settle()
        }
        now += 1000; me.tick(); settle()
        assertFalse(me.entries().any { it.key == link.key })
        assertEquals(0, me.linkCount)
    }

    @Test
    fun aKeyProofLostOnTheWayIsSentAgainAndTheEndsStillConfirm() {
        val x = node()
        val y = node()
        val (a, b) = if (compare(x.destination, y.destination) < 0) x to y else y to x
        // The first two link data packets with no context are the two ends' proofs (the RTT has its
        // own context): drop them, deliver everything else.
        var dropped = 0
        fun deliverDroppingProofs() {
            while (queue.isNotEmpty()) {
                val (from, raw) = queue.removeFirst()
                val p = RnsPacket.decode(raw)
                if (p != null && p.packetType == RnsPacket.DATA && p.destType == RnsPacket.LINK && p.context == RnsPacket.CTX_NONE && dropped < 2) {
                    dropped++
                    continue
                }
                for (n in nodes) if (n !== from) inbox[n]!!.addAll(n.onFrame(raw))
            }
        }
        a.connected(); b.connected(); deliverDroppingProofs()
        now += 100; b.tick(); a.tick(); deliverDroppingProofs()
        assertEquals(2, dropped)
        assertEquals(0, a.linkCount + b.linkCount)
        repeat(2) { now += ReticulumNode.PROOF_RESEND_MS; a.tick(); b.tick(); settle() }
        assertEquals(1, a.linkCount)
        assertEquals(1, b.linkCount)
    }

    @Test
    fun anotherChannelIsNeverDialledAndAnOlderAnnounceChangesNothing() {
        val mine = node("1111111111111111")
        val other = node("2222222222222222")
        mine.connected(); other.connected(); settle()
        assertEquals(0, mine.peerCount)
        assertEquals(0, other.peerCount)
        val peer = RnsIdentity.generate()
        mine.onFrame(announce(peer, mine.nameHash, 2000))
        mine.onFrame(announce(peer, mine.nameHash, 1000))
        assertEquals(1, mine.peerCount)
        assertEquals("the older announce was ignored", 2000L, mine.peerEmitted(RnsIdentity.destinationHash(mine.nameHash, peer.hash)))
        val (d, data) = RnsIdentity.buildAnnounce(mine.identity, mine.nameHash)
        mine.onFrame(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, d, data = data))
        assertEquals("our own echo is not a peer", 1, mine.peerCount)
        mine.onFrame(byteArrayOf(1, 2, 3))
    }

    @Test
    fun silentLinksCloseTheDiallerRedialsUnprovenRequestsTimeOutAndDisconnectClearsLinks() {
        val (a, b) = linked()
        assertEquals(1, a.linkCount)
        now += ReticulumNode.STALE_MS + 1
        a.tick(); settle()
        assertEquals(0, a.entries().size)
        assertEquals("the close reached the far end", 0, b.entries().size)
        now += 2000
        a.tick(); settle()
        assertEquals("redialled after the backoff", 1, a.linkCount)
        nodes.remove(b)                      // b goes away: the link falls silent, requests go unanswered
        now += ReticulumNode.STALE_MS + 1
        a.tick(); queue.clear()
        assertEquals(0, a.entries().size)
        now += 20_000
        a.tick(); queue.clear()
        assertEquals("a redial is waiting", 1, a.pendingCount)
        now += ReticulumNode.LINK_TIMEOUT_MS + 1
        a.tick(); queue.clear()
        assertEquals("given up", 0, a.pendingCount)
        now += 20_000
        a.tick(); queue.clear()
        a.disconnected()
        assertEquals(0, a.pendingCount)
        assertEquals(0, a.entries().size)
        assertFalse(a.send(ByteArray(40), null))
    }

    @Test
    fun aFullPeerTableMakesRoomButNeverForgetsAConfirmedOrFreshlyLinkedPeer() {
        val (a, b) = linked("5555555555555555")
        assertEquals(1, a.linkCount)
        queue.clear()
        nodes.remove(b)
        // Strangers announcing under our public name hash fill the table; each new one still gets in.
        val first = RnsIdentity.generate()
        now += 100
        a.onFrame(announce(first, a.nameHash, now / 1000))
        repeat(ReticulumNode.MAX_PEERS) { now += 100; a.onFrame(announce(RnsIdentity.generate(), a.nameHash, now / 1000)); queue.clear() }
        assertEquals(ReticulumNode.MAX_PEERS, a.peerCount)
        assertFalse("the stalest made room", a.knows(RnsIdentity.destinationHash(a.nameHash, first.hash)))
        assertTrue("the peer behind a confirmed link is never the one to go", a.knows(b.destination))
        // Its link drops: it is as fresh as that link was, not as its announce.
        val lastHeard = now
        a.entries().first { it.confirmed }.lastIn = lastHeard
        now += ReticulumNode.STALE_MS + 1
        a.tick(); queue.clear()
        assertEquals(lastHeard, a.peerSeenAt(b.destination))
        now += 100
        a.onFrame(announce(RnsIdentity.generate(), a.nameHash, now / 1000)); queue.clear()
        assertTrue("strangers heard before its last link go first", a.knows(b.destination))
    }

    @Test
    fun aFullLinkTableMakesRoomOnlyPastTheGraceAndNeverFromAConfirmedLink() {
        val me = node("6666666666666666")
        me.connected(); queue.clear()
        val ids = ArrayList<String>()
        repeat(ReticulumNode.MAX_LINKS) {
            now += 100                                         // within the request budget (10 a second)
            val r = request(me); me.onFrame(r.raw); ids.add(r.id.toHex())
        }
        queue.clear()
        assertEquals(ids.toSet(), me.entries().map { it.key }.toSet())
        // A request no link can come of (65 bytes of data: not 64, not 67) takes nobody's slot.
        now += 100
        me.onFrame(request(me).raw + byteArrayOf(0))
        assertEquals("a malformed request evicts nothing", ids.toSet(), me.entries().map { it.key }.toSet())
        // Every link is younger than the grace: a new request is refused rather than push one out.
        now += 100
        val early = request(me); me.onFrame(early.raw)
        assertFalse(me.entries().any { it.key == early.id.toHex() })
        // Past the grace, the oldest unconfirmed link makes room.
        now += ReticulumNode.GRACE_MS
        val late = request(me); me.onFrame(late.raw); queue.clear()
        val held = me.entries().map { it.key }
        assertTrue(late.id.toHex() in held)
        assertFalse("the oldest unconfirmed link made room", ids[0] in held)
        assertEquals(ReticulumNode.MAX_LINKS, held.size)
        // Once every link has proved the key, none is ever the one to go.
        for (e in me.entries()) e.confirmed = true
        now += ReticulumNode.GRACE_MS
        me.onFrame(request(me).raw)
        assertEquals(held.toSet(), me.entries().map { it.key }.toSet())
    }

    @Test
    fun requestsAndAnnouncesPastTheFloodBudgetCostNoSignatureWork() {
        val me = node("7777777777777777")
        me.connected(); queue.clear()
        repeat(ReticulumNode.MAX_LINKS) { me.onFrame(request(me).raw) }           // no time passes: one burst
        queue.clear()
        assertEquals("the rest of the burst was refused before any key agreement", ReticulumNode.GATE_BURST, me.entries().size)
        repeat(2 * ReticulumNode.GATE_BURST) { me.onFrame(announce(RnsIdentity.generate(), me.nameHash, now / 1000)); queue.clear() }
        assertEquals("announces have a budget of their own", ReticulumNode.GATE_BURST, me.peerCount)
        now += 1000
        me.onFrame(announce(RnsIdentity.generate(), me.nameHash, now / 1000)); queue.clear()
        assertEquals("and it refills", ReticulumNode.GATE_BURST + 1, me.peerCount)
    }

    @Test
    fun requestsOfOursNobodyAnswersNeverLockTheCrewOut() {
        val me = node("8888888888888888")
        me.connected()
        queue.clear()
        // Strangers announcing under our name with destinations we are to dial: each costs a pending request of ours.
        while (me.pendingCount < ReticulumNode.MAX_LINKS) {
            val id = RnsIdentity.generate()
            if (compare(me.destination, RnsIdentity.destinationHash(me.nameHash, id.hash)) > 0) continue   // it would dial us
            now += 100
            me.onFrame(announce(id, me.nameHash, now / 1000))
        }
        queue.clear()                                     // nobody answers them
        assertEquals(0, me.entries().size)
        now += ReticulumNode.GRACE_MS
        val crew = request(me)
        me.onFrame(crew.raw)
        assertEquals("the oldest unanswered request made room", listOf(crew.id.toHex()), me.entries().map { it.key })
        assertEquals(ReticulumNode.MAX_LINKS - 1, me.pendingCount)
    }

    @Test
    fun aConfirmedLinkIsHeldToItsOwnRateBudget() {
        val (a, b) = linked()
        val opus = sealed(Packet.Codec.OPUS, ByteArray(60) { 1 }, 2)
        repeat(1000) { a.send(opus, null) }
        settle()
        assertEquals("a burst of 800, then nothing until it refills", 800, inbox[b]!!.size)
        now += 1000
        a.send(opus, null); settle()
        assertEquals(801, inbox[b]!!.size)
    }

    private fun compare(x: ByteArray, y: ByteArray): Int {
        for (i in x.indices) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }
}
