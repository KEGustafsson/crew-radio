package fi.crewradio.ask

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Signal K is SI; the crew is not. Every conversion the boat's answers pass through is here, on
 * its own, so it can be checked against a table of known values without a phone in the loop.
 *
 * Rounding is part of the conversion, not a detail of the wording: a heading is whole degrees
 * because nobody steers to a tenth, a speed is one decimal because the difference between 6.2 and
 * 6.7 knots matters, and a depth is one decimal for the same reason. Numbers are formatted with
 * [Locale.ROOT] so the decimal separator is the point the speech engine expects, whatever the
 * phone's locale is set to.
 */
object AskUnits {

    enum class Speed { KNOTS, METRES_PER_SECOND, KM_PER_HOUR }
    enum class Depth { METRES, FEET }

    /** What the crew chose in Settings. */
    data class Prefs(val speed: Speed = Speed.KNOTS, val depth: Depth = Depth.METRES)

    const val MS_TO_KNOTS = 1.9438444924406046
    const val MS_TO_KMH = 3.6
    const val METRES_TO_FEET = 3.280839895013123
    const val METRES_PER_NAUTICAL_MILE = 1852.0
    const val KELVIN_ZERO_CELSIUS = 273.15

    /** Under this a distance is spoken in metres rather than a fraction of a mile. */
    const val METRES_BELOW = 1000.0

    /** A compass reading: radians to whole degrees, 0–359 (360 reads back as 0, as a compass does). */
    fun headingDegrees(radians: Double): Int {
        val degrees = Math.toDegrees(radians)
        val wrapped = ((degrees % 360.0) + 360.0) % 360.0
        return wrapped.roundToInt() % 360
    }

    /**
     * A wind angle: radians to degrees either side of the bow, negative to port, as Signal K
     * signs it. ±180 is dead astern; the sign is what the crew hears as "port" or "starboard",
     * so it survives the rounding.
     */
    fun relativeDegrees(radians: Double): Int {
        val degrees = Math.toDegrees(radians)
        var wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (wrapped <= -180.0) wrapped += 360.0
        return wrapped.roundToInt()
    }

    /** Metres per second in the crew's unit, one decimal. */
    fun speed(metresPerSecond: Double, unit: Speed): String = oneDecimal(
        when (unit) {
            Speed.KNOTS -> metresPerSecond * MS_TO_KNOTS
            Speed.METRES_PER_SECOND -> metresPerSecond
            Speed.KM_PER_HOUR -> metresPerSecond * MS_TO_KMH
        }
    )

    /** Metres in the crew's unit, one decimal. */
    fun depth(metres: Double, unit: Depth): String = oneDecimal(
        when (unit) {
            Depth.METRES -> metres
            Depth.FEET -> metres * METRES_TO_FEET
        }
    )

    /** True when a distance is close enough to be worth hearing in metres. */
    fun distanceIsClose(metres: Double): Boolean = abs(metres) < METRES_BELOW

    /** Whole metres, for a distance inside [METRES_BELOW]. */
    fun distanceMetres(metres: Double): String = metres.roundToLong().toString()

    /** Nautical miles, one decimal. */
    fun distanceNauticalMiles(metres: Double): String = oneDecimal(metres / METRES_PER_NAUTICAL_MILE)

    /** Kelvin to degrees Celsius, one decimal. */
    fun celsius(kelvin: Double): String = oneDecimal(kelvin - KELVIN_ZERO_CELSIUS)

    /** A 0–1 ratio to whole percent, clamped: a gauge reading 1.02 is full, not 102 percent. */
    fun percent(ratio: Double): Int = (ratio.coerceIn(0.0, 1.0) * 100.0).roundToInt()

    /** Volts, one decimal: the difference between 12.2 and 12.6 is the whole question. */
    fun volts(volts: Double): String = oneDecimal(volts)

    /** Cubic metres to whole litres. */
    fun litres(cubicMetres: Double): String = (cubicMetres * 1000.0).roundToLong().toString()

    /** Signal K counts revolutions in hertz; an engine is talked about in RPM, to the nearest ten. */
    fun rpm(hertz: Double): String = ((hertz * 60.0 / 10.0).roundToLong() * 10).toString()

    /** Seconds to whole minutes, rounded up from thirty seconds. */
    fun minutes(seconds: Double): Long = (seconds / 60.0).roundToLong()

    /**
     * Degrees and decimal minutes, the way a position is read out and written in a log:
     * 60° 09.8′ N. [degrees] is always positive; [north] carries the sign.
     */
    data class Coordinate(val degrees: Int, val minutes: String, val positive: Boolean)

    fun coordinate(value: Double): Coordinate {
        val positive = value >= 0.0
        val magnitude = abs(value)
        var degrees = magnitude.toInt()
        // Rounding the minutes can carry: 60° 59.97′ must read 61° 00.0′, never 60° 60.0′.
        var minutes = (magnitude - degrees) * 60.0
        if (oneDecimal(minutes) == "60.0") {
            degrees += 1
            minutes = 0.0
        }
        return Coordinate(degrees, oneDecimalPadded(minutes), positive)
    }

    /** One decimal, point separator, no thousands grouping: what the speech engine reads best. */
    fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    /** As [oneDecimal] but zero-padded to two integer digits, for the minutes of a position. */
    private fun oneDecimalPadded(value: Double): String = String.format(Locale.ROOT, "%04.1f", value)
}
