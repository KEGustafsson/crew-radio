package fi.crewradio

import fi.crewradio.Forwarding.Origin
import fi.crewradio.Forwarding.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingTest {
    private val me = 5
    private val talker = 9

    /**
     * Whether a frame goes to the WLAN, and whether one Reticulum link with a far end that is (or
     * is not, or not yet known as) local gets it.
     */
    private fun goes(origin: Origin, forwarder: Boolean, senderRnsUp: Boolean, farEndLocal: Boolean?): Pair<Boolean, Boolean> {
        val r = Forwarding.route(origin, if (origin == Origin.OWN) me else talker, me, forwarder, senderRnsUp)
        return r.lan to (r.reticulum && Forwarding.linkGets(farEndLocal))
    }

    /** Whether a frame from [origin] goes to Bluetooth and Wi-Fi Aware. */
    private fun mesh(origin: Origin, forwarder: Boolean, senderRnsUp: Boolean) =
        Forwarding.route(origin, if (origin == Origin.OWN) me else talker, me, forwarder, senderRnsUp).mesh

    /** Our own voice goes to every local transport and every Reticulum link but one whose far end is local, forwarder or not. */
    @Test
    fun ownVoiceGoesEverywhereButToLinksWhoseFarEndIsLocal() {
        for (forwarder in listOf(false, true)) {
            assertEquals(Route(lan = true, mesh = true, reticulum = true), Forwarding.route(Origin.OWN, me, me, forwarder, false))
            assertEquals(true to true, goes(Origin.OWN, forwarder, false, farEndLocal = false))
            assertEquals("split horizon", true to false, goes(Origin.OWN, forwarder, false, farEndLocal = true))
            assertEquals("a far end not known yet gets it", true to true, goes(Origin.OWN, forwarder, false, farEndLocal = null))
        }
    }

    @Test
    fun localToReticulumOnlyFromTheForwarderAndOnlyForATalkerNotOnReticulumItself() {
        assertEquals(true to true, goes(Origin.LOCAL, forwarder = true, senderRnsUp = false, farEndLocal = false))
        assertEquals("split horizon", true to false, goes(Origin.LOCAL, forwarder = true, senderRnsUp = false, farEndLocal = true))
        assertEquals("unknown far end", true to true, goes(Origin.LOCAL, forwarder = true, senderRnsUp = false, farEndLocal = null))
        assertEquals("the talker sends its own voice there", true to false, goes(Origin.LOCAL, forwarder = true, senderRnsUp = true, farEndLocal = false))
        assertEquals("not the forwarder", true to false, goes(Origin.LOCAL, forwarder = false, senderRnsUp = false, farEndLocal = false))
        assertEquals(true to false, goes(Origin.LOCAL, forwarder = false, senderRnsUp = true, farEndLocal = false))
    }

    /** A frame heard on a local transport is relayed to the WLAN, Bluetooth and Aware whatever the election says. */
    @Test
    fun localToLocalRelaysAsAlways() {
        for (forwarder in listOf(false, true)) for (up in listOf(false, true)) {
            val r = Forwarding.route(Origin.LOCAL, talker, me, forwarder, up)
            assertTrue(r.lan)
            assertTrue(r.mesh)
        }
    }

    /** A frame heard over Reticulum goes onto the WLAN only from the forwarder, and never back to Reticulum. */
    @Test
    fun reticulumToTheWlanOnlyFromTheForwarderAndNeverBackToReticulum() {
        for (up in listOf(false, true)) for (far in listOf(null, false, true)) {
            assertEquals(true to false, goes(Origin.RETICULUM, forwarder = true, senderRnsUp = up, farEndLocal = far))
            assertEquals(false to false, goes(Origin.RETICULUM, forwarder = false, senderRnsUp = up, farEndLocal = far))
        }
    }

    /**
     * A local talker's own frame (hops == ttl) heard over Reticulum before its local copy goes on as
     * the local copy would have: from the forwarder, for a talker not on Reticulum itself, to the
     * links whose far end is not local. Otherwise the local copy, a duplicate by then, would never
     * reach the links ashore.
     */
    @Test
    fun aLocalTalkersOwnReticulumCopyThatComesFirstGoesAshore() {
        fun r(forwarder: Boolean, up: Boolean) = Forwarding.route(Origin.RETICULUM, talker, me, forwarder, up, localDirect = true)
        assertEquals(Route(lan = true, mesh = true, reticulum = true), r(forwarder = true, up = false))
        assertFalse("the talker sends its own voice there", r(forwarder = true, up = true).reticulum)
        assertFalse("not the forwarder", r(forwarder = false, up = false).reticulum)
        assertFalse("never our own frame", Forwarding.route(Origin.RETICULUM, me, me, true, false, localDirect = true).reticulum)
    }

    /** A frame heard over Reticulum (or our own) goes to Bluetooth and Aware from every node, forwarder or not. */
    @Test
    fun reticulumToBluetoothAndAwareFromEveryoneAsBefore() {
        // A peer reached only over Bluetooth or Aware hears a frame from whoever had it first: if
        // only the forwarder relayed it there, a relay that heard it over Reticulum first would hold
        // its WLAN copy for a duplicate and the peer would never get it.
        for (forwarder in listOf(false, true)) for (up in listOf(false, true)) {
            assertTrue(mesh(Origin.RETICULUM, forwarder, up))
            assertTrue(mesh(Origin.OWN, forwarder, up))
        }
    }

    /** RNS_UP needs relay on, a hop limit that relays (matching Ingress.relayTtl) and a local transport up. */
    @Test
    fun onlyANodeThatBridgesMayClaimRnsUp() {
        assertTrue(Forwarding.bridges(relay = true, maxHops = 4, localUp = true))
        assertFalse("relay switched off", Forwarding.bridges(relay = false, maxHops = 4, localUp = true))
        assertFalse("a hop limit of 1 relays nothing", Forwarding.bridges(relay = true, maxHops = 1, localUp = true))
        assertFalse("nothing local to bridge to", Forwarding.bridges(relay = true, maxHops = 4, localUp = false))
        // The hop rule is Ingress.relayTtl's: an unrelayed packet is forwarded exactly when maxHops >= 2.
        for (maxHops in 1..6) {
            val h = Packet.Header(1, 1, Packet.Codec.OPUS, 4, 4, 0)
            assertEquals("maxHops $maxHops", Ingress.relayTtl(h, maxHops) > 0, Forwarding.bridges(true, maxHops, true))
        }
    }

    @Test
    fun ourOwnIdArrivingFromALocalTransportIsNeverForwardedToReticulum() {
        assertFalse(Forwarding.route(Origin.LOCAL, me, me, forwarder = true, senderRnsUp = false).reticulum)
    }

    /** On a boat with three bridges and one LAN-only talker, exactly one node forwards the talker's frame to Reticulum. */
    @Test
    fun theElectionAndTheRulesTogetherPutAFrameThroughTheHubOnce() {
        // A boat: three bridges (two phones and the plugin) on the LAN and Reticulum, and a phone
        // on the LAN only, talking. Only the plugin (FWD_PREFER) forwards it to Reticulum.
        val plugin = 0x7000_0000
        val phoneA = 0x1000_0000
        val phoneB = 0x2000_0000
        val talking = 0x3000_0000
        val up = Hello.LAN or Hello.RETICULUM or Hello.RNS_UP
        val bits = mapOf(plugin to (up or Hello.FWD_PREFER), phoneA to up, phoneB to up, talking to Hello.LAN)
        var copies = 0
        for ((node, own) in bits) {
            if (node == talking) continue
            val view = LocalPeers()
            for ((other, b) in bits) if (other != node) view.heardHello(other, b, 4, 4, true, 0)
            val r = Forwarding.route(Origin.LOCAL, talking, node, view.isForwarder(node, own, 0), view.rnsUp(talking, 0))
            if (r.reticulum) copies++
        }
        assertEquals(1, copies)
    }
}
