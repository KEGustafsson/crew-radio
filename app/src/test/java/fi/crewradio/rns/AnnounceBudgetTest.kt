package fi.crewradio.rns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** rnsd 1.5.4's announce rate rule, as the plugin's test/rns.test.js checks its copy. */
class AnnounceBudgetTest {
    @Test
    fun sixAtOnceThenOneAnHour() {
        val b = AnnounceBudget()
        var t = 0L
        val passed = ArrayList<Boolean>()
        repeat(8) { passed += b.record(t); t += 60_000 }
        assertEquals("the first opens the record, five more are the grace", listOf(true, true, true, true, true, true, false, false), passed)
        assertFalse(b.peek(t).first)
        // An hour after the last one passed, one passes again, and the next one soon after does not.
        t = 5 * 60_000L + AnnounceBudget.TARGET_MS + 1
        assertTrue(b.peek(t).first)
        assertTrue(b.record(t))
        assertFalse(b.record(t + 60_000))
        // Spaced an hour apart they always pass, and each one forgives a violation.
        var u = t + AnnounceBudget.TARGET_MS * 2
        repeat(6) { assertTrue(b.record(u)); u += AnnounceBudget.TARGET_MS }
        assertEquals(0, b.violations)
    }

    @Test
    fun theDefaultsAreRnsdsWithTransportOn() {
        assertEquals(3_600_000L, AnnounceBudget.TARGET_MS)
        assertEquals(5, AnnounceBudget.GRACE)
    }
}
