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
 * itself (`electrical.batteries.house`, `tanks.fuel.0`); [SignalKTree] resolves it.
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
                listOf("navigation.headingMagnetic", "navigation.headingTrue", "navigation.courseOverGroundTrue"),
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
