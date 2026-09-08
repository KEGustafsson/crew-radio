package fi.crewradio.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a value and its age out of a slice of the boat's tree, and resolving instance names. */
class SignalKTreeTest {

    private fun leaf(value: Any?, timestamp: String? = "2026-09-08T09:14:02.000Z", source: String? = "n2k.4") =
        buildMap<String, Any?> {
            put("value", value)
            if (timestamp != null) put("timestamp", timestamp)
            if (source != null) put("\$source", source)
        }

    private val tree = SignalKTree(
        mapOf(
            "navigation" to mapOf(
                "headingMagnetic" to leaf(4.2771),
                "position" to leaf(mapOf("latitude" to 60.163333, "longitude" to 24.956667)),
            ),
            "electrical" to mapOf(
                "batteries" to mapOf(
                    "house" to mapOf("voltage" to leaf(12.61)),
                    "start" to mapOf("voltage" to leaf(12.9)),
                ),
            ),
            "propulsion" to mapOf(
                "port" to mapOf("revolutions" to leaf(13.333)),
            ),
        )
    )

    /** A boat with two of everything, and numbered engines rather than named ones. */
    private val twin = SignalKTree(
        mapOf(
            "propulsion" to mapOf(
                "0" to mapOf("revolutions" to leaf(13.333)),
                "1" to mapOf("revolutions" to leaf(35.0)),
            ),
            "electrical" to mapOf(
                "batteries" to mapOf(
                    "house" to mapOf("voltage" to leaf(12.61)),
                    "start" to mapOf("voltage" to leaf(12.9)),
                ),
            ),
        )
    )

    @Test
    fun readsAValueWithItsTimestampAndSource() {
        val found = tree.read("navigation.headingMagnetic")!!
        assertEquals(4.2771, found.value as Double, 1e-9)
        assertEquals(1788858842000L, found.timestampMs!!)
        assertEquals("n2k.4", found.source)
        assertEquals("navigation.headingMagnetic", found.path)
    }

    @Test
    fun aPathTheBoatDoesNotPublishIsNull() {
        assertNull(tree.read("navigation.speedOverGround"))
        assertNull(tree.read("environment.depth.belowKeel"))
        assertNull(tree.read("navigation"))          // a branch is not a leaf
    }

    @Test
    fun aSingleInstanceNeedsNoConfiguring() {
        val found = tree.read("propulsion.*.revolutions")!!
        assertEquals("propulsion.port.revolutions", found.path)
    }

    @Test
    fun theCrewsChosenInstanceWins() {
        val found = tree.read("electrical.batteries.*.voltage", mapOf("electrical.batteries" to "start"))!!
        assertEquals("electrical.batteries.start.voltage", found.path)
        assertEquals(12.9, found.value as Double, 1e-9)
    }

    @Test
    fun withoutAChoiceTheInstancesResolveInAStableOrder() {
        val found = tree.read("electrical.batteries.*.voltage")!!
        assertEquals("electrical.batteries.house.voltage", found.path)
    }

    @Test
    fun anInstanceThatCannotSatisfyTheRestOfThePathIsSkipped() {
        val mixed = SignalKTree(
            mapOf(
                "tanks" to mapOf(
                    "fuel" to mapOf(
                        "0" to mapOf("capacity" to leaf(0.2)),        // no currentLevel
                        "1" to mapOf("currentLevel" to leaf(0.62)),
                    ),
                ),
            )
        )
        val found = mixed.read("tanks.fuel.*.currentLevel")!!
        assertEquals("tanks.fuel.1.currentLevel", found.path)
    }

    @Test
    fun theOnlyInstanceIsNotAChoiceWorthNaming() {
        val found = tree.read("propulsion.*.revolutions")!!
        assertEquals("port", found.instance)
        assertFalse(found.ambiguous)
    }

    @Test
    fun anInstanceOutOfSeveralIsReportedSoTheAnswerCanSayWhichItRead() {
        val found = twin.read("propulsion.*.revolutions")!!
        assertEquals("0", found.instance)
        assertTrue(found.ambiguous)
    }

    @Test
    fun aRoleFindsTheInstanceTheBoatNamedForIt() {
        val found = tree.read("electrical.batteries.*.voltage", role = Quantity.Role.START)!!
        assertEquals("electrical.batteries.start.voltage", found.path)
        assertEquals(12.9, found.value as Double, 1e-9)
    }

    @Test
    fun aNamedInstanceIsMatchedHoweverTheBoatSpellsIt() {
        // The side is usually only one word of what the boat calls the thing, and every boat
        // abbreviates it differently. Measured on the real boat: prt_engine and stb_engine.
        val spellings = listOf("Starboard_Engine", "stb_engine", "stbEngine", "starboard", "sb-1")
        for (key in spellings) {
            val spelled = SignalKTree(
                mapOf("propulsion" to mapOf(key to mapOf("revolutions" to leaf(35.0))))
            )
            assertEquals(
                "propulsion.$key.revolutions",
                spelled.read("propulsion.*.revolutions", role = Quantity.Role.STARBOARD)!!.path,
            )
        }
    }

    @Test
    fun theBoatsOwnTwinEnginesResolveToTheRightSide() {
        val boat = SignalKTree(
            mapOf(
                "propulsion" to mapOf(
                    "prt_engine" to mapOf("revolutions" to leaf(13.333)),
                    "stb_engine" to mapOf("revolutions" to leaf(35.0)),
                ),
            )
        )
        assertEquals(
            "propulsion.prt_engine.revolutions",
            boat.read("propulsion.*.revolutions", role = Quantity.Role.PORT)!!.path,
        )
        assertEquals(
            "propulsion.stb_engine.revolutions",
            boat.read("propulsion.*.revolutions", role = Quantity.Role.STARBOARD)!!.path,
        )
    }

    @Test
    fun numberedEnginesFollowTheNmea2000Convention() {
        assertEquals("propulsion.0.revolutions", twin.read("propulsion.*.revolutions", role = Quantity.Role.PORT)!!.path)
        assertEquals(
            "propulsion.1.revolutions",
            twin.read("propulsion.*.revolutions", role = Quantity.Role.STARBOARD)!!.path,
        )
    }

    @Test
    fun theCrewCanSayWhichNumberIsWhichSide() {
        // A boat wired the other way round: engine 1 is the port one.
        val swapped = mapOf("propulsion:port" to "1", "propulsion:starboard" to "0")
        assertEquals(
            "propulsion.1.revolutions",
            twin.read("propulsion.*.revolutions", swapped, Quantity.Role.PORT)!!.path,
        )
    }

    @Test
    fun aRoleThatMatchesNothingIsAbsentRatherThanItsSister() {
        // One engine, and it is the port one: "starboard engine revs" must not read it out.
        assertNull(tree.read("propulsion.*.revolutions", role = Quantity.Role.STARBOARD))
        // Batteries carry no numbering convention, so a numbered bank answers no role by itself.
        val numbered = SignalKTree(
            mapOf("electrical" to mapOf("batteries" to mapOf("0" to mapOf("voltage" to leaf(12.6)))))
        )
        assertNull(numbered.read("electrical.batteries.*.voltage", role = Quantity.Role.HOUSE))
    }

    @Test
    fun anInstanceThatCannotAnswerIsNotCountedAsAChoice() {
        val mixed = SignalKTree(
            mapOf(
                "tanks" to mapOf(
                    "fuel" to mapOf(
                        "0" to mapOf("capacity" to leaf(0.2)),        // no currentLevel
                        "1" to mapOf("currentLevel" to leaf(0.62)),
                    ),
                ),
            )
        )
        val found = mixed.read("tanks.fuel.*.currentLevel")!!
        assertEquals("1", found.instance)
        assertFalse(found.ambiguous)
        // ... and it cannot stand in for the port tank either.
        assertNull(mixed.read("tanks.fuel.*.currentLevel", role = Quantity.Role.PORT))
    }

    @Test
    fun aLeafWithoutATimestampIsReadButHasNoAge() {
        val untimed = SignalKTree(mapOf("navigation" to mapOf("headingTrue" to leaf(1.0, timestamp = null))))
        val found = untimed.read("navigation.headingTrue")!!
        assertNull(found.timestampMs)
    }

    @Test
    fun anUnparsableTimestampIsNoAgeRatherThanACrash() {
        assertNull(SignalKTree.parseTimestamp("yesterday"))
        assertNull(SignalKTree.parseTimestamp(null))
        assertNull(SignalKTree.parseTimestamp(""))
        assertEquals(1788858842000L, SignalKTree.parseTimestamp("2026-09-08T09:14:02.000Z")!!)
        // Some servers send an offset rather than Z.
        assertEquals(1788858842000L, SignalKTree.parseTimestamp("2026-09-08T12:14:02.000+03:00")!!)
    }

    @Test
    fun anEmptyTreeAnswersNothing() {
        assertNull(SignalKTree.EMPTY.read("navigation.headingMagnetic"))
    }
}
