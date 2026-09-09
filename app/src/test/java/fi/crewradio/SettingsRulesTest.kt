package fi.crewradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRulesTest {

    @Test
    fun nameFitsAHello() {
        assertTrue(SettingsRules.validName(""))                      // empty = device name
        assertTrue(SettingsRules.validName("  Skipper S25  "))
        assertTrue(SettingsRules.validName("ä".repeat(16)))          // 32 bytes exactly
        assertFalse(SettingsRules.validName("ä".repeat(17)))         // 34 bytes
        assertFalse(SettingsRules.validName("two\nlines"))
    }

    @Test
    fun groupMustBeIpv4Multicast() {
        assertTrue(SettingsRules.validGroup("239.255.42.1"))
        assertTrue(SettingsRules.validGroup(" 224.0.0.1 "))
        assertFalse(SettingsRules.validGroup("192.168.0.1"))          // unicast
        assertFalse(SettingsRules.validGroup("240.0.0.1"))            // reserved, past the multicast block
        assertFalse(SettingsRules.validGroup("239.255.42"))
        assertFalse(SettingsRules.validGroup("239.255.42.256"))
        assertFalse(SettingsRules.validGroup("ff02::1"))              // IPv6 is not what LanTransport joins
    }

    @Test
    fun portIsUnprivileged() {
        assertTrue(SettingsRules.validPort("1024"))
        assertTrue(SettingsRules.validPort("65535"))
        assertFalse(SettingsRules.validPort("1023"))
        assertFalse(SettingsRules.validPort("65536"))
        assertFalse(SettingsRules.validPort("port"))
    }

    @Test
    fun generatedChannelKeysAreValidReadableAndDistinct() {
        val r = java.util.Random(42)
        val keys = (1..50).map { SettingsRules.generateChannelKey(r) }
        for (k in keys) {
            assertEquals(14, k.length)                                // xxxx-xxxx-xxxx
            assertTrue(k, SettingsRules.validPassphrase(k))
            assertTrue(k, SettingsRules.validChannelKey(k))           // a fresh key passes the stricter rule
            assertTrue(k, Regex("[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}").matches(k))
            assertFalse(k, k.any { it in "01oOlI" })
        }
        assertEquals(50, keys.toSet().size)
    }

    @Test
    fun storedKeysStayAcceptedFromEight() {
        assertTrue(SettingsRules.validPassphrase("12345678"))         // an old crew's short key still works
        assertTrue(SettingsRules.validPassphrase("x".repeat(64)))
        assertFalse(SettingsRules.validPassphrase("1234567"))         // too short
        assertFalse(SettingsRules.validPassphrase("x".repeat(65)))    // too long
        assertFalse(SettingsRules.validPassphrase(""))
        assertFalse(SettingsRules.validPassphrase("salasana ääkkösillä"))   // not printable ASCII
        assertFalse(SettingsRules.validPassphrase("tab\there1234"))         // control character
    }

    @Test
    fun newKeysTypedInSettingsNeedTwelve() {
        assertFalse(SettingsRules.validChannelKey("12345678"))        // fine in storage, not to type in
        assertFalse(SettingsRules.validChannelKey("elevenchars"))     // 11
        assertTrue(SettingsRules.validChannelKey("twelvechars!"))     // 12
        assertTrue(SettingsRules.validChannelKey("x".repeat(64)))
        assertFalse(SettingsRules.validChannelKey("x".repeat(65)))
        assertFalse(SettingsRules.validChannelKey("pohjantähti-2026"))  // not printable ASCII
    }

    @Test
    fun keyStateSaysWhenAKeyIsTooShortToKeep() {
        assertEquals(SettingsRules.KeyState.MISSING, SettingsRules.channelKeyState(""))
        assertEquals(SettingsRules.KeyState.MISSING, SettingsRules.channelKeyState("short"))
        assertEquals(SettingsRules.KeyState.SHORT, SettingsRules.channelKeyState("12345678"))
        assertEquals(SettingsRules.KeyState.SHORT, SettingsRules.channelKeyState("elevenchars"))
        assertEquals(SettingsRules.KeyState.OK, SettingsRules.channelKeyState("twelvechars!"))
        assertEquals(SettingsRules.KeyState.OK, SettingsRules.channelKeyState(SettingsRules.generateChannelKey()))
    }

    @Test
    fun maskShowsOnlyTheLastFour() {
        assertEquals("•••• •••• ab3f", SettingsRules.maskChannelKey("north-star-ab3f"))
        assertEquals("•••• •••• 6789", SettingsRules.maskChannelKey("123456789"))
        assertEquals("", SettingsRules.maskChannelKey("abc"))         // nothing to show, nothing shown
        assertEquals("", SettingsRules.maskChannelKey(""))
        val key = SettingsRules.generateChannelKey()
        assertFalse(SettingsRules.maskChannelKey(key).contains(key))  // never the key itself
    }

    @Test
    fun crewNameStaysOnOneLine() {
        assertTrue(SettingsRules.validCrewName(""))
        assertTrue(SettingsRules.validCrewName(" Skipper "))
        assertTrue(SettingsRules.validCrewName("x".repeat(24)))
        assertFalse(SettingsRules.validCrewName("x".repeat(25)))
        assertFalse(SettingsRules.validCrewName("Crew\nTwo"))
    }

    @Test
    fun hopsAreBounded() {
        assertTrue(SettingsRules.validHops("1"))
        assertTrue(SettingsRules.validHops("16"))
        assertFalse(SettingsRules.validHops("0"))
        assertFalse(SettingsRules.validHops("17"))
        assertFalse(SettingsRules.validHops(""))
    }

    /**
     * The plugin trims its configured key and the app does not, so a key with a stray space
     * worked between phones and derived a different packet key on the boat server - which shows
     * up as the plugin simply never appearing on the roster, with nothing to say why. A key being
     * typed in is refused; one already stored is left alone, so nobody working today is broken.
     */
    @Test
    fun aKeyBeingTypedInMayNotBeginOrEndWithASpace() {
        assertFalse(SettingsRules.validChannelKey(" north-star-2026"))
        assertFalse(SettingsRules.validChannelKey("north-star-2026 "))
        assertFalse(SettingsRules.validChannelKey(" north-star-2026 "))
        assertTrue(SettingsRules.validChannelKey("north star 2026"))     // inside is fine: it is printable ASCII
        assertTrue(SettingsRules.validPassphrase(" carried over "))      // already stored: still accepted
    }
}
