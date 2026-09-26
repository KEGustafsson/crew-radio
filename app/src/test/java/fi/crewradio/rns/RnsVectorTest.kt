package fi.crewradio.rns

import fi.crewradio.TestKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * The Reticulum bytes the plugin's suite checks (sk-plugin/test/rns.vector.json, read from the
 * checkout rather than copied, so a change to the vector reaches both at once). The vector was
 * checked against the reference implementation when it was made: identity and destination
 * hashes, the announce signature, the link id, the derived key and the token all agree with it.
 */
class RnsVectorTest {
    private val json = File("../sk-plugin/test/rns.vector.json").readText()

    private fun v(section: String?, key: String): String {
        val scope = if (section == null) json else Regex("\"$section\"\\s*:\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(json)!!.groupValues[1]
        return Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(scope)!!.groupValues[1]
    }
    private fun b(section: String?, key: String) = h(v(section, key))
    private fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun time(): Long = Regex("\"time\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toLong()

    private val identity by lazy { RnsIdentity(b("identity", "encPriv"), b("identity", "sigSeed")) }

    @Test
    fun tagNameIdentityAndDestination() {
        assertEquals(v(null, "reticulumTag"), TestKeys.crypto.reticulumTag)
        assertArrayEquals(b(null, "nameHash"), RnsIdentity.nameHash(v(null, "destinationName")))
        assertArrayEquals(b("identity", "publicKey"), identity.publicKey)
        assertArrayEquals(b("identity", "hash"), identity.hash)
        assertArrayEquals(b("announce", "destination"), RnsIdentity.destinationHash(b(null, "nameHash"), identity.hash))
    }

    @Test
    fun theAnnounceByteForByteFramedAndParsed() {
        val (dest, data) = RnsIdentity.buildAnnounce(identity, b(null, "nameHash"), ByteArray(0), b("announce", "random"), time())
        val raw = RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, dest, data = data)
        assertArrayEquals(b("announce", "raw"), raw)
        assertArrayEquals(b("announce", "framed"), RnsPacket.frame(raw))
        val a = RnsIdentity.parseAnnounce(RnsPacket.decode(raw)!!)
        assertNotNull(a)
        assertEquals(time(), a!!.emitted)
    }

    @Test
    fun linkRequestProofKeyTokenAndRtt() {
        val (dest, data) = RnsIdentity.buildAnnounce(identity, b(null, "nameHash"), ByteArray(0), b("announce", "random"), time())
        val announce = RnsIdentity.parseAnnounce(RnsPacket.decode(RnsPacket.encode(RnsPacket.ANNOUNCE, RnsPacket.SINGLE, dest, data = data))!!)!!
        val req = RnsLink.request(announce, b("linkRequest", "transportId"), b("linkRequest", "encPriv"), b("linkRequest", "sigSeed"))
        assertArrayEquals(b("linkRequest", "raw"), req.raw)
        assertArrayEquals(b("linkRequest", "linkId"), req.id)
        val (responder, proof) = RnsLink.accept(identity, RnsPacket.decode(req.raw)!!, b("linkProof", "encPriv"))!!
        assertArrayEquals(b("linkProof", "raw"), proof)
        assertArrayEquals(b("linkProof", "key"), responder.keyForTest())
        val link = req.complete(RnsPacket.decode(proof)!!)!!
        assertArrayEquals(b("linkProof", "key"), link.keyForTest())
        val token = link.encrypt(b("token", "plain"), b("token", "iv"))
        assertArrayEquals(b("token", "token"), token)
        assertArrayEquals(b("token", "dataRaw"), RnsPacket.encode(RnsPacket.DATA, RnsPacket.LINK, link.id, data = token))
        assertArrayEquals(b("rtt", "packed"), RnsLink.packRtt(0.25))
    }

    @Test
    fun aPcmSizedPacketIsCutTheSameWay() {
        val parts = Regex("\"parts\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(json)!!.groupValues[1]
            .split(",").map { h(it.trim().trim('"')) }
        val cut = Carry.cut(ByteArray(686) { it.toByte() }, 7)
        assertEquals(parts.size, cut.size)
        for (i in parts.indices) assertArrayEquals(parts[i], cut[i])
    }
}
