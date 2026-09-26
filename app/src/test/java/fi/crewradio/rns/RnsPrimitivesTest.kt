package fi.crewradio.rns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RnsPrimitivesTest {
    private fun seq(start: Int, n: Int) = ByteArray(n) { (start + it).toByte() }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm = RnsCrypto.hkdf(42, ByteArray(22) { 0x0b }, seq(0, 13), seq(0xf0, 10))
        assertArrayEquals(h("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun aTokenOpensOnlyWithItsKeyAndUntouched() {
        val key = seq(1, 64)
        val t = RnsCrypto.tokenEncrypt(key, "hello".toByteArray())
        assertEquals("hello", String(RnsCrypto.tokenDecrypt(key, t)!!))
        for (i in listOf(0, 20, t.size - 1)) assertNull("byte $i", RnsCrypto.tokenDecrypt(key, t.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        assertNull(RnsCrypto.tokenDecrypt(seq(2, 64), t))
        assertNull(RnsCrypto.tokenDecrypt(key, t.copyOf(40)))
    }

    @Test
    fun packetsRoundTripAndTheHashablePartIgnoresTransport() {
        val dest = seq(0x40, 16)
        val one = RnsPacket.encode(RnsPacket.LINKREQUEST, RnsPacket.SINGLE, dest, data = seq(0, 64))
        val two = RnsPacket.encode(RnsPacket.LINKREQUEST, RnsPacket.SINGLE, dest, data = seq(0, 64), transportId = seq(0x80, 16), hops = 3)
        val a = RnsPacket.decode(one)!!
        val b = RnsPacket.decode(two)!!
        assertEquals(RnsPacket.HEADER_1, a.headerType)
        assertEquals(RnsPacket.HEADER_2, b.headerType)
        assertArrayEquals(seq(0x80, 16), b.transportId)
        assertEquals(3, b.hops)
        assertArrayEquals(a.destination, b.destination)
        assertArrayEquals(RnsPacket.hashablePart(one), RnsPacket.hashablePart(two))
        assertNull(RnsPacket.decode(ByteArray(5)))
        assertNull("interface access codes are not ours to check", RnsPacket.decode(one.copyOf().also { it[0] = (it[0].toInt() or 0x80).toByte() }))
        assertNull(RnsPacket.decode(ByteArray(RnsPacket.MTU + 1)))
        assertNull(RnsPacket.decode(two.copyOf(20)))
    }

    @Test
    fun hdlcEscapesAndDropsJunkAndOversizedFrames() {
        val got = ArrayList<ByteArray>()
        val d = RnsPacket.Deframer(32) { got.add(it) }
        val packet = byteArrayOf(1, 0x7e, 2, 0x7d, 3)
        val framed = RnsPacket.frame(packet)
        assertArrayEquals(byteArrayOf(0x7e, 1, 0x7d, 0x5e, 2, 0x7d, 0x5d, 3, 0x7e), framed)
        d.push(byteArrayOf(9, 9, 9))
        d.push(framed.copyOf(4))
        d.push(framed.copyOfRange(4, framed.size))
        d.push(RnsPacket.frame(ByteArray(100) { 1 }))
        d.push(RnsPacket.frame(byteArrayOf(5)))
        assertEquals(2, got.size)
        assertArrayEquals(packet, got[0])
        assertArrayEquals(byteArrayOf(5), got[1])
    }

    @Test
    fun announcesTamperedForeignOrWithARatchet() {
        val id = RnsIdentity.generate()
        val nh = RnsIdentity.nameHash("crewradio.channel.test")
        val (dest, data) = RnsIdentity.buildAnnounce(id, nh, "app".toByteArray())
        val raw = RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, dest, data = data)
        assertEquals("app", String(RnsIdentity.parseAnnounce(RnsPacket.decode(raw)!!)!!.appData))
        assertNull(RnsIdentity.parseAnnounce(RnsPacket.decode(raw.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })!!))
        assertNull(RnsIdentity.parseAnnounce(RnsPacket.decode(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, seq(0, 16), data = data))!!))
        val ratchet = seq(0x33, 32)
        val randomHash = data.copyOfRange(74, 84)
        val sig = id.sign(dest + id.publicKey + nh + randomHash + ratchet)
        val withRatchet = RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, dest, data = id.publicKey + nh + randomHash + ratchet + sig, contextFlag = true)
        assertNotNull(RnsIdentity.parseAnnounce(RnsPacket.decode(withRatchet)!!))
    }

    @Test
    fun linksRefuseAnImpostorAndCarryDataKeepaliveAndClose() {
        val dest = RnsIdentity.generate()
        val impostor = RnsIdentity.generate()
        val nh = RnsIdentity.nameHash("crewradio.channel.test")
        val (d, data) = RnsIdentity.buildAnnounce(dest, nh)
        val announce = RnsIdentity.parseAnnounce(RnsPacket.decode(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, d, data = data))!!)!!
        val req = RnsLink.request(announce, null)
        val request = RnsPacket.decode(req.raw)!!
        assertNull(req.complete(RnsPacket.decode(RnsLink.accept(impostor, request)!!.second)!!))
        val (theirs, proof) = RnsLink.accept(dest, request)!!
        val mine = req.complete(RnsPacket.decode(proof)!!)!!
        assertTrue(!theirs.active)
        assertTrue(theirs.handle(RnsPacket.decode(mine.rttPacket(0.1))!!) === RnsLink.Event.Rtt)
        assertTrue(theirs.active)
        assertEquals("over", String((theirs.handle(RnsPacket.decode(mine.dataPacket("over".toByteArray()))!!) as RnsLink.Event.Data).plain))
        val ka = theirs.handle(RnsPacket.decode(mine.keepalivePacket())!!) as RnsLink.Event.Keepalive
        assertNull((mine.handle(RnsPacket.decode(ka.reply!!)!!) as RnsLink.Event.Keepalive).reply)
        try { mine.dataPacket(ByteArray(RnsLink.MDU + 1)); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(theirs.handle(RnsPacket.decode(mine.closePacket())!!) === RnsLink.Event.Close)
        assertNull(theirs.handle(RnsPacket.decode(mine.dataPacket("late".toByteArray()))!!))
        assertEquals(431, RnsLink.MDU)
        // A request with MTU signalling bytes has the link id of its keys alone.
        val withMtu = RnsPacket.encode(RnsPacket.LINKREQUEST, RnsPacket.SINGLE, request.destination, data = request.data + byteArrayOf(0x21, 0x01, 0xf4.toByte()))
        assertArrayEquals(req.id, RnsLink.accept(dest, RnsPacket.decode(withMtu)!!)!!.first.id)
        // A low-order public key yields no link.
        val lowOrder = RnsPacket.encode(RnsPacket.LINKREQUEST, RnsPacket.SINGLE, request.destination, data = ByteArray(64))
        assertNull(RnsLink.accept(dest, RnsPacket.decode(lowOrder)!!))
    }

    @Test
    fun carryWholeTwoAndThreePartsAndLossDropsOnlyThatPacket() {
        val j = Carry.Joiner()
        val small = seq(0, 100)
        assertArrayEquals(small, j.push(Carry.cut(small, 0)[0]))
        val pcm = seq(0, 686)
        val two = Carry.cut(pcm, 1)
        assertEquals(2, two.size)
        assertTrue(two.all { it.size <= RnsLink.MDU })
        assertNull(j.push(two[0]))
        assertArrayEquals(pcm, j.push(two[1]))
        val big = seq(0, 1024)
        val three = Carry.cut(big, 2)
        assertEquals(3, three.size)
        assertNull(j.push(three[0])); assertNull(j.push(three[1]))
        assertArrayEquals(big, j.push(three[2]))
        assertNull("a second half without its first", j.push(two[1]))
        assertNull(j.push(two[0]))
        assertNull("another packet's half", j.push(Carry.cut(pcm, 3)[1]))
        assertArrayEquals(small, j.push(Carry.cut(small, 4)[0]))
        assertNull(j.push(byteArrayOf(9, 0, 0, 1)))
    }

    private fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
