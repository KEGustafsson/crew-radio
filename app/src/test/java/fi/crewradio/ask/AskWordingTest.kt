package fi.crewradio.ask

import org.junit.Assert.assertEquals
import org.junit.Test

/** How an answer reads aloud. The words are English here; on the phone they come from resources. */
class AskWordingTest {

    private val vocabulary = AskWording.Vocabulary(
        quantity = mapOf(
            "heading" to "heading",
            "speed" to "speed",
            "depth" to "depth",
            "position" to "position",
            "windAngle" to "wind",
            "waypointTime" to "time to go",
            "batteryVoltage" to "battery",
        ),
        path = mapOf(
            "navigation.courseOverGroundTrue" to "course over ground",
            "navigation.headingTrue" to "true heading",
            "navigation.speedThroughWater" to "speed through water",
            "electrical.batteries.*.voltage" to "battery",
        ),
        unit = mapOf(
            AskAnswer.Unit.DEGREES to "degrees",
            AskAnswer.Unit.KNOTS to "knots",
            AskAnswer.Unit.METRES to "metres",
            AskAnswer.Unit.VOLTS to "volts",
        ),
        value = "%1\$s %2\$s %3\$s",
        toPort = "%1\$s %2\$s degrees to port",
        toStarboard = "%1\$s %2\$s degrees to starboard",
        position = "%1\$s %2\$s degrees %3\$s minutes %4\$s, %5\$s degrees %6\$s minutes %7\$s",
        north = "north",
        south = "south",
        east = "east",
        west = "west",
        hoursMinutes = "%1\$s hours %2\$s minutes",
        minutesOnly = "%1\$s minutes",
        missing = "no %1\$s",
        missingFor = "no %1\$s, nothing for %2\$s",
        quietMinutes = "%1\$s minutes",
        quietSeconds = "%1\$s seconds",
        notUnderstood = "say again",
    )

    private fun say(vararg items: AskAnswer.Item) =
        AskWording.sentence(AskAnswer.Answer(items.toList()), vocabulary)

    private fun value(
        id: String,
        number: String,
        unit: AskAnswer.Unit,
        path: String = "navigation.$id",
        viaFallback: Boolean = false,
    ) = AskAnswer.Item.Value(id, number, unit, path, viaFallback, 2)

    @Test
    fun theSubjectComesBeforeTheNumber() {
        assertEquals(
            "heading 245 degrees, speed 6.2 knots.",
            say(value("heading", "245", AskAnswer.Unit.DEGREES), value("speed", "6.2", AskAnswer.Unit.KNOTS)),
        )
    }

    @Test
    fun anUnexpectedSourceIsNamedSoItIsNotMistakenForTheUsualOne() {
        assertEquals(
            "course over ground 245 degrees.",
            say(
                value(
                    "heading", "245", AskAnswer.Unit.DEGREES,
                    path = "navigation.courseOverGroundTrue", viaFallback = true,
                )
            ),
        )
    }

    @Test
    fun aStoppedInstrumentSaysHowLongItHasBeenQuiet() {
        assertEquals(
            "no heading, nothing for 3 minutes, speed 6.2 knots.",
            say(
                AskAnswer.Item.Missing("heading", 180),
                value("speed", "6.2", AskAnswer.Unit.KNOTS),
            ),
        )
    }

    @Test
    fun somethingTheBoatDoesNotHaveIsSaidPlainly() {
        assertEquals("no depth.", say(AskAnswer.Item.Missing("depth", null)))
        assertEquals("no depth, nothing for 20 seconds.", say(AskAnswer.Item.Missing("depth", 20)))
    }

    @Test
    fun windCarriesTheSideItIsOn() {
        assertEquals(
            "wind 45 degrees to port.",
            say(AskAnswer.Item.Relative("windAngle", 45, true, "environment.wind.angleApparent", false, 1)),
        )
        assertEquals(
            "wind 30 degrees to starboard.",
            say(AskAnswer.Item.Relative("windAngle", 30, false, "environment.wind.angleApparent", false, 1)),
        )
    }

    private fun position(northOfZero: Boolean, eastOfZero: Boolean) = say(
        AskAnswer.Item.Position(
            "position",
            AskUnits.Coordinate(60, "09.8", northOfZero),
            AskUnits.Coordinate(24, "57.4", eastOfZero),
            "navigation.position",
            3,
        )
    )

    @Test
    fun aPositionReadsAsDegreesAndMinutes() {
        assertEquals(
            "position 60 degrees 09.8 minutes north, 24 degrees 57.4 minutes east.",
            position(northOfZero = true, eastOfZero = true),
        )
    }

    /**
     * All four, because the degrees carry no sign — [AskUnits.coordinate] keeps them positive and
     * puts the direction in `positive`. A template with the hemispheres written into it reads the
     * same for a position on the other side of the equator, which is the one case where being
     * wrong matters most.
     */
    @Test
    fun aPositionSaysWhichSideOfZeroItIsOn() {
        assertEquals(
            "position 60 degrees 09.8 minutes south, 24 degrees 57.4 minutes east.",
            position(northOfZero = false, eastOfZero = true),
        )
        assertEquals(
            "position 60 degrees 09.8 minutes north, 24 degrees 57.4 minutes west.",
            position(northOfZero = true, eastOfZero = false),
        )
        assertEquals(
            "position 60 degrees 09.8 minutes south, 24 degrees 57.4 minutes west.",
            position(northOfZero = false, eastOfZero = false),
        )
    }

    @Test
    fun aDurationWithNoUnitLeavesNoDoubleSpaceBehind() {
        assertEquals(
            "time to go 1 hours 35 minutes.",
            say(AskAnswer.Item.Duration("waypointTime", 1, 35, "navigation.courseGreatCircle.nextPoint.timeToGo", 4)),
        )
        assertEquals(
            "time to go 35 minutes.",
            say(AskAnswer.Item.Duration("waypointTime", 0, 35, "navigation.courseGreatCircle.nextPoint.timeToGo", 4)),
        )
    }

    @Test
    fun nothingRecognisedAsksForItAgain() {
        assertEquals("say again", AskWording.sentence(AskAnswer.Answer(emptyList()), vocabulary))
    }

    @Test
    fun anInstanceNameIsNotPartOfWhatSomethingIsCalled() {
        assertEquals(
            "electrical.batteries.*.voltage",
            AskWording.generalise("electrical.batteries.house.voltage"),
        )
        assertEquals("tanks.fuel.*.currentLevel", AskWording.generalise("tanks.fuel.0.currentLevel"))
        assertEquals("propulsion.*.revolutions", AskWording.generalise("propulsion.port.revolutions"))
        assertEquals("navigation.headingMagnetic", AskWording.generalise("navigation.headingMagnetic"))
    }

    @Test
    fun aWordThatIsMissingCostsThatWordOnlyNotTheAnswer() {
        // No label for "fuel" and no word for percent: the number still gets out.
        assertEquals("fuel 62.", say(value("fuel", "62", AskAnswer.Unit.PERCENT, path = "tanks.fuel.0.currentLevel")))
    }
}
