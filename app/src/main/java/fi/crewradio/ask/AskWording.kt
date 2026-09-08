package fi.crewradio.ask

/**
 * The sentence the boat says, built from [AskAnswer.Answer].
 *
 * The words themselves come in through [Vocabulary], which the Android side fills from
 * `res/values/strings.xml`. That keeps every user-visible string in one translatable place and
 * still lets the assembly — the order, the joining, what a stale reading turns into, when the
 * source is named — be tested on its own.
 *
 * What a good answer sounds like on a working boat is the whole design here:
 *
 *  * **Subject first.** "Heading 245 degrees", never "245". Half an answer heard over an engine
 *    is still useful if the half you caught was the subject.
 *  * **Name an unexpected source.** When the compass is silent and the GPS answered, the crew
 *    hears "course over ground 245 degrees" — the number is not wrong, but it is not the same
 *    thing, and they must be able to tell.
 *  * **Say what is missing, and for how long.** "No depth, nothing for four minutes" is an
 *    answer. A silence is not, and a stale number is worse than either.
 */
object AskWording {

    /**
     * Every word the sentence can need. The Android side builds one of these from resources; a
     * test builds one from literals.
     */
    data class Vocabulary(
        /** Quantity id to its name: `heading` to "heading". Used when nothing could be read. */
        val quantity: Map<String, String>,
        /** Resolved Signal K path to its name: `navigation.courseOverGroundTrue` to "course over ground". */
        val path: Map<String, String>,
        /** Unit to the word after the number: `KNOTS` to "knots". */
        val unit: Map<AskAnswer.Unit, String>,
        /** `%1$s` subject, `%2$s` number, `%3$s` unit: "%1$s %2$s %3$s". */
        val value: String,
        /** `%1$s` subject, `%2$s` degrees: "%1$s %2$s degrees to port". */
        val toPort: String,
        val toStarboard: String,
        /** `%1$s` subject, then degrees, minutes and a hemisphere for each half. */
        val position: String,
        /** The hemispheres themselves, chosen by the sign of each coordinate. */
        val north: String,
        val south: String,
        val east: String,
        val west: String,
        /** `%1$s` hours, `%2$s` minutes. */
        val hoursMinutes: String,
        /** `%1$s` minutes, for under an hour. */
        val minutesOnly: String,
        /** `%1$s` subject: "no %1$s". */
        val missing: String,
        /** `%1$s` subject, `%2$s` how long: "no %1$s, nothing for %2$s". */
        val missingFor: String,
        /** `%1$s` a whole number of minutes; used for how long something has been quiet. */
        val quietMinutes: String,
        val quietSeconds: String,
        /** What separates two answers, and what ends the sentence. */
        val separator: String = ", ",
        val terminator: String = ".",
        /** Nothing in the question was recognised. */
        val notUnderstood: String = "",
    )

    /** The whole answer as one line, ready to be spoken and shown. */
    fun sentence(answer: AskAnswer.Answer, vocabulary: Vocabulary): String {
        if (answer.items.isEmpty()) return vocabulary.notUnderstood
        val parts = answer.items.map { part(it, vocabulary) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return vocabulary.notUnderstood
        return parts.joinToString(vocabulary.separator) + vocabulary.terminator
    }

    private fun part(item: AskAnswer.Item, vocabulary: Vocabulary): String = when (item) {
        is AskAnswer.Item.Value -> format(
            vocabulary.value,
            subject(item.quantityId, item.path, item.viaFallback, vocabulary),
            item.number,
            vocabulary.unit[item.unit] ?: "",
        )

        is AskAnswer.Item.Relative -> format(
            if (item.toPort) vocabulary.toPort else vocabulary.toStarboard,
            subject(item.quantityId, item.path, item.viaFallback, vocabulary),
            item.degrees.toString(),
        )

        // The sign of each coordinate picks its hemisphere. AskUnits.coordinate() keeps the
        // degrees positive and puts the direction in `positive`, so it has to be read back here
        // or every position south or west of zero is announced as north and east.
        is AskAnswer.Item.Position -> format(
            vocabulary.position,
            subject(item.quantityId, item.path, false, vocabulary),
            item.latitude.degrees.toString(),
            item.latitude.minutes,
            if (item.latitude.positive) vocabulary.north else vocabulary.south,
            item.longitude.degrees.toString(),
            item.longitude.minutes,
            if (item.longitude.positive) vocabulary.east else vocabulary.west,
        )

        is AskAnswer.Item.Duration -> {
            val time =
                if (item.hours > 0) format(vocabulary.hoursMinutes, item.hours.toString(), item.minutes.toString())
                else format(vocabulary.minutesOnly, item.minutes.toString())
            format(vocabulary.value, subject(item.quantityId, item.path, false, vocabulary), time, "")
        }

        is AskAnswer.Item.Missing -> {
            val name = vocabulary.quantity[item.quantityId] ?: item.quantityId
            val quiet = item.quietSec
            if (quiet == null) format(vocabulary.missing, name)
            else format(vocabulary.missingFor, name, quietFor(quiet, vocabulary))
        }
    }

    /**
     * What the answer calls itself. The first-choice source is named by the quantity ("heading");
     * a fallback is named by the path it actually came from ("course over ground"), so the crew is
     * never told that a GPS course is a compass heading.
     */
    private fun subject(quantityId: String, path: String, viaFallback: Boolean, vocabulary: Vocabulary): String {
        if (viaFallback) vocabulary.path[generalise(path)]?.let { return it }
        return vocabulary.quantity[quantityId] ?: vocabulary.path[generalise(path)] ?: quantityId
    }

    /**
     * A resolved path back to the shape the labels are keyed by: the instance a boat chose for
     * itself is not part of what the thing is called, so `electrical.batteries.house.voltage`
     * looks up as `electrical.batteries.*.voltage`.
     */
    fun generalise(path: String): String {
        val segments = path.split('.')
        return when {
            segments.size >= 4 && segments[0] == "electrical" && segments[1] == "batteries" ->
                (listOf("electrical", "batteries", "*") + segments.drop(3)).joinToString(".")
            segments.size >= 4 && segments[0] == "tanks" ->
                (listOf("tanks", segments[1], "*") + segments.drop(3)).joinToString(".")
            segments.size >= 3 && segments[0] == "propulsion" ->
                (listOf("propulsion", "*") + segments.drop(2)).joinToString(".")
            else -> path
        }
    }

    /** How long something has been quiet, in whole minutes once it is past a minute. */
    private fun quietFor(seconds: Long, vocabulary: Vocabulary): String =
        if (seconds >= 60) format(vocabulary.quietMinutes, (seconds / 60).toString())
        else format(vocabulary.quietSeconds, seconds.toString())

    /**
     * `%1$s`-style substitution, done here rather than with `String.format` so a template with a
     * trailing empty argument (a duration has no unit) does not leave a double space behind.
     */
    private fun format(template: String, vararg args: String): String {
        var text = template
        for ((index, value) in args.withIndex()) text = text.replace("%${index + 1}\$s", value)
        return text.replace(Regex("\\s{2,}"), " ").trim()
    }
}
