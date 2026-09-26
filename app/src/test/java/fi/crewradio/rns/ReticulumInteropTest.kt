package fi.crewradio.rns

import fi.crewradio.Hello
import fi.crewradio.Packet
import fi.crewradio.TestKeys
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Against a real Reticulum transport node, and whatever else of the crew is on it: skipped unless
 * RNS_HUB names one (`RNS_HUB=127.0.0.1:4242 ./gradlew testDebugUnitTest --tests '*ReticulumInterop*'`).
 * Start rnsd with a TCPServerInterface and enable_transport = Yes, and the plugin (or another
 * phone) on the same channel key, "north-star-2026"; this node then has to link to it and hear
 * its hello within 30 s. It is how this Kotlin implementation was checked against the plugin's
 * over rnsd 1.5.4.
 */
class ReticulumInteropTest {
    @Test
    fun linksToTheCrewOverARealTransportNode() {
        val hub = System.getenv("RNS_HUB")
        assumeTrue("RNS_HUB not set", !hub.isNullOrBlank())
        val (host, port) = fi.crewradio.SettingsRules.parseHostPort(hub!!)!!
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = 200
        val out = socket.getOutputStream()
        val node = ReticulumNode(TestKeys.crypto.reticulumTag, write = { raw -> synchronized(out) { out.write(RnsPacket.frame(raw)); out.flush() } })
        var heard: String? = null
        val deframer = RnsPacket.Deframer { raw ->
            for ((packet, via) in node.onFrame(raw)) {
                val h = Packet.parse(packet) ?: continue
                val plain = TestKeys.crypto.open(Packet.aadOf(packet), packet, Packet.HEADER, packet.size - Packet.HEADER) ?: continue
                node.confirm(via)
                if (h.codec == Packet.Codec.HELLO) heard = Hello.decode(plain, 0, plain.size)?.name
            }
        }
        node.connected()
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 30_000
        var seq = 0
        var lastHello = 0L
        var heardAt = 0L
        // Past the first hello heard, a few seconds more, so the far end hears ours as well.
        while ((heard == null || System.currentTimeMillis() - heardAt < 3000) && System.currentTimeMillis() < deadline) {
            if (heard != null && heardAt == 0L) heardAt = System.currentTimeMillis()
            var n: Int
            try { n = socket.getInputStream().read(buf) } catch (_: SocketTimeoutException) { n = 0 }
            if (n < 0) break
            if (n > 0) deframer.push(buf, n)
            val now = System.currentTimeMillis()
            if (now - lastHello >= 1000) {
                lastHello = now
                node.tick()
                val header = Packet.encode(0x4b4f544c, seq++, Packet.Codec.HELLO, 4, ByteArray(0), time = now / 1000)
                node.send(header + TestKeys.crypto.seal(Packet.aadOf(header), Hello("Kotlin", Hello.RETICULUM, 4, 1).encode()), null)
            }
        }
        node.closeAll()
        Thread.sleep(200)
        socket.close()
        assertTrue("heard nobody's hello over Reticulum", heard != null)
        println("ReticulumInteropTest: linked and heard \"$heard\"")
    }
}
