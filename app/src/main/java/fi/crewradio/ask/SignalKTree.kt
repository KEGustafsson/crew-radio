package fi.crewradio.ask

/**
 * A slice of the boat's Signal K tree, already parsed, with the two things an answer needs from
 * it: the value at a path, and how old it is.
 *
 * The server hands back nested objects whose leaves carry the value beside its timestamp:
 *
 * ```
 * { "headingMagnetic": { "value": 4.2771, "timestamp": "2026-09-08T09:14:02.101Z", "$source": "n2k.4" } }
 * ```
 *
 * so a leaf is recognised by having a `value` member, not by its position. This class is pure
 * Kotlin over plain maps — the Android side turns the response into maps — which is what lets the
 * whole read-and-age path be unit-tested with no server and no phone.
 *
 * A `*` in a path stands for an instance the boat names itself: `electrical.batteries.house`,
 * `tanks.fuel.0`, `propulsion.port`. The crew should not have to know which, so `*` resolves to
 * the preferred instance for that branch when there is one, and otherwise to the first child that
 * can satisfy the rest of the path — a boat with one engine never has to configure anything.
 */
class SignalKTree(private val root: Map<String, Any?>) {

    /** A value with the age the answer is allowed to judge it by. */
    data class Leaf(
        val value: Any?,
        /** Epoch milliseconds, or null when the server sent no timestamp. */
        val timestampMs: Long?,
        val source: String?,
        /** The path actually read, with `*` replaced by the instance that resolved it. */
        val path: String,
    )

    /**
     * The leaf at [path], or null when the boat does not publish it.
     *
     * [instances] names the preferred instance per branch (`"electrical.batteries"` to `"house"`),
     * as the crew set it in Settings; a branch with no entry resolves by itself.
     */
    fun read(path: String, instances: Map<String, String> = emptyMap()): Leaf? =
        walk(root, path.split('.'), emptyList(), instances)

    private fun walk(
        node: Any?,
        remaining: List<String>,
        consumed: List<String>,
        instances: Map<String, String>,
    ): Leaf? {
        if (node !is Map<*, *>) return null
        if (remaining.isEmpty()) return leafOf(node, consumed)
        val segment = remaining.first()
        val rest = remaining.drop(1)
        if (segment != "*") {
            val child = node[segment] ?: return null
            return walk(child, rest, consumed + segment, instances)
        }
        // An instance segment. Try the crew's choice for this branch first, then every child in a
        // stable order, and take the first that can satisfy what is left of the path: a boat with
        // one battery bank answers without anyone having named it.
        val keys = node.keys.filterIsInstance<String>().filterNot { it.startsWith("$") }
        val preferred = instances[consumed.joinToString(".")]
        val order = (listOfNotNull(preferred).filter { it in keys } + keys.sorted()).distinct()
        for (key in order) {
            walk(node[key], rest, consumed + key, instances)?.let { return it }
        }
        return null
    }

    /**
     * A node is a leaf when it carries a `value`. A branch that happens to hold a child called
     * `value` is not one — Signal K leaves always sit beside a timestamp or a source — but a
     * server that omits both still answers, with an unknown age, which [AskAnswer] treats as
     * stale rather than fresh.
     */
    private fun leafOf(node: Map<*, *>, consumed: List<String>): Leaf? {
        if (!node.containsKey("value")) return null
        return Leaf(
            value = node["value"],
            timestampMs = parseTimestamp(node["timestamp"] as? String),
            source = node["\$source"] as? String,
            path = consumed.joinToString("."),
        )
    }

    companion object {
        /**
         * Signal K timestamps are RFC 3339 in UTC. A server that sends something else gets its
         * value treated as ageless — which the staleness gate then refuses to speak — rather than
         * throwing on the answer path.
         */
        fun parseTimestamp(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            return try {
                java.time.Instant.parse(text).toEpochMilli()
            } catch (_: java.time.format.DateTimeParseException) {
                try {
                    java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
                } catch (_: java.time.format.DateTimeParseException) {
                    null
                }
            }
        }

        /** An empty tree: what a failed or refused request leaves behind. */
        val EMPTY = SignalKTree(emptyMap())
    }
}
