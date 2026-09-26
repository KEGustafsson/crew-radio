package fi.crewradio.transport

import fi.crewradio.transport.NetworkChoice.Candidate
import fi.crewradio.transport.NetworkChoice.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChoiceTest {
    private val boatWifi = Candidate("boat-wifi", local = true, validated = false, isDefault = true)
    private val mobile = Candidate("mobile", local = false, validated = true, isDefault = false)
    private val marinaWifi = Candidate("marina-wifi", local = true, validated = true, isDefault = false)

    @Test
    fun anInternetHubGoesOverANetworkThatReachesTheInternet() {
        assertEquals("the boat Wi-Fi has no internet: mobile data", "mobile", NetworkChoice.pick(listOf(boatWifi, mobile), Target.PUBLIC))
        assertEquals("mobile", NetworkChoice.pick(listOf(boatWifi, mobile), Target.NAME))
        assertEquals("Wi-Fi with internet before mobile data", "marina-wifi", NetworkChoice.pick(listOf(mobile, marinaWifi), Target.PUBLIC))
        val defaultMobile = Candidate("mobile", local = false, validated = true, isDefault = true)
        assertEquals("the default when it reaches the internet", "mobile", NetworkChoice.pick(listOf(marinaWifi, defaultMobile), Target.PUBLIC))
        assertEquals("nothing validated: the default, and hope", "boat-wifi", NetworkChoice.pick(listOf(boatWifi), Target.PUBLIC))
        assertNull(NetworkChoice.pick(emptyList<Candidate<String>>(), Target.PUBLIC))
    }

    @Test
    fun aNodeAtAPrivateAddressGoesOverTheLocalNetwork() {
        assertEquals("boat-wifi", NetworkChoice.pick(listOf(mobile, boatWifi), Target.PRIVATE))
        assertNull("no local network: no way to a private address", NetworkChoice.pick(listOf(mobile), Target.PRIVATE))
    }

    @Test
    fun onlyAPrivateNodeGoesOverTheRoutingTableWhenNoNetworkFits() {
        assertTrue("behind this phone's own hotspot", NetworkChoice.unboundWhenNone(Target.PRIVATE))
        assertFalse(NetworkChoice.unboundWhenNone(Target.PUBLIC))
        assertFalse(NetworkChoice.unboundWhenNone(Target.NAME))
    }

    @Test
    fun aNameMobileDataCannotResolveIsAskedOfTheLocalNetwork() {
        assertEquals("marina-wifi", NetworkChoice.localRetry(listOf(mobile, marinaWifi), "mobile"))
        assertNull("the local network is the one that failed", NetworkChoice.localRetry(listOf(mobile, marinaWifi), "marina-wifi"))
        assertNull("no local network to ask", NetworkChoice.localRetry(listOf(mobile), "mobile"))
    }

    @Test
    fun hostsAreClassedWithoutDns() {
        for (h in listOf("192.168.1.9", "10.0.0.2", "172.16.4.1", "172.31.255.254", "127.0.0.1", "169.254.1.1", "[fe80::1]", "fd12::7", "::1"))
            assertEquals(h, Target.PRIVATE, NetworkChoice.target(h))
        for (h in listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "2001:db8::7", "[2a01:4f8::1]"))
            assertEquals(h, Target.PUBLIC, NetworkChoice.target(h))
        for (h in listOf("hub.example.org", "boat.local", "1.2.3", "999.1.1.1"))
            assertEquals(h, Target.NAME, NetworkChoice.target(h))
    }
}
