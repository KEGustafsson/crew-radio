package fi.crewradio.ask

import fi.crewradio.rns.AskCarry
import org.json.JSONObject

/**
 * The boat asked over Reticulum ([AskCarry]): the Crew Radio plugin on the boat's Signal K server
 * reads the same branches [SignalKClient] would GET, from the server's own tree, and says a Whole
 * crew answer through its own announcement queue. For a phone ashore, which has the channel over a
 * Reticulum hub but no way into the boat's LAN.
 *
 * [ask] sends one request message and blocks for the answer (the transport's
 * [fi.crewradio.transport.ReticulumTransport.ask]); [parse] turns the JSON into the plain maps
 * [SignalKTree] walks, and is the platform parser in the app, so that everything here but that
 * one line runs in a unit test.
 */
internal class ReticulumBoat(
    private val ask: (ByteArray) -> AskCarry.Reply?,
    private val parse: (String) -> Map<String, Any?> = { SignalKClient.toMap(JSONObject(it)) },
) : BoatSource {

    override fun read(branches: List<String>): SignalKClient.Result<SignalKTree> {
        if (branches.isEmpty()) return SignalKClient.Result.Ok(SignalKTree.EMPTY)
        val body = branches.joinToString("\n").toByteArray(Charsets.UTF_8)
        val reply = ask(AskCarry.request(AskCarry.OP_READ, body)) ?: return noAnswer()
        if (reply.status != AskCarry.OK) return refused(reply)
        val json = AskCarry.inflate(reply.body) ?: return SignalKClient.Result.Failed(SignalKClient.Failure.BAD_RESPONSE, null)
        return try {
            SignalKClient.Result.Ok(SignalKTree(parse(json)))
        } catch (e: Exception) {
            // Not JSON after all (org.json's JSONException, or anything else the parser throws).
            SignalKClient.Result.Failed(SignalKClient.Failure.BAD_RESPONSE, e.message)
        }
    }

    override fun say(text: String): SignalKClient.Result<Unit> {
        // The plugin says no more than its own cap, and a message longer than four parts is not carried.
        val reply = ask(AskCarry.request(AskCarry.OP_SAY, text.take(MAX_SAY_CHARS).toByteArray(Charsets.UTF_8))) ?: return noAnswer()
        return if (reply.status == AskCarry.OK) SignalKClient.Result.Ok(Unit) else refused(reply)
    }

    private fun noAnswer() = SignalKClient.Result.Failed(SignalKClient.Failure.NO_ANSWER, null)

    private fun refused(reply: AskCarry.Reply) = SignalKClient.Result.Failed(
        when (reply.status) {
            AskCarry.OFF -> SignalKClient.Failure.NOT_OFFERED
            AskCarry.BUSY -> SignalKClient.Failure.BUSY
            else -> SignalKClient.Failure.BOAT_FAILED
        },
        reply.body.toString(Charsets.UTF_8).takeIf { reply.status == AskCarry.FAILED && it.isNotBlank() },
    )

    companion object {
        /** The plugin's MAX_TEXT (lib/tts.js): what it would cut a longer text to anyway. */
        const val MAX_SAY_CHARS = 500
    }
}
