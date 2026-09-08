package fi.crewradio.ask

/**
 * What the crew just asked for: a transcript in, a list of [Quantity] out.
 *
 * This is not natural-language understanding and does not want to be. The answer set is closed,
 * so the question set is closed too: a table of trigger phrases, matched longest first, over the
 * words the recogniser heard. That is what makes the whole feature testable without a microphone.
 *
 * Three things it does that a plain `contains` would get wrong:
 *
 *  * **Longest first, then consumed.** "wind speed" must not also answer the boat's speed, so a
 *    trigger that matches takes its words out of play before the shorter triggers are tried.
 *  * **Every hypothesis, not just the best one.** Android's recogniser returns an n-best list and
 *    the top entry is often the wrong one of a near-homophone pair ("speed over ground" heard as
 *    "speed of ground"). Matching against all of them costs nothing and recovers most of those.
 *  * **A little misspelling.** Dictation is not exact, so a word of five letters or more matches
 *    within one edit. Shorter words are matched exactly: at four letters an edit of one turns
 *    "wind" into "mind" and "find", and a wrong answer is worse than "say again".
 *
 * The phrases themselves live in `res/values/arrays.xml`, so a translator reaches them in the same
 * pass as everything else and a crew can add their own from Settings; [parse] turns those lines
 * into triggers. The catalogue they point at is [Quantity.ALL].
 */
class AskIntents(triggers: List<Trigger>) {

    /** One spoken phrase and the quantity it asks for. */
    data class Trigger(val quantityId: String, val words: List<String>)

    /** What a transcript turned out to mean. [quantities] is empty when nothing was recognised. */
    data class Match(
        val quantities: List<Quantity>,
        /** The hypothesis that produced the match, for the sheet to show; the first one when nothing matched. */
        val transcript: String,
    )

    /** Longest phrase first: "wind speed" is tried before "speed". */
    private val triggers: List<Trigger> = triggers.sortedByDescending { it.words.size }

    /**
     * The quantities asked for, in the order they were spoken, from the first hypothesis that
     * yields any. [wakeWord] (the boat's name) is dropped wherever it appears, so both
     * "Northstar, depth" and "depth please Northstar" work.
     */
    fun match(hypotheses: List<String>, wakeWord: String? = null): Match {
        if (hypotheses.isEmpty()) return Match(emptyList(), "")
        for (hypothesis in hypotheses) {
            val found = matchOne(hypothesis, wakeWord)
            if (found.isNotEmpty()) return Match(found, hypothesis.trim())
        }
        return Match(emptyList(), hypotheses.first().trim())
    }

    private fun matchOne(transcript: String, wakeWord: String?): List<Quantity> {
        val words = tokenise(transcript).filterNot { isWakeWord(it, wakeWord) }
        if (words.isEmpty()) return emptyList()
        val taken = BooleanArray(words.size)
        // Position of the first word each quantity was recognised from, so the answer comes back
        // in the order it was asked: "heading and speed" is heard as heading, then speed.
        val at = LinkedHashMap<String, Int>()
        for (trigger in triggers) {
            var i = 0
            while (i + trigger.words.size <= words.size) {
                if (matchesAt(words, taken, i, trigger.words)) {
                    for (k in i until i + trigger.words.size) taken[k] = true
                    at.putIfAbsent(trigger.quantityId, i)
                    i += trigger.words.size
                } else {
                    i++
                }
            }
        }
        return at.entries
            .sortedBy { it.value }
            .mapNotNull { Quantity.byId(it.key) }
            .take(MAX_QUANTITIES)
    }

    private fun matchesAt(words: List<String>, taken: BooleanArray, start: Int, phrase: List<String>): Boolean {
        for (k in phrase.indices) {
            if (taken[start + k]) return false
            if (!looseEquals(words[start + k], phrase[k])) return false
        }
        return true
    }

    companion object {
        /** More than this in one breath is a misfire, not a question, and would be a paragraph to hear. */
        const val MAX_QUANTITIES = 4

        /** Words of at least this length are matched within one edit; shorter ones exactly. */
        const val FUZZY_FROM = 5

        /**
         * Trigger lines as `res/values/arrays.xml` holds them: an id, then its phrases, separated
         * by `|`. A line whose id is not in [Quantity.ALL] is skipped rather than failing the
         * screen — a crew's own line with a typo costs that one phrase, not the feature.
         */
        fun parse(lines: List<String>): List<Trigger> = buildList {
            for (line in lines) {
                val parts = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                val id = parts[0]
                if (Quantity.byId(id) == null) continue
                for (phrase in parts.drop(1)) {
                    val words = tokenise(phrase)
                    if (words.isNotEmpty()) add(Trigger(id, words))
                }
            }
        }

        /** Lower case, punctuation and digits' separators dropped, runs of space collapsed. */
        fun tokenise(text: String): List<String> =
            text.lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }

        /** Exact for a short word, within one edit for a long one. */
        fun looseEquals(heard: String, wanted: String): Boolean {
            if (heard == wanted) return true
            if (wanted.length < FUZZY_FROM) return false
            if (kotlin.math.abs(heard.length - wanted.length) > 1) return false
            return editDistanceAtMostOne(heard, wanted)
        }

        /**
         * The boat's name as the recogniser might have heard it: within one edit for a short name
         * and two for a long one, because a name is the word dictation is least likely to know.
         */
        fun isWakeWord(heard: String, wakeWord: String?): Boolean {
            if (wakeWord.isNullOrBlank()) return false
            for (part in tokenise(wakeWord)) {
                if (heard == part) return true
                if (part.length >= FUZZY_FROM && kotlin.math.abs(heard.length - part.length) <= 1 &&
                    editDistanceAtMostOne(heard, part)
                ) return true
                if (part.length >= 8 && editDistanceAtMostTwo(heard, part)) return true
            }
            return false
        }

        /** True when at most one insertion, deletion or substitution turns one into the other. */
        private fun editDistanceAtMostOne(a: String, b: String): Boolean = withinEdits(a, b, 1)

        private fun editDistanceAtMostTwo(a: String, b: String): Boolean = withinEdits(a, b, 2)

        /**
         * Levenshtein distance capped at [max]: the strings are single words, so the band is two
         * rows of at most a few dozen cells and there is no reason to be cleverer.
         */
        private fun withinEdits(a: String, b: String, max: Int): Boolean {
            if (kotlin.math.abs(a.length - b.length) > max) return false
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                var best = current[0]
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                    if (current[j] < best) best = current[j]
                }
                if (best > max) return false          // every path through this row is already too dear
                val swap = previous; previous = current; current = swap
            }
            return previous[b.length] <= max
        }
    }
}
