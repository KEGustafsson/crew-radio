package fi.crewradio.transport

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The Wi-Fi Aware service-specific info: `senderId int32 BE || tag (8 bytes)`.
 *
 * Discovery is unauthenticated, and every discovery used to cost a network request (twenty
 * seconds each, and the framework refuses past a hundred outstanding). The tag is
 * `ChannelCrypto.awareIdTag(senderId)`, an HMAC under the packet key, so a publisher that does
 * not hold the channel key is ignored before anything is requested. The same bytes are the
 * wake-up message to a pre-Android-12 publisher, checked the same way.
 *
 * Pure: the tag function is a parameter, so the encoding and the check are unit-tested.
 */
internal object AwareSsi {
    const val TAG_BYTES = 8
    const val SIZE = 4 + TAG_BYTES

    fun encode(id: Int, tag: (Int) -> ByteArray): ByteArray {
        val t = tag(id)
        require(t.size == TAG_BYTES) { "id tag must be $TAG_BYTES bytes, got ${t.size}" }
        return ByteBuffer.allocate(SIZE).putInt(id).put(t).array()
    }

    /** The node id [ssi] carries, or null when it is not one of ours: wrong size, or a tag that does not verify. */
    fun decode(ssi: ByteArray?, tag: (Int) -> ByteArray): Int? {
        if (ssi == null || ssi.size != SIZE) return null
        val id = ByteBuffer.wrap(ssi).int
        val expected = try { tag(id) } catch (_: Exception) { return null }
        return if (MessageDigest.isEqual(expected, ssi.copyOfRange(4, SIZE))) id else null
    }
}
