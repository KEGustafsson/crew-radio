package fi.crewradio.ask

import android.content.Context
import fi.crewradio.R

/**
 * The words [AskWording] puts round an answer, taken from `res/values/strings.xml`.
 *
 * This is the only place where a quantity id or a Signal K path meets a resource, and it is
 * deliberately an explicit table rather than a name lookup: `getIdentifier` would survive a typo
 * silently and cannot be shrunk by R8.
 */
object AskVocabulary {

    fun of(context: Context): AskWording.Vocabulary = AskWording.Vocabulary(
        quantity = Quantity.ALL.associate { it.id to context.getString(quantityLabel(it.id)) },
        path = PATH_LABELS.mapValues { context.getString(it.value) },
        unit = AskAnswer.Unit.entries.associateWith { context.getString(unitLabel(it)) },
        value = context.getString(R.string.ask_value),
        toPort = context.getString(R.string.ask_to_port),
        toStarboard = context.getString(R.string.ask_to_starboard),
        position = context.getString(R.string.ask_position_value),
        north = context.getString(R.string.ask_north),
        south = context.getString(R.string.ask_south),
        east = context.getString(R.string.ask_east),
        west = context.getString(R.string.ask_west),
        hoursMinutes = context.getString(R.string.ask_hours_minutes),
        minutesOnly = context.getString(R.string.ask_minutes_only),
        missing = context.getString(R.string.ask_missing),
        missingFor = context.getString(R.string.ask_missing_for),
        quietMinutes = context.getString(R.string.ask_quiet_minutes),
        quietSeconds = context.getString(R.string.ask_quiet_seconds),
        separator = context.getString(R.string.ask_separator),
        terminator = context.getString(R.string.ask_terminator),
        notUnderstood = context.getString(R.string.ask_say_again),
    )

    /** The trigger phrases, as the crew (and a translator) may edit them. */
    fun intents(context: Context): AskIntents =
        AskIntents(AskIntents.parse(context.resources.getStringArray(R.array.ask_phrases).toList()))

    private fun quantityLabel(id: String): Int = when (id) {
        "heading" -> R.string.ask_q_heading
        "course" -> R.string.ask_q_course
        "speed" -> R.string.ask_q_speed
        "speedThroughWater" -> R.string.ask_q_speedThroughWater
        "depth" -> R.string.ask_q_depth
        "position" -> R.string.ask_q_position
        "windSpeed" -> R.string.ask_q_windSpeed
        "windAngle" -> R.string.ask_q_windAngle
        "waterTemperature" -> R.string.ask_q_waterTemperature
        "airTemperature" -> R.string.ask_q_airTemperature
        "batteryVoltage" -> R.string.ask_q_batteryVoltage
        "batteryCharge" -> R.string.ask_q_batteryCharge
        "fuel" -> R.string.ask_q_fuel
        "water" -> R.string.ask_q_water
        "engineTemperature" -> R.string.ask_q_engineTemperature
        "engineRevolutions" -> R.string.ask_q_engineRevolutions
        "waypointDistance" -> R.string.ask_q_waypointDistance
        "waypointBearing" -> R.string.ask_q_waypointBearing
        "waypointTime" -> R.string.ask_q_waypointTime
        else -> R.string.ask_q_heading   // unreachable: every id in Quantity.ALL is above
    }

    private fun unitLabel(unit: AskAnswer.Unit): Int = when (unit) {
        AskAnswer.Unit.DEGREES -> R.string.ask_u_degrees
        AskAnswer.Unit.KNOTS -> R.string.ask_u_knots
        AskAnswer.Unit.METRES_PER_SECOND -> R.string.ask_u_ms
        AskAnswer.Unit.KM_PER_HOUR -> R.string.ask_u_kmh
        AskAnswer.Unit.METRES -> R.string.ask_u_metres
        AskAnswer.Unit.FEET -> R.string.ask_u_feet
        AskAnswer.Unit.NAUTICAL_MILES -> R.string.ask_u_nautical_miles
        AskAnswer.Unit.CELSIUS -> R.string.ask_u_celsius
        AskAnswer.Unit.PERCENT -> R.string.ask_u_percent
        AskAnswer.Unit.VOLTS -> R.string.ask_u_volts
        AskAnswer.Unit.LITRES -> R.string.ask_u_litres
        AskAnswer.Unit.RPM -> R.string.ask_u_rpm
    }

    /**
     * Only the sources whose name differs from the quantity's own need a label: they are what the
     * answer calls itself when the usual instrument was silent and something else answered.
     */
    private val PATH_LABELS: Map<String, Int> = mapOf(
        "navigation.headingMagnetic" to R.string.ask_p_headingMagnetic,
        "navigation.headingTrue" to R.string.ask_p_headingTrue,
        "navigation.courseOverGroundTrue" to R.string.ask_p_courseOverGroundTrue,
        "navigation.speedOverGround" to R.string.ask_p_speedOverGround,
        "navigation.speedThroughWater" to R.string.ask_p_speedThroughWater,
        "environment.depth.belowKeel" to R.string.ask_p_depthBelowKeel,
        "environment.depth.belowTransducer" to R.string.ask_p_depthBelowTransducer,
        "environment.depth.belowSurface" to R.string.ask_p_depthBelowSurface,
        "environment.wind.speedTrue" to R.string.ask_p_windSpeedTrue,
        "environment.wind.angleTrueWater" to R.string.ask_p_windAngleTrue,
    )
}
