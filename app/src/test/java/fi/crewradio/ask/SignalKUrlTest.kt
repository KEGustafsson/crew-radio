package fi.crewradio.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the crew may type as the boat's server, and what it turns into. */
class SignalKUrlTest {

    @Test
    fun aBareHostGetsSignalKsOwnSchemeAndPort() {
        assertEquals("http://arabella.local:3000", SignalKUrl.normalise("arabella.local"))
        assertEquals("http://192.168.1.9:3000", SignalKUrl.normalise("192.168.1.9"))
    }

    @Test
    fun aPortOrSchemeThatIsGivenIsKept() {
        assertEquals("http://192.168.1.9:8080", SignalKUrl.normalise("192.168.1.9:8080"))
        assertEquals("https://boat.example:443", SignalKUrl.normalise("https://boat.example"))
        assertEquals("https://boat.example:3443", SignalKUrl.normalise("https://boat.example:3443"))
    }

    @Test
    fun aLinkPastedOutOfTheAdminUiIsStillAGoodAddress() {
        assertEquals(
            "http://192.168.1.9:3000",
            SignalKUrl.normalise("http://192.168.1.9:3000/admin/#/dashboard"),
        )
        assertEquals("http://arabella.local:3000", SignalKUrl.normalise("  arabella.local/  "))
    }

    @Test
    fun anIpv6LiteralKeepsItsBrackets() {
        assertEquals("http://[fd00::1]:3000", SignalKUrl.normalise("http://[fd00::1]"))
    }

    @Test
    fun whatCannotBeAServerIsRefused() {
        assertNull(SignalKUrl.normalise(null))
        assertNull(SignalKUrl.normalise(""))
        assertNull(SignalKUrl.normalise("   "))
        assertNull(SignalKUrl.normalise("ftp://boat"))
        assertNull(SignalKUrl.normalise("http://"))
        assertNull(SignalKUrl.normalise("two words"))
        assertFalse(SignalKUrl.valid("ftp://boat"))
        assertTrue(SignalKUrl.valid("arabella.local"))
    }

    @Test
    fun theSettingsRowShowsHostAndPortWithoutTheScheme() {
        assertEquals("arabella.local:3000", SignalKUrl.describe("arabella.local"))
        assertEquals("", SignalKUrl.describe("nonsense://x"))
    }

    @Test
    fun theEndpointsAreBuiltOffTheBase() {
        val base = SignalKUrl.normalise("arabella.local")!!
        assertEquals(
            "http://arabella.local:3000/signalk/v1/api/vessels/self/navigation",
            SignalKUrl.selfBranch(base, "navigation"),
        )
        assertEquals("http://arabella.local:3000/signalk/v1/access/requests", SignalKUrl.accessRequests(base))
        assertEquals("http://arabella.local:3000/plugins/signalk-crewradio/say", SignalKUrl.pluginSay(base))
    }
}
