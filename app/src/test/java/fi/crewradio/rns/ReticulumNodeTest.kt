package fi.crewradio.rns

import fi.crewradio.Hello
import fi.crewradio.Packet
import fi.crewradio.TestKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two nodes on a fake shared medium: every packet one writes, the others read, as on one hub segment. */
class ReticulumNodeTest {
    private var now = 1_000_000L
    private val inbox = HashMap<ReticulumNode, MutableList<Pair<ByteArray, ReticulumNode.Entry>>>()
    private val nodes = ArrayList<ReticulumNode>()
    private val queue = ArrayDeque<Pair<ReticulumNode, ByteArray>>()

    private fun node(tag: String = "a2ddc18dee75e2bd"): ReticulumNode {
        lateinit var n: ReticulumNode
        n = ReticulumNode(tag, write = { raw -> queue.addLast(n to raw) }, clock = { now }, jitter = { 0 })
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

    private fun pair(): Pair<ReticulumNode, ReticulumNode> {
        val x = node()
        val y = node()
        return if (compare(x.destination, y.destination) < 0) x to y else y to x    // first dials
    }

    private fun sealed(codec: Packet.Codec, payload: ByteArray, seq: Int): ByteArray {
        val header = Packet.encode(7, seq, codec, 4, ByteArray(0), time = System.currentTimeMillis() / 1000)
        return header + TestKeys.crypto.seal(Packet.aadOf(header), payload)
    }

    @Test
    fun twoNodesLinkAndCarryHellosBeforeAndAudioAfterConfirmation() {
        val (a, b) = pair()
        a.connected(); b.connected()
        settle()
        now += 100; b.tick(); a.tick(); settle()
        assertEquals(1, a.linkCount)
        assertEquals(1, b.linkCount)
        val hello = sealed(Packet.Codec.HELLO, Hello("A", 8, 4, 1).encode(), 1)
        val opus = sealed(Packet.Codec.OPUS, ByteArray(60) { 1 }, 2)
        assertFalse("audio waits for the far end to prove the key", a.send(opus, null))
        assertTrue(a.send(hello, null))
        settle()
        assertEquals(1, inbox[b]!!.size)
        val via = inbox[b]!![0].second
        b.confirm(via)
        b.send(hello, null); settle()
        a.confirm(inbox[a]!![0].second)
        val pcm = sealed(Packet.Codec.PCM, ByteArray(640) { 2 }, 3)
        assertTrue(a.send(opus, null))
        assertTrue(a.send(pcm, null))
        settle()
        assertEquals(listOf(hello.size, opus.size, pcm.size), inbox[b]!!.map { it.first.size })
        assertFalse("never back where it came from", b.send(opus, via))
    }

    @Test
    fun anotherChannelIsNeverDialledAndAnOlderAnnounceChangesNothing() {
        val mine = node("1111111111111111")
        val other = node("2222222222222222")
        mine.connected(); other.connected(); settle()
        assertEquals(0, mine.peerCount)
        assertEquals(0, other.peerCount)
        val peer = RnsIdentity.generate()
        fun pk(t: Long): ByteArray {
            val (d, data) = RnsIdentity.buildAnnounce(peer, mine.nameHash, ByteArray(0), ByteArray(5), t)
            return RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, d, data = data)
        }
        mine.onFrame(pk(2000)); mine.onFrame(pk(1000))
        assertEquals(1, mine.peerCount)
        val (d, data) = RnsIdentity.buildAnnounce(mine.identity, mine.nameHash)
        mine.onFrame(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, d, data = data))
        assertEquals("our own echo is not a peer", 1, mine.peerCount)
        mine.onFrame(byteArrayOf(1, 2, 3))
    }

    @Test
    fun silentAndUnconfirmedLinksCloseTheDiallerRedialsAndUnprovenRequestsTimeOut() {
        val (a, b) = pair()
        a.connected(); b.connected(); settle()
        now += 100; b.tick(); a.tick(); settle()
        assertEquals(1, a.linkCount)
        now += ReticulumNode.CONFIRM_MS + 1
        a.tick(); settle()
        assertEquals(0, a.linkCount)
        assertEquals("the close reached the far end", 0, b.linkCount)
        now += 2000
        a.tick(); settle()
        assertEquals("redialled after the backoff", 1, a.linkCount)
        now += ReticulumNode.STALE_MS + 1
        a.tick()
        assertEquals(0, a.linkCount)
        nodes.remove(b)                      // b goes away: requests go unanswered
        now += 20_000
        a.tick(); settle()
        assertEquals(0, a.linkCount)
        now += ReticulumNode.LINK_TIMEOUT_MS + 1
        a.tick()
        a.disconnected()
        assertFalse(a.send(ByteArray(40), null))
    }

    @Test
    fun fullTablesDropWhatNeverProvedTheKeyAndNeverWhatDid() {
        val me = node("5555555555555555")
        me.connected()
        queue.clear()
        fun announce(id: RnsIdentity): ByteArray {
            val (d, data) = RnsIdentity.buildAnnounce(id, me.nameHash, ByteArray(0), ByteArray(5), now / 1000)
            return RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, d, data = data)
        }
        // Strangers announcing under our public name hash fill the peer table; the next one still gets in.
        val first = RnsIdentity.generate()
        me.onFrame(announce(first))
        repeat(ReticulumNode.MAX_PEERS - 1) { now += 10; me.onFrame(announce(RnsIdentity.generate())) }
        assertEquals(ReticulumNode.MAX_PEERS, me.peerCount)
        now += 10
        val late = RnsIdentity.generate()
        me.onFrame(announce(late))
        assertEquals(ReticulumNode.MAX_PEERS, me.peerCount)
        assertTrue(me.knows(RnsIdentity.destinationHash(me.nameHash, late.hash)))
        assertFalse("the stalest made room", me.knows(RnsIdentity.destinationHash(me.nameHash, first.hash)))

        // Link requests fill the link table (with whatever requests we sent ourselves); a new one closes the oldest unconfirmed link.
        val target = RnsIdentity.parseAnnounce(RnsPacket.decode(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, me.destination,
            data = RnsIdentity.buildAnnounce(me.identity, me.nameHash).second))!!)!!
        fun request(): String { now += 10; val r = RnsLink.request(target, null); me.onFrame(r.raw); return r.id.toHex() }
        val ids = (0 until ReticulumNode.MAX_LINKS).map { request() }
        // A request no link can come of (65 bytes of data: not 64, not 67) takes nobody's slot.
        val before = me.entries().map { it.key }
        now += 10
        me.onFrame(RnsLink.request(target, null).raw + byteArrayOf(0))
        assertEquals("a malformed request evicts nothing", before, me.entries().map { it.key })
        val oldest = me.entries().minByOrNull { it.createdAt }!!.key
        val newest = request()
        val held = me.entries().map { it.key }
        assertTrue(newest in held)
        assertFalse("the oldest unconfirmed link made room", oldest in held)
        assertTrue(held.size <= ReticulumNode.MAX_LINKS && ids.isNotEmpty())
        // Once every link has proved the key, none of them is ever the one to go: a new request
        // takes the slot of one of our own unanswered requests if there is one, else it is refused.
        for (e in me.entries()) me.confirm(e)
        val confirmed = me.entries().map { it.key }.toSet()
        repeat(ReticulumNode.MAX_LINKS + 1) { request() }
        assertTrue(me.entries().map { it.key }.containsAll(confirmed))
        assertTrue(me.entries().size <= ReticulumNode.MAX_LINKS)
    }

    @Test
    fun requestsOfOursNobodyAnswersNeverLockTheCrewOut() {
        val me = node("6666666666666666")
        me.connected()
        queue.clear()
        // Strangers announcing under our name with destinations we are to dial: each costs a pending request of ours.
        var dialled = 0
        while (dialled < ReticulumNode.MAX_LINKS) {
            val id = RnsIdentity.generate()
            if (compare(me.destination, RnsIdentity.destinationHash(me.nameHash, id.hash)) > 0) continue   // it would dial us
            now += 10
            val (d, data) = RnsIdentity.buildAnnounce(id, me.nameHash, ByteArray(0), ByteArray(5), now / 1000)
            me.onFrame(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, d, data = data))
            dialled++
        }
        queue.clear()                                     // nobody answers them
        assertEquals(0, me.entries().size)
        val target = RnsIdentity.parseAnnounce(RnsPacket.decode(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, me.destination,
            data = RnsIdentity.buildAnnounce(me.identity, me.nameHash).second))!!)!!
        val crew = RnsLink.request(target, null)
        me.onFrame(crew.raw)
        assertEquals("the oldest unanswered request made room", listOf(crew.id.toHex()), me.entries().map { it.key })
    }

    private fun compare(x: ByteArray, y: ByteArray): Int {
        for (i in x.indices) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }
}
