package fi.crewradio.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class LanAddressingTest {

    private fun bc(addr: String, prefix: Int) = LanAddressing.broadcastOf(InetAddress.getByName(addr), prefix)?.hostAddress

    @Test
    fun derivesTheDirectedBroadcastFromAddressAndPrefix() {
        assertEquals("192.168.1.255", bc("192.168.1.35", 24))
        assertEquals("10.255.255.255", bc("10.0.0.5", 8))
        assertEquals("172.16.3.255", bc("172.16.0.9", 22))
        assertEquals("192.168.43.3", bc("192.168.43.1", 30))
        assertEquals("127.255.255.255", bc("127.0.0.1", 8))
    }

    @Test
    fun aPointToPointOrEmptyPrefixHasNoBroadcast() {
        assertNull(bc("192.168.1.1", 31))
        assertNull(bc("192.168.1.1", 32))
        assertNull(bc("192.168.1.1", 0))
        assertNull(bc("192.168.1.1", -1))
    }

    @Test
    fun ipv6HasNoBroadcast() {
        assertNull(bc("fe80::1", 64))
    }
}
