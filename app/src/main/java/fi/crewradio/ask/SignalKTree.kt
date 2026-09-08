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
 *
 * A boat with two of something is why [read] also takes a [Quantity.Role]. Without one the rule
 * above still holds and [Leaf.ambiguous] tells the answer there was a choice, so it can say which
 * instance it read. With one, only that instance will do: a role that matches nothing reads as
 * absent rather than as its sister, because "starboard engine 2100 rpm" spoken off the port
 * tachometer is exactly the kind of confident wrong answer this file is careful about.
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
        /** The instance `*` resolved to (`house`, `1`), or null when the path had no `*`. */
        val instance: String? = null,
        /** True when the branch held more than one instance that could have answered. */
        val ambiguous: Boolean = false,
    )

    /**
     * The leaf at [path], or null when the boat does not publish it.
     *
     * [instances] names the preferred instance per branch (`"electrical.batteries"` to `"house"`)
     * and per branch and role (`"propulsion:starboard"` to `"1"`), as the crew set it in Settings;
     * a branch with no entry resolves by itself. [role] is the instance the question asked for by
     * name, and when there is one nothing else may answer for it.
     */
    fun read(
        path: String,
        instances: Map<String, String> = emptyMap(),
        role: Quantity.Role? = null,
    ): Leaf? = walk(root, path.split('.'), emptyList(), instances, role)

    private fun walk(
        node: Any?,
        remaining: List<String>,
        consumed: List<String>,
        instances: Map<String, String>,
        role: Quantity.Role?,
    ): Leaf? {
        if (node !is Map<*, *>) return null
        if (remaining.isEmpty()) return leafOf(node, consumed)
        val segment = remaining.first()
        val rest = remaining.drop(1)
        if (segment != "*") {
            val child = node[segment] ?: return null
            return walk(child, rest, consumed + segment, instances, role)
        }
        // An instance segment. Only the children that can satisfy what is left of the path count
        // — a tank that publishes a capacity but no level is not an answer — and how many of those
        // there are is what tells the answer whether there was a choice worth naming out loud.
        val branch = consumed.joinToString(".")
        val keys = node.keys.filterIsInstance<String>().filterNot { it.startsWith("$") }.sorted()
        val candidates = keys.filter { walk(node[it], rest, consumed + it, instances, role) != null }
        if (candidates.isEmpty()) return null
        val chosen = choose(candidates, branch, instances, role) ?: return null
        val leaf = walk(node[chosen], rest, consumed + chosen, instances, role) ?: return null
        // Two `*` in one path is not a shape any quantity has, and if it ever were, the outer one
        // is the instance the crew names, so one already stamped is left alone.
        return if (leaf.instance != null) leaf else leaf.copy(instance = chosen, ambiguous = candidates.size > 1)
    }

    /**
     * Which of [candidates] answers. Without a [role] that is the crew's choice for the branch and
     * otherwise the first, as it has always been. With one it is the crew's line for that role,
     * else a candidate the role knows by name, else a candidate that is nothing but the role's
     * instance number — and if none of those, nothing at all.
     */
    private fun choose(
        candidates: List<String>,
        branch: String,
        instances: Map<String, String>,
        role: Quantity.Role?,
    ): String? {
        if (role == null) return instances[branch]?.takeIf { it in candidates } ?: candidates.first()
        instances["$branch:${role.key}"]?.takeIf { it in candidates }?.let { return it }
        candidates.firstOrNull { role.matches(it) }?.let { return it }
        val number = role.instanceNumber ?: return null
        return candidates.firstOrNull { it.toIntOrNull() == number }
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
