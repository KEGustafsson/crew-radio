package fi.crewradio.transport

import fi.crewradio.Packet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class BluetoothTieBreakTest {

    /** A minimal packet of the current wire version: header fields, the rest of the header zero, one payload byte. */
    private fun packet(senderId: Int, ttl: Int, hops: Int): ByteArray {
        val p = ByteArray(Packet.HEADER + 1)
        p[0] = 'P'.code.toByte(); p[1] = 'T'.code.toByte()
        p[2] = Packet.VERSION.toByte(); p[3] = Packet.Codec.HELLO.id.toByte()
        p[4] = ttl.toByte(); p[5] = hops.toByte()
        ByteBuffer.wrap(p, 6, 8).putInt(senderId).putInt(1)
        return p
    }

    @Test
    fun theLowerIdKeepsTheLinkItDialledTheHigherTheOneItAccepted() {
        assertTrue(BluetoothTieBreak.keepsDialled(localId = 1, peerId = 2))
        assertFalse(BluetoothTieBreak.keepsDialled(localId = 2, peerId = 1))
        assertTrue(BluetoothTieBreak.keepsDialled(localId = -7, peerId = 3))   // signed, like Aware's rule
        assertFalse(BluetoothTieBreak.keepsDialled(localId = 3, peerId = -7))
    }

    @Test
    fun theHigherIdDoesNotDialWhileItsAcceptedLinkIsAlive() {
        assertFalse(BluetoothTieBreak.shouldDial(localId = 9, peerId = 4, acceptedAlive = true))
        assertTrue(BluetoothTieBreak.shouldDial(localId = 9, peerId = 4, acceptedAlive = false))   // it dropped: dial again
        assertTrue(BluetoothTieBreak.shouldDial(localId = 1, peerId = 4, acceptedAlive = true))    // the lower id always dials
        assertTrue(BluetoothTieBreak.shouldDial(localId = 9, peerId = null, acceptedAlive = true)) // id unknown yet: dial, reconcile later
    }

    @Test
    fun onlyADirectFrameNamesThePeer() {
        assertEquals(0x1234, BluetoothTieBreak.directSender(packet(0x1234, ttl = 4, hops = 4)))
        assertNull(BluetoothTieBreak.directSender(packet(0x1234, ttl = 3, hops = 4)))   // relayed once
        assertNull(BluetoothTieBreak.directSender(byteArrayOf(1, 2, 3)))
        assertNull(BluetoothTieBreak.directSender(packet(0x1234, ttl = 4, hops = 4).also { it[0] = 'X'.code.toByte() }))
    }
}
