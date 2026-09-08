package fi.crewradio.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                "headingMagnetic|magnetic heading|compass heading",
                "headingTrue|true heading",
                "engineRevolutions|engine revs|engine speed",
                "engineRevolutionsPort|port engine revs|port revs",
                "engineRevolutionsStarboard|starboard engine revs|starboard revs",
                "batteryVoltageHouse|house battery|service battery",
                "batteryVoltageStart|start battery|starter battery",
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
    }

    /**
     * Dictation rarely knows a boat's name, so it is matched loosely.
     *
     * Asserted on [AskIntents.isWakeWord] rather than through a whole question on purpose: a
     * mishearing that is *not* recognised is dropped from the match as an unknown word anyway, so
     * the question still answers and an end-to-end assertion passes either way. Only this says
     * whether the name was actually recognised — which is what stops the test quietly meaning
     * nothing the next time the boat is renamed.
     */
    @Test
    fun theBoatsNameSurvivesBeingMisheard() {
        assertTrue(AskIntents.isWakeWord("northstar", "Northstar"))
        assertTrue(AskIntents.isWakeWord("norhstar", "Northstar"))    // a letter dropped
        assertTrue(AskIntents.isWakeWord("nordstar", "Northstar"))    // two edits, allowed for a long name
        // Not the name, and not close enough to be taken for it.
        assertFalse(AskIntents.isWakeWord("north", "Northstar"))
        assertFalse(AskIntents.isWakeWord("depth", "Northstar"))
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
    @Test
    fun namingAnInstanceAsksForThatOneAndNotTheGenericThing() {
        // "port engine revs" must not also answer the bare "engine revs": the longer phrase wins
        // and takes its words with it, or a twin-engine boat reads out the same tachometer twice.
        assertEquals(listOf("engineRevolutionsPort"), ids("port engine revs"))
        assertEquals(listOf("engineRevolutionsStarboard"), ids("starboard revs"))
        assertEquals(listOf("engineRevolutions"), ids("engine revs"))
        assertEquals(
            listOf("engineRevolutionsPort", "engineRevolutionsStarboard"),
            ids("port engine revs and starboard engine revs"),
        )
    }

    @Test
    fun namingABatteryBankAsksForThatBank() {
        assertEquals(listOf("batteryVoltageHouse"), ids("house battery"))
        assertEquals(listOf("batteryVoltageStart"), ids("Northstar, starter battery"))
        assertEquals(listOf("batteryVoltage"), ids("battery"))
    }

    @Test
    fun aHeadingSourceCanBeAskedForByName() {
        assertEquals(listOf("headingMagnetic"), ids("magnetic heading"))
        assertEquals(listOf("headingTrue"), ids("true heading"))
        assertEquals(listOf("heading"), ids("what is our heading"))
    }

}
