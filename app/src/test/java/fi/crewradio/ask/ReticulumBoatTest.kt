package fi.crewradio.ask

import fi.crewradio.rns.AskCarry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Deflater

class ReticulumBoatTest {
    private val asked = ArrayList<ByteArray>()

    private fun deflate(text: String): ByteArray {
        val d = Deflater()
        d.setInput(text.toByteArray())
        d.finish()
        val out = ByteArray(4096)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    /** A boat that gives [reply] to every question; the JSON is "parsed" by a lookup, since org.json is the platform's. */
    private fun boat(reply: AskCarry.Reply?, parsed: Map<String, Map<String, Any?>> = emptyMap()) = ReticulumBoat(
        ask = { asked.add(it); reply },
        parse = { parsed[it] ?: throw IllegalArgumentException("not JSON") },
    )

    private fun ok(body: ByteArray = ByteArray(0)) = AskCarry.Reply(AskCarry.OK, body)

    @Test
    fun aReadAsksForTheBranchesOneALineAndTheInflatedTreeIsTheAnswer() {
        val json = """{"navigation":{"speedOverGround":{"value":3.1,"timestamp":"2026-09-27T10:00:00.000Z"}}}"""
        val tree = mapOf("navigation" to mapOf("speedOverGround" to mapOf("value" to 3.1, "timestamp" to "2026-09-27T10:00:00.000Z")))
        val r = boat(ok(deflate(json)), mapOf(json to tree)).read(listOf("navigation", "environment"))
        assertEquals(AskCarry.OP_READ, asked.single()[0].toInt())
        assertEquals("navigation\nenvironment", String(asked.single().copyOfRange(1, asked.single().size)))
        val leaf = (r as SignalKClient.Result.Ok).value.read("navigation.speedOverGround")!!
        assertEquals(3.1, leaf.value)
        assertEquals(1_790_503_200_000L, leaf.timestampMs)
    }

    @Test
    fun nothingToReadAsksNothing() {
        assertTrue(boat(null).read(emptyList()) is SignalKClient.Result.Ok)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun everyWayOfNotAnsweringHasItsOwnFailure() {
        fun failure(r: SignalKClient.Result<*>) = (r as SignalKClient.Result.Failed).failure
        assertEquals(SignalKClient.Failure.NO_ANSWER, failure(boat(null).read(listOf("navigation"))))
        assertEquals(SignalKClient.Failure.NOT_OFFERED, failure(boat(AskCarry.Reply(AskCarry.OFF, ByteArray(0))).read(listOf("navigation"))))
        assertEquals(SignalKClient.Failure.BUSY, failure(boat(AskCarry.Reply(AskCarry.BUSY, ByteArray(0))).say("x")))
        val failed = boat(AskCarry.Reply(AskCarry.FAILED, "no model".toByteArray())).read(listOf("navigation")) as SignalKClient.Result.Failed
        assertEquals(SignalKClient.Failure.BOAT_FAILED, failed.failure)
        assertEquals("no model", failed.detail)
        assertEquals("an unknown status is a failure too", SignalKClient.Failure.BOAT_FAILED, failure(boat(AskCarry.Reply(9, ByteArray(0))).say("x")))
        assertEquals("not zlib", SignalKClient.Failure.BAD_RESPONSE, failure(boat(ok(byteArrayOf(1, 2, 3))).read(listOf("navigation"))))
        assertEquals("not JSON", SignalKClient.Failure.BAD_RESPONSE, failure(boat(ok(deflate("[1,2"))).read(listOf("navigation"))))
    }

    @Test
    fun aSayGoesAsTheTextCutToThePluginsOwnCap() {
        assertTrue(boat(ok()).say("Anna asked speed. Speed 6 knots.") is SignalKClient.Result.Ok)
        assertEquals(AskCarry.OP_SAY, asked[0][0].toInt())
        assertEquals("Anna asked speed. Speed 6 knots.", String(asked[0].copyOfRange(1, asked[0].size)))
        boat(ok()).say("é".repeat(2000))
        assertEquals(ReticulumBoat.MAX_SAY_CHARS, String(asked[1].copyOfRange(1, asked[1].size), Charsets.UTF_8).length)
    }
}
