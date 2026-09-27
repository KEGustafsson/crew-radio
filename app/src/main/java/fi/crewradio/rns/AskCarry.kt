package fi.crewradio.rns

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Asking the boat over a Reticulum link: what "Ask boat data" asks the Signal K server over HTTP
 * on the boat's LAN, put to the Crew Radio plugin instead on the link this phone already holds to
 * it, for a phone ashore that cannot reach the boat's LAN. Beside the channel's own frames
 * ([Carry]: 0x01 whole, 2-3 parts, 0x80 key proof) a link carries two more kinds, cut the same
 * way both ways:
 *
 *     request part: 0x81 | id u16 | index u8 | count u8 | bytes
 *     answer part:  0x82 | id u16 | index u8 | count u8 | bytes
 *
 * The parts of one message, joined in index order, are:
 *
 *     request: op u8 | body       [OP_READ]: the top-level branches of vessels.self, UTF-8, one a line
 *                                 [OP_SAY]: the text to announce on the channel, UTF-8
 *     answer:  status u8 | body   [OK]: for a read, zlib-deflated UTF-8 JSON {branch: tree}, each leaf
 *                                       cut to value, timestamp and $source; for a say, empty
 *                                 [OFF]: the boat does not answer questions over Reticulum (a plugin setting)
 *                                 [BUSY]: over its rate budget, or its announcement queue is full
 *                                 [FAILED]: body a UTF-8 reason
 *
 * Only a confirmed link carries either, so a question is the crew's. A phone asks on every
 * confirmed link and takes the first [OK]; phones ignore requests, the plugin answers them. A lost
 * part loses the message, so the phone asks again with the same id, and the plugin answers a
 * repeat from its memory rather than doing it twice. Parts may arrive in any order.
 * The same format as the plugin's lib/rns/ask.js.
 */
internal object AskCarry {
    const val REQUEST = 0x81
    const val ANSWER = 0x82
    const val HEAD = 5
    val ROOM = RnsLink.MDU - HEAD
    const val MAX_REQUEST_PARTS = 4
    const val MAX_ANSWER_PARTS = 64
    /** Messages being put together on one link at a time; the oldest goes when a new one needs room. */
    const val IN_FLIGHT = 4

    const val OP_READ = 1
    const val OP_SAY = 2

    const val OK = 0
    const val OFF = 1
    const val BUSY = 2
    const val FAILED = 3

    /** The most a read's JSON may inflate to: a boat's whole `navigation` branch is a few kilobytes. */
    const val MAX_INFLATED = 1 shl 20

    /** A whole answer: its status and body. */
    class Reply(val status: Int, val body: ByteArray)

    private fun maxParts(kind: Int) = if (kind == REQUEST) MAX_REQUEST_PARTS else MAX_ANSWER_PARTS

    /** True when a link payload is a part of a request or an answer. */
    fun isAsk(payload: ByteArray): Boolean {
        if (payload.size <= HEAD) return false
        val kind = payload[0].toInt() and 0xFF
        return kind == REQUEST || kind == ANSWER
    }

    /** The id a part belongs to. */
    fun idOf(payload: ByteArray): Int = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)

    /** The link payloads for one message of [kind] with the id [id]. */
    fun cut(kind: Int, id: Int, message: ByteArray): List<ByteArray> {
        val count = maxOf(1, (message.size + ROOM - 1) / ROOM)
        require(count <= maxParts(kind)) { "message of ${message.size} bytes is too large to carry" }
        return (0 until count).map { i ->
            byteArrayOf(kind.toByte(), (id shr 8).toByte(), id.toByte(), i.toByte(), count.toByte()) +
                message.copyOfRange(i * ROOM, minOf(message.size, (i + 1) * ROOM))
        }
    }

    fun request(op: Int, body: ByteArray): ByteArray = byteArrayOf(op.toByte()) + body

    /** A whole answer message read as its status and body, or null for an empty one. */
    fun reply(message: ByteArray): Reply? =
        if (message.isEmpty()) null else Reply(message[0].toInt() and 0xFF, message.copyOfRange(1, message.size))

    /** A read's body back to its JSON text, or null when it is not zlib or inflates past [limit]. */
    fun inflate(body: ByteArray, limit: Int = MAX_INFLATED): String? {
        val inflater = Inflater()
        try {
            inflater.setInput(body)
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                // All the input is in: nothing out means it was cut short or wants a dictionary.
                if (n == 0) {
                    if (inflater.finished()) break
                    return null
                }
                out.write(chunk, 0, n)
                if (out.size() > limit) return null
            }
            return out.toString(Charsets.UTF_8.name())
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    /**
     * Puts one link's messages of one kind back together, parts in any order. [push] returns the
     * id and the message once the last part is in, null meanwhile and for anything malformed.
     * Not thread-safe: the owner holds its lock.
     */
    class Assembler(private val kind: Int) {
        private class Pending(val count: Int) {
            val parts = arrayOfNulls<ByteArray>(count)
            var have = 0
        }
        private val pending = LinkedHashMap<Int, Pending>()

        /** Unfinished messages held; for tests. */
        val held: Int get() = pending.size

        fun push(payload: ByteArray): Pair<Int, ByteArray>? {
            if (!isAsk(payload) || (payload[0].toInt() and 0xFF) != kind) return null
            val id = idOf(payload)
            val index = payload[3].toInt() and 0xFF
            val count = payload[4].toInt() and 0xFF
            if (count < 1 || count > maxParts(kind) || index >= count) return null
            val body = payload.copyOfRange(HEAD, payload.size)
            if (count == 1) {
                pending.remove(id)
                return id to body
            }
            var m = pending[id]
            if (m != null && m.count != count) { pending.remove(id); m = null }
            if (m == null) {
                if (pending.size >= IN_FLIGHT) pending.remove(pending.keys.first())
                m = Pending(count)
                pending[id] = m
            }
            if (m.parts[index] != null) return null                     // a copy of a part already in
            m.parts[index] = body
            if (++m.have < count) return null
            pending.remove(id)
            val out = ByteArrayOutputStream()
            for (p in m.parts) out.write(p!!)
            return id to out.toByteArray()
        }
    }
}
