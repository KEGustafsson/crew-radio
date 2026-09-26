package fi.crewradio.rns

import java.io.ByteArrayOutputStream

/**
 * Reticulum packets and the HDLC framing of its TCP interfaces, from the manual's "Wire Format":
 *
 *     flags u8 | hops u8 | [transport id (16) when header type 2] | destination (16) | context u8 | data
 *
 *     flags = ifac(1 bit) | header type(1) | context flag(1) | propagation(1) | destination type(2) | packet type(2)
 *
 * Interface access codes are not supported; a packet with the IFAC bit set is not parsed. On TCP
 * each packet is a frame: 0x7E, the packet with 0x7D and 0x7E escaped as 0x7D and the byte XOR
 * 0x20, 0x7E. The plugin's lib/rns/packet.js is the same.
 */
internal class RnsPacket(
    val headerType: Int,
    val contextFlag: Boolean,
    val packetType: Int,
    val destType: Int,
    val hops: Int,
    val transportId: ByteArray?,
    val destination: ByteArray,
    val context: Int,
    val data: ByteArray,
    val raw: ByteArray
) {
    companion object {
        const val MTU = 500
        const val HASH_BYTES = 16
        const val HEADER_MIN = 2 + HASH_BYTES + 1

        const val HEADER_1 = 0
        const val HEADER_2 = 1

        const val SINGLE = 0
        const val GROUP = 1
        const val PLAIN = 2
        const val LINK = 3

        const val DATA = 0
        const val ANNOUNCE = 1
        const val LINKREQUEST = 2
        const val PROOF = 3

        const val CTX_NONE = 0x00
        const val CTX_KEEPALIVE = 0xFA
        const val CTX_LINKCLOSE = 0xFC
        const val CTX_LRRTT = 0xFE
        const val CTX_LRPROOF = 0xFF

        /** Builds a packet; a [transportId] makes it header type 2 with transport propagation, addressed through that next hop. */
        fun encode(
            packetType: Int, destType: Int, destination: ByteArray, context: Int = CTX_NONE, data: ByteArray = ByteArray(0),
            transportId: ByteArray? = null, contextFlag: Boolean = false, hops: Int = 0
        ): ByteArray {
            val two = transportId != null
            val flags = ((if (two) 1 else 0) shl 6) or ((if (contextFlag) 1 else 0) shl 5) or ((if (two) 1 else 0) shl 4) or
                ((destType and 3) shl 2) or (packetType and 3)
            val out = ByteArrayOutputStream(2 + 2 * HASH_BYTES + 1 + data.size)
            out.write(flags)
            out.write(hops and 0xFF)
            if (transportId != null) out.write(transportId)
            out.write(destination)
            out.write(context and 0xFF)
            out.write(data)
            return out.toByteArray()
        }

        /** Parses a packet; null for anything too short, too long or carrying an interface access code. */
        fun decode(raw: ByteArray): RnsPacket? {
            if (raw.size < HEADER_MIN || raw.size > MTU) return null
            val flags = raw[0].toInt() and 0xFF
            if (flags and 0x80 != 0) return null
            val headerType = (flags shr 6) and 1
            val off = if (headerType == HEADER_2) 2 + HASH_BYTES else 2
            if (raw.size < off + HASH_BYTES + 1) return null
            return RnsPacket(
                headerType = headerType,
                contextFlag = (flags shr 5) and 1 == 1,
                packetType = flags and 3,
                destType = (flags shr 2) and 3,
                hops = raw[1].toInt() and 0xFF,
                transportId = if (headerType == HEADER_2) raw.copyOfRange(2, 2 + HASH_BYTES) else null,
                destination = raw.copyOfRange(off, off + HASH_BYTES),
                context = raw[off + HASH_BYTES].toInt() and 0xFF,
                data = raw.copyOfRange(off + HASH_BYTES + 1, raw.size),
                raw = raw
            )
        }

        /** The low nibble of the flags and everything after the hops and any transport id: the same however the packet travelled. */
        fun hashablePart(raw: ByteArray): ByteArray {
            val two = (raw[0].toInt() shr 6) and 1 == 1
            return byteArrayOf((raw[0].toInt() and 0x0F).toByte()) + raw.copyOfRange(if (two) 2 + HASH_BYTES else 2, raw.size)
        }

        private const val FLAG = 0x7E
        private const val ESC = 0x7D
        private const val ESC_MASK = 0x20

        fun frame(packet: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(packet.size + 8)
            out.write(FLAG)
            for (b in packet) {
                val v = b.toInt() and 0xFF
                if (v == FLAG || v == ESC) { out.write(ESC); out.write(v xor ESC_MASK) } else out.write(v)
            }
            out.write(FLAG)
            return out.toByteArray()
        }
    }

    /**
     * Collects frames from a byte stream. Bytes before the first flag are dropped; a frame longer
     * than [max] is abandoned up to the next flag, so a peer that never sends one cannot grow it.
     */
    class Deframer(private val max: Int = MTU + 64, private val onFrame: (ByteArray) -> Unit) {
        private val buf = ByteArrayOutputStream()
        private var inFrame = false
        private var escape = false
        private var overflow = false

        fun push(chunk: ByteArray, len: Int = chunk.size) {
            for (k in 0 until len) {
                val b = chunk[k].toInt() and 0xFF
                when {
                    b == FLAG -> {
                        if (inFrame && buf.size() > 0 && !overflow) onFrame(buf.toByteArray())
                        buf.reset(); inFrame = true; escape = false; overflow = false
                    }
                    !inFrame || overflow -> {}
                    escape -> { buf.write(b xor ESC_MASK); escape = false }
                    b == ESC -> escape = true
                    else -> buf.write(b)
                }
                if (buf.size() > max) { buf.reset(); overflow = true }
            }
        }
    }
}
