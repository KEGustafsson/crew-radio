package fi.crewradio

import fi.crewradio.LocalNetwork.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    private val android16 = 36
    private val android17 = 37

    @Test
    fun enforcedFromAndroid17() {
        assertEquals("CINNAMON_BUN is API 37", 37, android.os.Build.VERSION_CODES.CINNAMON_BUN)
        assertEquals(android.Manifest.permission.ACCESS_LOCAL_NETWORK, LocalNetwork.PERMISSION)
        assertEquals(Need.NONE, LocalNetwork.forChannel(android16, wlan = true, reticulumHost = "192.168.1.9"))
        assertFalse(LocalNetwork.forServer(android16, "http://192.168.1.9:3000"))
    }

    @Test
    fun wlanAndALocalReticulumNodeCannotWorkWithoutIt() {
        assertEquals(Need.REQUIRED, LocalNetwork.forChannel(android17, wlan = true, reticulumHost = null))
        assertEquals("WLAN decides, whatever the node", Need.REQUIRED, LocalNetwork.forChannel(android17, wlan = true, reticulumHost = "hub.example.org"))
        assertEquals(Need.REQUIRED, LocalNetwork.forChannel(android17, wlan = false, reticulumHost = "192.168.1.9"))
        assertEquals(Need.REQUIRED, LocalNetwork.forChannel(android17, wlan = false, reticulumHost = "[fd12::7]"))
        assertEquals("as parseHostPort hands it over, unbracketed", Need.REQUIRED, LocalNetwork.forChannel(android17, wlan = false, reticulumHost = "fd12::7"))
    }

    @Test
    fun aHubOnTheInternetDoesNotNeedIt() {
        assertEquals(Need.NONE, LocalNetwork.forChannel(android17, wlan = false, reticulumHost = null))
        assertEquals(Need.NONE, LocalNetwork.forChannel(android17, wlan = false, reticulumHost = "8.8.8.8"))
        assertEquals("a name may be either: asked, not required", Need.OPTIONAL,
            LocalNetwork.forChannel(android17, wlan = false, reticulumHost = "hub.example.org"))
    }

    @Test
    fun askingTheBoatNeedsItForALocalServerAndForTheSearch() {
        assertTrue(LocalNetwork.forServer(android17, "http://192.168.1.9:3000"))
        assertTrue(LocalNetwork.forServer(android17, "http://boat.local:3000"))
        assertTrue("no server yet: mDNS finds one", LocalNetwork.forServer(android17, null))
        assertFalse(LocalNetwork.forServer(android17, "https://203.0.113.7"))
    }
}
