package fi.crewradio.transport

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

/** Address arithmetic for [LanTransport], pure so it is unit-tested. */
internal object LanAddressing {

    /**
     * The IPv4 directed broadcast of [address]/[prefixLength]: 192.168.1.35/24 → 192.168.1.255.
     * Some OEM builds report no broadcast address on the interface at all; the address and the
     * prefix are always there. Null for anything but IPv4 and for prefixes of 31 and up (a
     * point-to-point net has no broadcast) or 0 (no network at all).
     */
    fun broadcastOf(address: InetAddress, prefixLength: Int): InetAddress? {
        if (address !is Inet4Address || prefixLength !in 1..30) return null
        val a = ByteBuffer.wrap(address.address).int
        val hostBits = (1 shl (32 - prefixLength)) - 1
        return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(a or hostBits).array())
    }
}
