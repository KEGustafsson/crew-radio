package fi.crewradio.ask

/**
 * What the boat has to say, as data rather than as a sentence.
 *
 * The wording is not here on purpose. Every user-visible string in this app lives in
 * `res/values/strings.xml` so the whole thing can be translated in one pass, so this class decides
 * the hard parts — which source answered, whether the number is fresh enough to say at all, what
 * the number is once converted and rounded — and hands a structure to the Android side, which puts
 * the words around it. That split is also what makes all of it testable on the JVM.
 *
 * **The staleness gate is the point of this class.** A dead instrument keeps its last value in the
 * Signal K tree for ever. Answering "heading 245" from a compass that stopped ten minutes ago is
 * worse than answering nothing, because it is said with the same confidence as a live reading and
 * the crew has no way to tell. So a value older than its [Quantity.staleSec] never becomes a
 * number: it becomes [Item.Missing] carrying how long it has been quiet, and the rest of the
 * question is still answered — "no heading, no data for three minutes; speed 6.2 knots".
 */
object AskAnswer {

    /** Which words the Android side should put after the number. */
    enum class Unit { DEGREES, KNOTS, METRES_PER_SECOND, KM_PER_HOUR, METRES, FEET, NAUTICAL_MILES, CELSIUS, PERCENT, VOLTS, LITRES, RPM }

    sealed interface Item {
        val quantityId: String

        /** A plain number and its unit: heading, speed, depth, temperature, level, voltage, revolutions. */
        data class Value(
            override val quantityId: String,
            val number: String,
            val unit: Unit,
            /** The path it came from, with any instance resolved. */
            val path: String,
            /** True when the first-choice source was silent and a fallback answered, so the crew is told. */
            val viaFallback: Boolean,
            val ageSec: Long,
            /**
             * The instance that answered a question that did not name one, on a boat that has more
             * than one: "engine 1 2100 rpm" rather than "engine 2100 rpm", so nobody reads the port
             * tachometer as the starboard one. Null when the boat has only one, and null when the
             * question named the instance itself, because then the subject already says which.
             */
            val instance: String? = null,
        ) : Item

        /** A wind angle: degrees off the bow, and which side. */
        data class Relative(
            override val quantityId: String,
            val degrees: Int,
            val toPort: Boolean,
            val path: String,
            val viaFallback: Boolean,
            val ageSec: Long,
        ) : Item

        /** Degrees and decimal minutes, north/south and east/west, as a position is read out. */
        data class Position(
            override val quantityId: String,
            val latitude: AskUnits.Coordinate,
            val longitude: AskUnits.Coordinate,
            val path: String,
            val ageSec: Long,
        ) : Item

        /** Time to run: whole hours and minutes. */
        data class Duration(
            override val quantityId: String,
            val hours: Long,
            val minutes: Long,
            val path: String,
            val ageSec: Long,
        ) : Item

        /**
         * Nothing worth saying. [quietSec] is how long the value has been unchanged when the boat
         * does publish it but it has gone stale, and null when the boat does not publish it at all
         * — the difference between "the compass has stopped" and "there is no compass".
         */
        data class Missing(
            override val quantityId: String,
            val quietSec: Long?,
        ) : Item
    }

    /** Everything asked for, in the order it was asked. */
    data class Answer(val items: List<Item>) {
        /** True when at least one thing could actually be said. */
        val hasValue: Boolean get() = items.any { it !is Item.Missing }
    }

    /**
     * Reads each quantity out of [tree] and converts it.
     *
     * @param nowMs this phone's clock, the same one the freshness test uses
     * @param instances the crew's preferred instance per branch, and per branch and role, for the
     *   `*` in a path
     */
    fun build(
        quantities: List<Quantity>,
        tree: SignalKTree,
        nowMs: Long,
        prefs: AskUnits.Prefs = AskUnits.Prefs(),
        instances: Map<String, String> = emptyMap(),
    ): Answer = Answer(quantities.map { one(it, tree, nowMs, prefs, instances) })

    private fun one(
        quantity: Quantity,
        tree: SignalKTree,
        nowMs: Long,
        prefs: AskUnits.Prefs,
        instances: Map<String, String>,
    ): Item {
        // How long the freshest thing we found has been quiet, so a stale answer can say so even
        // though no path was fresh enough to speak.
        var quietest: Long? = null
        for ((index, path) in quantity.paths.withIndex()) {
            val leaf = tree.read(path, instances, quantity.role) ?: continue
            val timestamp = leaf.timestampMs
            if (timestamp == null) {
                // No timestamp is not "just now": it is an age we cannot check, and the gate exists
                // precisely because an unchecked age is what gets a stopped instrument believed.
                continue
            }
            val ageSec = ((nowMs - timestamp).coerceAtLeast(0L)) / 1000L
            if (ageSec > quantity.staleSec) {
                val known = quietest
                if (known == null || ageSec < known) quietest = ageSec
                continue
            }
            // A question that named its instance ("starboard engine revs") has it in its own name
            // already; one that did not says which it read, but only where there was a choice.
            val instance = if (quantity.role == null && leaf.ambiguous) leaf.instance else null
            convert(quantity, leaf, ageSec, index > 0, prefs, instance)?.let { return it }
        }
        return Item.Missing(quantity.id, quietest)
    }

    /** The SI value as something sayable, or null when the boat published a shape we cannot read. */
    private fun convert(
        quantity: Quantity,
        leaf: SignalKTree.Leaf,
        ageSec: Long,
        viaFallback: Boolean,
        prefs: AskUnits.Prefs,
        instance: String?,
    ): Item? {
        if (quantity.kind == Quantity.Kind.POSITION) {
            val map = leaf.value as? Map<*, *> ?: return null
            val latitude = number(map["latitude"]) ?: return null
            val longitude = number(map["longitude"]) ?: return null
            return Item.Position(
                quantity.id,
                AskUnits.coordinate(latitude),
                AskUnits.coordinate(longitude),
                leaf.path,
                ageSec,
            )
        }
        val value = number(leaf.value) ?: return null
        fun value(number: String, unit: Unit) =
            Item.Value(quantity.id, number, unit, leaf.path, viaFallback, ageSec, instance)
        return when (quantity.kind) {
            Quantity.Kind.HEADING ->
                value(AskUnits.headingDegrees(value).toString(), Unit.DEGREES)

            Quantity.Kind.RELATIVE_ANGLE -> {
                val degrees = AskUnits.relativeDegrees(value)
                Item.Relative(quantity.id, kotlin.math.abs(degrees), degrees < 0, leaf.path, viaFallback, ageSec)
            }

            Quantity.Kind.SPEED -> value(
                AskUnits.speed(value, prefs.speed),
                when (prefs.speed) {
                    AskUnits.Speed.KNOTS -> Unit.KNOTS
                    AskUnits.Speed.METRES_PER_SECOND -> Unit.METRES_PER_SECOND
                    AskUnits.Speed.KM_PER_HOUR -> Unit.KM_PER_HOUR
                },
            )

            Quantity.Kind.DEPTH -> value(
                AskUnits.depth(value, prefs.depth),
                if (prefs.depth == AskUnits.Depth.FEET) Unit.FEET else Unit.METRES,
            )

            Quantity.Kind.DISTANCE ->
                if (AskUnits.distanceIsClose(value)) value(AskUnits.distanceMetres(value), Unit.METRES)
                else value(AskUnits.distanceNauticalMiles(value), Unit.NAUTICAL_MILES)

            Quantity.Kind.TEMPERATURE -> value(AskUnits.celsius(value), Unit.CELSIUS)
            Quantity.Kind.RATIO -> value(AskUnits.percent(value).toString(), Unit.PERCENT)
            Quantity.Kind.VOLTAGE -> value(AskUnits.volts(value), Unit.VOLTS)
            Quantity.Kind.VOLUME -> value(AskUnits.litres(value), Unit.LITRES)
            Quantity.Kind.RPM -> value(AskUnits.rpm(value), Unit.RPM)

            Quantity.Kind.DURATION -> {
                val total = AskUnits.minutes(value)
                Item.Duration(quantity.id, total / 60, total % 60, leaf.path, ageSec)
            }

            Quantity.Kind.POSITION -> null   // handled above
        }
    }

    /** Numbers arrive from JSON as Double, Int or Long depending on the parser; booleans never do. */
    private fun number(value: Any?): Double? = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is java.math.BigDecimal -> value.toDouble()
        else -> null
    }
}
