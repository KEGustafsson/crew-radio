package fi.crewradio.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a slice of the boat's tree into the parts of an answer, and — the reason this class
 * exists — refusing to turn a stopped instrument into a number.
 */
class AskAnswerTest {

    private val now = 1_788_858_842_000L

    private fun leaf(value: Any?, ageSec: Long) = mapOf(
        "value" to value,
        "timestamp" to java.time.Instant.ofEpochMilli(now - ageSec * 1000).toString(),
    )

    private fun quantities(vararg ids: String) = ids.map { Quantity.byId(it)!! }

    private fun answer(tree: SignalKTree, vararg ids: String, prefs: AskUnits.Prefs = AskUnits.Prefs()) =
        AskAnswer.build(quantities(*ids), tree, now, prefs)

    @Test
    fun aFreshValueBecomesANumberAndItsUnit() {
        val tree = SignalKTree(
            mapOf(
                "navigation" to mapOf(
                    "headingMagnetic" to leaf(4.2771, ageSec = 2),
                    "speedOverGround" to leaf(3.19, ageSec = 2),
                ),
            )
        )
        val items = answer(tree, "heading", "speed").items
        val heading = items[0] as AskAnswer.Item.Value
        assertEquals("245", heading.number)
        assertEquals(AskAnswer.Unit.DEGREES, heading.unit)
        assertEquals("navigation.headingMagnetic", heading.path)
        assertFalse(heading.viaFallback)
        assertEquals(2L, heading.ageSec)

        val speed = items[1] as AskAnswer.Item.Value
        assertEquals("6.2", speed.number)
        assertEquals(AskAnswer.Unit.KNOTS, speed.unit)
    }

    @Test
    fun aStoppedInstrumentIsNeverSpokenAsANumber() {
        // The compass has been quiet for three minutes; the value is still in the tree.
        val tree = SignalKTree(
            mapOf(
                "navigation" to mapOf(
                    "headingMagnetic" to leaf(4.2771, ageSec = 180),
                    "speedOverGround" to leaf(3.19, ageSec = 1),
                ),
            )
        )
        val items = answer(tree, "heading", "speed").items
        val heading = items[0] as AskAnswer.Item.Missing
        assertEquals(180L, heading.quietSec)               // so the crew hears how long it has been out
        // The rest of the question is still answered.
        assertEquals("6.2", (items[1] as AskAnswer.Item.Value).number)
    }

    @Test
    fun aValueTheBoatDoesNotPublishAtAllIsDifferentFromAStoppedOne() {
        val missing = answer(SignalKTree.EMPTY, "heading").items.single() as AskAnswer.Item.Missing
        assertNull(missing.quietSec)
    }

    @Test
    fun aLeafWithoutATimestampIsTreatedAsStale() {
        // An age we cannot check is exactly what gets a stopped instrument believed.
        val tree = SignalKTree(mapOf("navigation" to mapOf("headingMagnetic" to mapOf("value" to 4.2771))))
        val item = answer(tree, "heading").items.single()
        assertTrue(item is AskAnswer.Item.Missing)
    }

    @Test
    fun aSilentCompassFallsBackToTheGpsAndSaysSo() {
        val tree = SignalKTree(
            mapOf("navigation" to mapOf("courseOverGroundTrue" to leaf(4.2771, ageSec = 1)))
        )
        val heading = answer(tree, "heading").items.single() as AskAnswer.Item.Value
        assertEquals("navigation.courseOverGroundTrue", heading.path)
        assertTrue(heading.viaFallback)                     // the wording then names the source
    }

    @Test
    fun aStaleFirstChoiceStillLetsTheFallbackAnswer() {
        val tree = SignalKTree(
            mapOf(
                "navigation" to mapOf(
                    "headingMagnetic" to leaf(1.0, ageSec = 600),
                    "headingTrue" to leaf(4.2771, ageSec = 1),
                ),
            )
        )
        val heading = answer(tree, "heading").items.single() as AskAnswer.Item.Value
        assertEquals("navigation.headingTrue", heading.path)
        assertEquals("245", heading.number)
    }

    @Test
    fun windAngleCarriesTheSideItIsOn() {
        val tree = SignalKTree(
            mapOf("environment" to mapOf("wind" to mapOf("angleApparent" to leaf(-0.7854, ageSec = 1))))
        )
        val wind = answer(tree, "windAngle").items.single() as AskAnswer.Item.Relative
        assertEquals(45, wind.degrees)
        assertTrue(wind.toPort)
    }

    @Test
    fun aPositionBecomesDegreesAndMinutes() {
        val tree = SignalKTree(
            mapOf(
                "navigation" to mapOf(
                    "position" to leaf(mapOf("latitude" to 60.163333, "longitude" to -24.956667), ageSec = 3),
                ),
            )
        )
        val position = answer(tree, "position").items.single() as AskAnswer.Item.Position
        assertEquals(60, position.latitude.degrees)
        assertEquals("09.8", position.latitude.minutes)
        assertTrue(position.latitude.positive)
        assertEquals(24, position.longitude.degrees)
        assertFalse(position.longitude.positive)
    }

    @Test
    fun timeToGoIsHoursAndMinutes() {
        val tree = SignalKTree(
            mapOf(
                "navigation" to mapOf(
                    "courseGreatCircle" to mapOf(
                        "nextPoint" to mapOf("timeToGo" to leaf(5700.0, ageSec = 4)),
                    ),
                ),
            )
        )
        val togo = answer(tree, "waypointTime").items.single() as AskAnswer.Item.Duration
        assertEquals(1L, togo.hours)
        assertEquals(35L, togo.minutes)
    }

    @Test
    fun theCrewsUnitsAreUsed() {
        val tree = SignalKTree(
            mapOf("environment" to mapOf("depth" to mapOf("belowKeel" to leaf(4.2, ageSec = 1))))
        )
        val feet = answer(tree, "depth", prefs = AskUnits.Prefs(depth = AskUnits.Depth.FEET))
            .items.single() as AskAnswer.Item.Value
        assertEquals("13.8", feet.number)
        assertEquals(AskAnswer.Unit.FEET, feet.unit)
    }

    @Test
    fun aValueOfTheWrongShapeIsMissingRatherThanACrash() {
        val tree = SignalKTree(
            mapOf("navigation" to mapOf("headingMagnetic" to leaf("north", ageSec = 1)))
        )
        assertTrue(answer(tree, "heading").items.single() is AskAnswer.Item.Missing)
    }

    @Test
    fun anAnswerKnowsWhetherItHasAnythingToSay() {
        assertFalse(answer(SignalKTree.EMPTY, "heading", "speed").hasValue)
        val tree = SignalKTree(mapOf("navigation" to mapOf("speedOverGround" to leaf(3.19, ageSec = 1))))
        assertTrue(answer(tree, "heading", "speed").hasValue)
    }

    @Test
    fun anInstancePathResolvesOnTheWayToTheAnswer() {
        val tree = SignalKTree(
            mapOf(
                "electrical" to mapOf(
                    "batteries" to mapOf("house" to mapOf("voltage" to leaf(12.61, ageSec = 5))),
                ),
            )
        )
        val volts = answer(tree, "batteryVoltage").items.single() as AskAnswer.Item.Value
        assertEquals("12.6", volts.number)
        assertEquals("electrical.batteries.house.voltage", volts.path)
    }
    @Test
    fun aQuestionThatNamedNoInstanceSaysWhichOneAnswered() {
        val twin = SignalKTree(
            mapOf(
                "propulsion" to mapOf(
                    "port" to mapOf("revolutions" to leaf(13.333, ageSec = 2)),
                    "starboard" to mapOf("revolutions" to leaf(35.0, ageSec = 2)),
                ),
            )
        )
        val revs = answer(twin, "engineRevolutions").items[0] as AskAnswer.Item.Value
        assertEquals("800", revs.number)
        assertEquals("port", revs.instance)
    }

    @Test
    fun aBoatWithOneOfSomethingNamesNoInstance() {
        val single = SignalKTree(
            mapOf("propulsion" to mapOf("0" to mapOf("revolutions" to leaf(13.333, ageSec = 2))))
        )
        assertNull((answer(single, "engineRevolutions").items[0] as AskAnswer.Item.Value).instance)
    }

    @Test
    fun oneEngineAnswersTheBareQuestionWhateverTheBoatCallsIt() {
        // "engine revs" has to work on a boat that never names a side, and the name it does use is
        // its own business: an instance is a key, not a convention.
        for (key in listOf("0", "1", "port", "prt_engine", "mainEngine", "Volvo_D2")) {
            val single = SignalKTree(
                mapOf("propulsion" to mapOf(key to mapOf("revolutions" to leaf(13.333, ageSec = 2))))
            )
            val revs = answer(single, "engineRevolutions").items[0] as AskAnswer.Item.Value
            assertEquals("800", revs.number)
            // Nothing else could have answered, so there is no instance to name.
            assertNull(revs.instance)
        }
    }

    @Test
    fun eachEngineCanBeAskedForByName() {
        val twin = SignalKTree(
            mapOf(
                "propulsion" to mapOf(
                    "0" to mapOf("revolutions" to leaf(13.333, ageSec = 2)),
                    "1" to mapOf("revolutions" to leaf(35.0, ageSec = 2)),
                ),
            )
        )
        val items = answer(twin, "engineRevolutionsPort", "engineRevolutionsStarboard").items
        assertEquals("800", (items[0] as AskAnswer.Item.Value).number)
        assertEquals("2100", (items[1] as AskAnswer.Item.Value).number)
        // The subject already says which engine, so the instance is not repeated after it.
        assertNull((items[0] as AskAnswer.Item.Value).instance)
    }

    @Test
    fun theStarboardEngineIsNeverAnsweredOffThePortOne() {
        val single = SignalKTree(
            mapOf("propulsion" to mapOf("port" to mapOf("revolutions" to leaf(13.333, ageSec = 2))))
        )
        val missing = answer(single, "engineRevolutionsStarboard").items[0] as AskAnswer.Item.Missing
        // Nothing was read at all, so there is no "quiet for" to report either.
        assertNull(missing.quietSec)
    }

    @Test
    fun theTrueHeadingIsAskedForOnItsOwnAndNeverAnsweredFromTheCompass() {
        val tree = SignalKTree(
            mapOf("navigation" to mapOf("headingMagnetic" to leaf(4.2771, ageSec = 2)))
        )
        assertEquals("245", (answer(tree, "headingMagnetic").items[0] as AskAnswer.Item.Value).number)
        assertTrue(answer(tree, "headingTrue").items[0] is AskAnswer.Item.Missing)
    }
}
