package fi.crewradio

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Payload of a [Packet.Codec.HELLO] packet: who this node is, how it is connected, and what build it runs.
 *
 *     ver u8 = 2 | transports u8 (bit flags) | ttl u8 | versionCode uint16 | nameLen u8 | name UTF-8, at most 32 bytes
 *
 * `ttl` repeats the hop budget the sender stamped on the header, so a receiver counts the
 * hops a packet travelled as `ttl - header.ttl` without assuming everyone uses the default.
 * `versionCode` is the sender's build (the app's `BuildConfig.VERSION_CODE`), so a crew can see
 * who has not updated; 0 means not applicable (the Signal K plugin sends 0). Every node sends
 * one every second and relays the others like audio; that is the whole roster protocol.
 * Version 1 (no build number) is not accepted: the wire has no legacy mode.
 */
class Hello(val name: String, val transports: Int, val ttl: Int, val versionCode: Int = 0) {

    fun encode(): ByteArray {
        val bytes = utf8Prefix(name, MAX_NAME_BYTES)
        val v = versionCode.coerceIn(0, 0xFFFF)
        val head = byteArrayOf(
            VERSION.toByte(), (transports and 0xFF).toByte(), ttl.coerceIn(0, 255).toByte(),
            (v shr 8).toByte(), v.toByte(), bytes.size.toByte()
        )
        return head + bytes
    }

    companion object {
        const val VERSION = 2
        const val MAX_NAME_BYTES = 32
        private const val HEAD = 6

        const val LAN = 1
        const val BT = 2
        const val AWARE = 4

        /**
         * Parses [length] bytes at [offset]; null for anything off the wire contract — an unknown
         * version, a name over [MAX_NAME_BYTES], trailing bytes, or invalid UTF-8. These packets come
         * from whoever is on the same network, so nothing about them is taken on trust; the name
         * goes through [sanitise] so it stays one plain line on screen.
         */
        fun decode(p: ByteArray, offset: Int, length: Int): Hello? {
            if (length < HEAD || p[offset].toInt() != VERSION) return null
            val nameLen = p[offset + 5].toInt() and 0xFF
            if (nameLen > MAX_NAME_BYTES || length != HEAD + nameLen) return null
            val name = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(p, offset + HEAD, nameLen))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            val versionCode = ((p[offset + 3].toInt() and 0xFF) shl 8) or (p[offset + 4].toInt() and 0xFF)
            return Hello(sanitise(name), p[offset + 1].toInt() and 0xFF, p[offset + 2].toInt() and 0xFF, versionCode)
        }

        /**
         * A name as it may appear on a roster: ISO control characters and Unicode format
         * characters (bidi overrides, zero-width joiners and spaces, byte order marks: anything
         * that reorders or hides what is next to it) are dropped, runs of whitespace become one
         * space, and the ends are trimmed. The Signal K plugin applies the same rule.
         */
        fun sanitise(name: String): String {
            val sb = StringBuilder(name.length)
            var space = false
            for (c in name) {
                if (c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt()) continue
                if (c.isWhitespace()) { space = true; continue }       // Unicode spaces too, as the plugin's \s
                if (space && sb.isNotEmpty()) sb.append(' ')
                space = false
                sb.append(c)
            }
            return sb.toString()
        }

        /** The flag for a transport, by the name it reports; unknown transports carry no flag. */
        fun bitFor(transportName: String): Int = when (transportName) {
            "LAN" -> LAN
            "BT" -> BT
            "Aware" -> AWARE
            else -> 0
        }

        /** Flags as text, e.g. `LAN+BT`; empty when none are set. */
        fun describe(flags: Int): String = buildList {
            if ((flags and LAN) != 0) add("LAN")
            if ((flags and BT) != 0) add("BT")
            if ((flags and AWARE) != 0) add("Aware")
        }.joinToString("+")

        /** The longest prefix of [s] whose UTF-8 form fits in [max] bytes, never cutting a code point. */
        private fun utf8Prefix(s: String, max: Int): ByteArray {
            var end = 0
            var bytes = 0
            while (end < s.length) {
                val cp = s.codePointAt(end)
                val size = when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
                if (bytes + size > max) break
                bytes += size
                end += Character.charCount(cp)
            }
            return s.substring(0, end).toByteArray(StandardCharsets.UTF_8)
        }
    }
}
