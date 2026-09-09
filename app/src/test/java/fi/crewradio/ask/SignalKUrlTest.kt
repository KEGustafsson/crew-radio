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
        assertEquals("http://northstar.local:3000", SignalKUrl.normalise("northstar.local"))
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
        assertEquals("http://northstar.local:3000", SignalKUrl.normalise("  northstar.local/  "))
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
        assertTrue(SignalKUrl.valid("northstar.local"))
    }

    @Test
    fun theSettingsRowShowsHostAndPortWithoutTheScheme() {
        assertEquals("northstar.local:3000", SignalKUrl.describe("northstar.local"))
        assertEquals("", SignalKUrl.describe("nonsense://x"))
    }

    @Test
    fun theEndpointsAreBuiltOffTheBase() {
        val base = SignalKUrl.normalise("northstar.local")!!
        assertEquals(
            "http://northstar.local:3000/signalk/v1/api/vessels/self/navigation",
            SignalKUrl.selfBranch(base, "navigation"),
        )
        assertEquals("http://northstar.local:3000/signalk/v1/access/requests", SignalKUrl.accessRequests(base))
        assertEquals("http://northstar.local:3000/plugins/signalk-crewradio/say", SignalKUrl.pluginSay(base))
    }

    /**
     * The access-request flow polls an href that comes straight out of the server's own JSON, and
     * it used to be concatenated onto the base. Since the base carries no trailing slash, an href
     * beginning with "@" turned the address we dialled into userinfo and the attacker's name into
     * the host - and the phone then polled it every two seconds for three minutes and took a
     * bearer token from whatever answered.
     */
    @Test
    fun resolveRefusesAnHrefThatWouldLeaveTheServer() {
        val base = "http://192.168.1.9:3000"
        assertNull(SignalKUrl.resolve(base, "@attacker.example/x"))
        assertNull(SignalKUrl.resolve(base, "//attacker.example/x"))
        assertNull(SignalKUrl.resolve(base, "http://attacker.example/x"))
        assertNull(SignalKUrl.resolve(base, "https://192.168.1.9:3000/x"))     // scheme changed
        assertNull(SignalKUrl.resolve(base, "/x" + 0x5C.toChar() + "@attacker.example"))   // 0x5C: a backslash
        assertNull(SignalKUrl.resolve(base, "/x y"))
        assertNull(SignalKUrl.resolve(base, "signalk/v1/access/requests/1"))    // not absolute
        assertNull(SignalKUrl.resolve(base, ""))
    }

    @Test
    fun resolveKeepsAnOrdinaryPathOnTheServerWeDialled() {
        val base = "http://192.168.1.9:3000"
        assertEquals("http://192.168.1.9:3000/signalk/v1/access/requests/abc", SignalKUrl.resolve(base, "/signalk/v1/access/requests/abc"))
        assertEquals("http://192.168.1.9:3000/a?b=c", SignalKUrl.resolve(base, "/a?b=c"))
        assertEquals("http://192.168.1.9:3000/y", SignalKUrl.resolve(base, "/x/../y"))   // normalised, still ours
    }
}
