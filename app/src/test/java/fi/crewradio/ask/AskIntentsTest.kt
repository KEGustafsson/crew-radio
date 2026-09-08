package fi.crewradio.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript matcher. These are the phrasings the crew actually uses, plus the ways Android's
 * recogniser gets them slightly wrong; see [AskIntents] for why each rule is the way it is.
 */
class AskIntentsTest {

    /** The same lines `res/values/arrays.xml` ships, kept here so the rules are tested without resources. */
    private val intents = AskIntents(
        AskIntents.parse(
            listOf(
                "heading|heading|what is our heading|course to steer",
                "course|course over ground|ground course",
                "speed|speed|boat speed|how fast are we going",
                "speedThroughWater|speed through water|water speed",
                "depth|depth|how deep is it|water under us",
                "position|position|where are we|our position",
                "windSpeed|wind speed|how much wind",
                "windAngle|wind angle|wind direction",
                "waterTemperature|water temperature|sea temperature",
                "batteryVoltage|battery|battery voltage",
                "fuel|fuel|fuel level",
                "waypointDistance|distance to go|distance to waypoint",
            )
        )
    )

    private fun ids(vararg hypotheses: String, wake: String? = "Northstar") =
        intents.match(hypotheses.toList(), wake).quantities.map { it.id }

    @Test
    fun answersTwoThingsInTheOrderTheyWereAsked() {
        assertEquals(listOf("heading", "speed"), ids("Northstar, what is heading and speed now?"))
        assertEquals(listOf("speed", "heading"), ids("speed and heading"))
    }

    @Test
    fun theLongerPhraseWinsAndTakesItsWordsOutOfPlay() {
        // "wind speed" must not also answer the boat's speed.
        assertEquals(listOf("windSpeed"), ids("what is the wind speed"))
        // Both, when both were genuinely asked for.
        assertEquals(listOf("speed", "windSpeed"), ids("speed and wind speed"))
    }

    @Test
    fun theWakeWordIsDroppedWhereverItFalls() {
        assertEquals(listOf("depth"), ids("Northstar depth"))
        assertEquals(listOf("depth"), ids("depth please Northstar"))
        // Dictation rarely knows a boat's name, so it is matched loosely.
        assertEquals(listOf("depth"), ids("Arabela depth"))
        assertEquals(listOf("depth"), ids("Annabella depth"))
    }

    @Test
    fun aLongWordSurvivesOneWrongLetterAndAShortOneDoesNot() {
        // "depth" is five letters, so one edit is forgiven.
        assertEquals(listOf("depth"), ids("dept"))
        // "wind" is four, so it is matched exactly: at that length one edit reaches real words.
        assertTrue(ids("mind angle").isEmpty())
        assertTrue(ids("find angle").isEmpty())
    }

    @Test
    fun everyHypothesisIsTriedNotJustTheBestOne() {
        // The recogniser's best guess is two edits out, so nothing in it matches; the second
        // hypothesis is the one the crew actually said.
        val match = intents.match(listOf("speak over ground", "speed over ground"), "Northstar")
        assertEquals(listOf("speed"), match.quantities.map { it.id })
        assertEquals("speed over ground", match.transcript)
    }

    @Test
    fun nothingRecognisedKeepsTheFirstHypothesisToShow() {
        val match = intents.match(listOf("did you see the otter", "did you see the water"), "Northstar")
        assertTrue(match.quantities.isEmpty())
        assertEquals("did you see the otter", match.transcript)
    }

    @Test
    fun aBreathHoldsAtMostFourThings() {
        val match = ids("heading speed depth position wind speed fuel battery")
        assertEquals(AskIntents.MAX_QUANTITIES, match.size)
    }

    @Test
    fun anEmptyOrUnknownLineCostsThatLineOnly() {
        val triggers = AskIntents.parse(listOf("heading|heading", "notAQuantity|banana", "malformed", "", "depth|depth"))
        assertEquals(listOf("heading", "depth"), triggers.map { it.quantityId })
    }

    @Test
    fun tokenisingDropsPunctuationAndCase() {
        assertEquals(listOf("what", "is", "our", "heading"), AskIntents.tokenise("  What is our HEADING?  "))
    }

    @Test
    fun matchingWithoutAWakeWordStillWorks() {
        assertEquals(listOf("heading"), ids("heading", wake = null))
    }
}
