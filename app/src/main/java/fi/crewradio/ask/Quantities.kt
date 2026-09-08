package fi.crewradio.ask

/**
 * What the crew can ask the boat for, and where each thing lives in Signal K.
 *
 * Signal K is SI throughout: an angle is radians, a speed is metres per second, a temperature is
 * kelvin, a tank level is a ratio. Every conversion the crew hears happens in [AskUnits]; nothing
 * in this file converts anything, it only says which conversion applies.
 *
 * [Quantity.paths] is a fallback chain, not one path: a boat without a compass still answers
 * "what is my heading" from its GPS course, and [AskAnswer] says which one it used when the
 * answer did not come from the first. A `*` segment stands for an instance name the boat chooses
 * itself (`electrical.batteries.house`, `tanks.fuel.0`); [SignalKTree] resolves it, to the one
 * [Quantity.role] names when the question named one ("starboard engine revs") and otherwise to
 * whichever the boat lists first, which the answer then says out loud.
 *
 * [Quantity.staleSec] is a safety rule, not a nicety. A dead instrument keeps its last value in
 * the Signal K tree for ever, so a value older than this is never spoken as a number: it is worse
 * to hear "heading 245" from a compass that stopped ten minutes ago than to hear nothing.
 */
data class Quantity(
    /** Stable id: what the phrase table maps to and what the strings are named after. Never shown. */
    val id: String,
    /** Where to look, best source first. */
    val paths: List<String>,
    /** How the SI value becomes something the crew can hear. */
    val kind: Kind,
    /** Older than this and the number is not said at all. */
    val staleSec: Int,
    /**
     * Which instance the `*` in [paths] has to resolve to, when the question named one
     * ("starboard engine revs"). Null is the generic question: it takes whichever instance the
     * boat resolves first, and says which one that was when the boat has more than one.
     */
    val role: Role? = null,
) {
    enum class Kind {
        /** Radians to whole degrees, 0–359. A heading or a bearing. */
        HEADING,
        /** Radians to signed degrees, port negative: apparent and true wind angle. */
        RELATIVE_ANGLE,
        /** Metres per second to the crew's speed unit. */
        SPEED,
        /** Metres to the crew's depth unit. */
        DEPTH,
        /** Metres to nautical miles, or to metres when it is close. */
        DISTANCE,
        /** Kelvin to degrees Celsius. */
        TEMPERATURE,
        /** A 0–1 ratio to whole percent. */
        RATIO,
        /** Volts, one decimal. */
        VOLTAGE,
        /** Cubic metres to litres. */
        VOLUME,
        /** Hertz to revolutions per minute. */
        RPM,
        /** Seconds to a spoken duration. */
        DURATION,
        /** `{latitude, longitude}` to degrees and decimal minutes. */
        POSITION,
    }

    /**
     * A named instance, so the crew can reach the second of something.
     *
     * No two boats name their instances alike: `propulsion.port`, `propulsion.prt_engine`,
     * `propulsion.1`; `electrical.batteries.house` and `electrical.batteries.0`. So a role is
     * matched in three steps: the crew's own line in Settings (`propulsion:starboard=1`), then
     * [matches] against the instance key, then [instanceNumber] against a key that is nothing but
     * a number, which is the NMEA 2000 convention (engine 0 is the port or only engine, engine 1
     * the starboard one). Battery and tank instances carry no such convention, so those roles
     * match by name only.
     *
     * [matches] reads the key as words rather than as one string, because the side is usually only
     * part of what the boat calls the thing: `prt_engine`, `stbEngine`, `Port Engine` all name a
     * side and a thing, and a whole-key comparison finds none of them.
     *
     * When none of the three finds it the answer is missing, never another instance. Reading the
     * port engine out as the starboard one is the same confident wrong answer that the staleness
     * gate exists to stop.
     */
    enum class Role(val names: List<String>, val instanceNumber: Int?) {
        PORT(listOf("port", "prt", "p", "portengine", "prtengine"), 0),
        STARBOARD(listOf("starboard", "stbd", "stb", "sb", "s", "starboardengine", "stbengine"), 1),
        HOUSE(listOf("house", "domestic", "service", "auxiliary", "aux"), null),
        START(listOf("start", "starter", "starting", "cranking", "engine"), null);

        /** How a Settings line names this role: the `starboard` of `propulsion:starboard=1`. */
        val key: String get() = name.lowercase()

        /** True when [instance] is this role, whole (`starboard`) or as one word of it (`stb_engine`). */
        fun matches(instance: String): Boolean =
            instance.lowercase().filter { it.isLetterOrDigit() } in names ||
                words(instance).any { it in names }

        companion object {
            /**
             * An instance key as the words a boat wrote into it: `prt_engine` and `stbEngine` and
             * `Port Engine` are all a side and a thing. Anything that is not a letter or a digit
             * separates, and so does the step from a lower-case letter into a capital, which is
             * how Signal K instance names are usually written.
             */
            fun words(key: String): List<String> =
                key.split(Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])"))
                    .filter { it.isNotEmpty() }
                    .map { it.lowercase() }
        }
    }

    companion object {
        /**
         * The catalogue. Ten or so covers what is actually asked under way; the crew can add more
         * from Settings without a release, because the phrase table is a resource (see
         * [AskIntents.parse]) and an extra entry here is one line.
         *
         * The stale windows differ on purpose: a compass that has been quiet for half a minute is
         * broken, a tank gauge that has been quiet for half an hour is normal.
         */
        val ALL: List<Quantity> = listOf(
            Quantity(
                "heading",
                listOf("navigation.headingTrue", "navigation.headingMagnetic", "navigation.courseOverGroundTrue"),
                Kind.HEADING, staleSec = 30,
            ),
            Quantity(
                "course",
                listOf("navigation.courseOverGroundTrue", "navigation.headingTrue", "navigation.headingMagnetic"),
                Kind.HEADING, staleSec = 30,
            ),
            Quantity(
                "speed",
                listOf("navigation.speedOverGround", "navigation.speedThroughWater"),
                Kind.SPEED, staleSec = 30,
            ),
            Quantity(
                "speedThroughWater",
                listOf("navigation.speedThroughWater"),
                Kind.SPEED, staleSec = 30,
            ),
            Quantity(
                "depth",
                listOf(
                    "environment.depth.belowKeel",
                    "environment.depth.belowTransducer",
                    "environment.depth.belowSurface",
                ),
                Kind.DEPTH, staleSec = 30,
            ),
            Quantity(
                "position",
                listOf("navigation.position"),
                Kind.POSITION, staleSec = 60,
            ),
            Quantity(
                "windSpeed",
                listOf("environment.wind.speedApparent", "environment.wind.speedTrue"),
                Kind.SPEED, staleSec = 30,
            ),
            Quantity(
                "windAngle",
                listOf("environment.wind.angleApparent", "environment.wind.angleTrueWater"),
                Kind.RELATIVE_ANGLE, staleSec = 30,
            ),
            Quantity(
                "waterTemperature",
                listOf("environment.water.temperature"),
                Kind.TEMPERATURE, staleSec = 300,
            ),
            Quantity(
                "airTemperature",
                listOf("environment.outside.temperature"),
                Kind.TEMPERATURE, staleSec = 300,
            ),
            Quantity(
                "batteryVoltage",
                listOf("electrical.batteries.*.voltage"),
                Kind.VOLTAGE, staleSec = 300,
            ),
            Quantity(
                "batteryCharge",
                listOf("electrical.batteries.*.capacity.stateOfCharge"),
                Kind.RATIO, staleSec = 300,
            ),
            Quantity(
                "fuel",
                listOf("tanks.fuel.*.currentLevel"),
                Kind.RATIO, staleSec = 1800,
            ),
            Quantity(
                "water",
                listOf("tanks.freshWater.*.currentLevel"),
                Kind.RATIO, staleSec = 1800,
            ),
            Quantity(
                "engineTemperature",
                listOf("propulsion.*.temperature"),
                Kind.TEMPERATURE, staleSec = 60,
            ),
            Quantity(
                "engineRevolutions",
                listOf("propulsion.*.revolutions"),
                Kind.RPM, staleSec = 30,
            ),
            Quantity(
                "waypointDistance",
                listOf("navigation.courseGreatCircle.nextPoint.distance", "navigation.course.calcValues.distance"),
                Kind.DISTANCE, staleSec = 60,
            ),
            Quantity(
                "waypointBearing",
                listOf("navigation.courseGreatCircle.nextPoint.bearingTrue", "navigation.course.calcValues.bearingTrue"),
                Kind.HEADING, staleSec = 60,
            ),
            Quantity(
                "waypointTime",
                listOf("navigation.courseGreatCircle.nextPoint.timeToGo", "navigation.course.calcValues.timeToGo"),
                Kind.DURATION, staleSec = 60,
            ),

            // The same things again, but naming a source or an instance, for the boats that have
            // two of something. The generic questions above still answer on those boats - they
            // say which instance they read - and these are how the crew reaches the other one.

            // A boat with a compass and a GPS has three answers to "heading" and the generic
            // question can only speak one of them. These two ask for exactly one source and have
            // no fallback: asked for the true heading, "no true heading" is the honest answer, and
            // a magnetic bearing spoken as a true one puts the boat on the wrong side of a mark.
            Quantity(
                "headingMagnetic",
                listOf("navigation.headingMagnetic"),
                Kind.HEADING, staleSec = 30,
            ),
            Quantity(
                "headingTrue",
                listOf("navigation.headingTrue"),
                Kind.HEADING, staleSec = 30,
            ),
            Quantity(
                "engineRevolutionsPort",
                listOf("propulsion.*.revolutions"),
                Kind.RPM, staleSec = 30, role = Role.PORT,
            ),
            Quantity(
                "engineRevolutionsStarboard",
                listOf("propulsion.*.revolutions"),
                Kind.RPM, staleSec = 30, role = Role.STARBOARD,
            ),
            Quantity(
                "engineTemperaturePort",
                listOf("propulsion.*.temperature"),
                Kind.TEMPERATURE, staleSec = 60, role = Role.PORT,
            ),
            Quantity(
                "engineTemperatureStarboard",
                listOf("propulsion.*.temperature"),
                Kind.TEMPERATURE, staleSec = 60, role = Role.STARBOARD,
            ),
            Quantity(
                "batteryVoltageHouse",
                listOf("electrical.batteries.*.voltage"),
                Kind.VOLTAGE, staleSec = 300, role = Role.HOUSE,
            ),
            Quantity(
                "batteryVoltageStart",
                listOf("electrical.batteries.*.voltage"),
                Kind.VOLTAGE, staleSec = 300, role = Role.START,
            ),
            Quantity(
                "batteryChargeHouse",
                listOf("electrical.batteries.*.capacity.stateOfCharge"),
                Kind.RATIO, staleSec = 300, role = Role.HOUSE,
            ),
            Quantity(
                "batteryChargeStart",
                listOf("electrical.batteries.*.capacity.stateOfCharge"),
                Kind.RATIO, staleSec = 300, role = Role.START,
            ),
            Quantity(
                "fuelPort",
                listOf("tanks.fuel.*.currentLevel"),
                Kind.RATIO, staleSec = 1800, role = Role.PORT,
            ),
            Quantity(
                "fuelStarboard",
                listOf("tanks.fuel.*.currentLevel"),
                Kind.RATIO, staleSec = 1800, role = Role.STARBOARD,
            ),
            Quantity(
                "waterPort",
                listOf("tanks.freshWater.*.currentLevel"),
                Kind.RATIO, staleSec = 1800, role = Role.PORT,
            ),
            Quantity(
                "waterStarboard",
                listOf("tanks.freshWater.*.currentLevel"),
                Kind.RATIO, staleSec = 1800, role = Role.STARBOARD,
            ),
        )

        private val byId = ALL.associateBy { it.id }

        fun byId(id: String): Quantity? = byId[id]

        /**
         * The Signal K subtrees that between them hold every path in the catalogue: the top
         * segment of each path — `environment`, not `environment.depth` — which is what
         * `SignalKUrl.selfBranch` takes. One GET each answers any question, and a question about
         * two things in different subtrees ("depth and speed") costs two requests, not one per value.
         */
        fun subtreesOf(quantities: List<Quantity>): List<String> =
            quantities.flatMap { q -> q.paths.map { it.substringBefore('.') } }.distinct()
    }
}
