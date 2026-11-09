package dev.leconnn.ioskeyboard.text

import kotlin.math.ln
import kotlin.math.min

/**
 * Finds dictionary words close to a typed word and decides whether to autocorrect.
 *
 * Distance is a weighted Damerau-Levenshtein (optimal string alignment) from the typed word to a
 * candidate: substitution 0.5 for neighbouring keys and 1.0 otherwise, transposition 0.6, extra or
 * missed letter 0.9, missed apostrophe 0.3, missed second letter of a double ("helo") 0.1. Candidates are scored ln(freq + 1) - 3 * cost. The
 * dictionary is walked in sorted order so DP rows are shared across a common prefix, and a whole
 * prefix is skipped once its best cost exceeds the limit.
 *
 * Following Android's open-source keyboard (Gboard's ancestor), three refinements:
 *  - Spatial model: when the tap positions are known ([Touch]), substituting a letter costs by how
 *    far the tap was from that letter's key (a tap on the edge towards a key makes it cheap),
 *    instead of a fixed neighbour / non-neighbour cost.
 *  - Unfinished words: one or two letters missing at the end cost less (0.3), so "worl" reads as
 *    "world" rather than a mistyped "work".
 *  - Context: words often typed after the previous word (learned pairs) score higher.
 */
class Corrector(private val dict: Dictionary, private val extraWords: (String) -> Int = { 0 }) {
    /** Lowercase words never offered as a correction (Block Offensive Words). */
    @Volatile var blocked: Set<String> = emptySet()


    data class Candidate(val word: String, val cost: Float, val score: Double)

    /** Where the tap for one typed letter landed (see [KeyProximity.touch]). */
    data class Touch(val x: Float, val y: Float)

    fun score(freq: Int, cost: Float) = ln(freq + 1.0) - COST_WEIGHT * cost

    /**
     * Candidates within [maxCost], best first (the typed word itself is excluded). [touches] are
     * the tap points per typed letter, if known; words in [context] get [CONTEXT_BONUS].
     */
    fun candidates(
        typed: String, maxCost: Float = MAX_COST, limit: Int = 8,
        touches: List<Touch?>? = null, context: Set<String> = emptySet(),
    ): List<Candidate> {
        // Taps per typed letter, or null when unknown (paste, swipe, hardware keys).
        val taps = touches?.takeIf { it.size == typed.length }
        val t = typed.lowercase()
        if (t.isEmpty()) return emptyList()
        val m = t.length
        val maxLen = m + MAX_COST.toInt() + 1
        val rows = Array(maxLen + 1) { FloatArray(m + 1) }
        for (j in 0..m) rows[0][j] = j * EXTRA
        val out = ArrayList<Candidate>()
        val keys = dict.keys
        var i = 0
        var prev = ""
        while (i < keys.size) {
            val k = keys[i]
            if (k.length > maxLen || k.length < m - 2) { prev = ""; i++; continue }
            // Rows up to the shared prefix with the previous word are still valid.
            var d = commonPrefix(prev, k)
            var pruned = -1
            while (d < k.length) {
                fill(rows, d + 1, k, t, taps)
                if (rows[d + 1].min() > maxCost) { pruned = d + 1; break }
                d++
            }
            if (pruned > 0) {
                // No word starting with k[0, pruned) can get under the limit: jump past them all.
                val prefix = k.substring(0, pruned)
                var j = i + 1
                while (j < keys.size && keys[j].startsWith(prefix)) j++
                prev = k.substring(0, pruned - 1)
                i = j
                continue
            }
            val cost = rows[k.length][m]
            if (cost <= maxCost && k != t && k !in blocked) {
                val bonus = if (k in context) CONTEXT_BONUS else 0.0
                out.add(Candidate(dict.words[i], cost, score(dict.freq[i], cost) + bonus))
            }
            prev = k
            i++
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    /**
     * The word to replace [typed] with when a word boundary is typed, or null to leave it.
     * Corrects when the typed word is unknown (or very rare) and a candidate clearly beats it.
     */
    fun autocorrect(
        typed: String, rejected: Set<String> = emptySet(),
        touches: List<Touch?>? = null, context: Set<String> = emptySet(),
    ): String? {
        val t = typed.lowercase()
        if (t.isEmpty() || t in rejected || typed.any { !it.isLetter() && it != '\'' }) return null
        val known = dict.indexOf(t)
        if (known >= 0) {
            // Fix capitalisation of words that are always capitalised ("i" -> "I", "monday").
            val shown = dict.words[known]
            if (shown != t && shown[0].isUpperCase() && typed[0].isLowerCase()) return shown
            // A rare dictionary word can still be a typo of a much more common one ("helo").
            if (dict.freq[known] >= RARE || t.length < 4) return null
            val best = candidates(typed, 1.0f, 1, touches, context).firstOrNull() ?: return null
            return if (best.score - score(dict.freq[known], 0f) >= MIN_GAIN_KNOWN) matchCase(typed, best.word) else null
        }
        if (extraWords(t) > 0) return null
        if (t.length < 2) return null
        // A contraction typed without its apostrophe ("dont", "im") always means the contraction.
        for (p in 1 until t.length) {
            val withApostrophe = t.substring(0, p) + "'" + t.substring(p)
            dict.displayForm(withApostrophe)?.let { return matchCase(typed, it) }
        }
        val typedScore = score(0, 0f)
        val limit = if (t.length <= 3) 1.0f else MAX_COST
        val best = candidates(typed, limit, 3, touches, context).firstOrNull() ?: return null
        if (best.score - typedScore < MIN_GAIN) return null
        return matchCase(typed, best.word)
    }

    private fun fill(rows: Array<FloatArray>, d: Int, k: String, t: String, taps: List<Touch?>?) {
        val c = k[d - 1]
        val row = rows[d]
        val up = rows[d - 1]
        row[0] = d * EXTRA
        for (j in 1..t.length) {
            val tc = t[j - 1]
            val tap = taps?.get(j - 1)
            val sub = when {
                tc == c -> 0f
                tap != null -> spatialCost(tap, c)
                KeyProximity.adjacent(tc, c) -> NEAR
                else -> FAR
            }
            // Candidate has a letter the typist missed; doubles and apostrophes are the easiest to
            // miss, then the last letter or two of a word typed a little too short.
            val missed = when {
                c == '\'' -> APOSTROPHE
                d > 1 && k[d - 2] == c -> DOUBLE
                j == t.length && d - t.length in 1..2 -> TRAILING
                else -> EXTRA
            }
            var v = min(up[j - 1] + sub, min(up[j] + missed, row[j - 1] + EXTRA))
            if (d > 1 && j > 1 && c == t[j - 2] && k[d - 2] == tc) v = min(v, rows[d - 2][j - 2] + SWAP)
            row[j] = v
        }
    }

    /**
     * Substitution cost from where the tap landed: grows with the squared distance (in key
     * pitches) to the candidate letter's key, between [MIN_SPATIAL] and [FAR]. A tap at a key's
     * centre makes a neighbour cost about 0.6; a tap on the shared edge, about 0.15.
     */
    private fun spatialCost(tap: Touch, c: Char): Float {
        val d = KeyProximity.distanceFrom(tap.x, tap.y, c) ?: return FAR
        return (SPATIAL * d * d).coerceIn(MIN_SPATIAL, FAR)
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = min(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    companion object {
        const val NEAR = 0.5f
        const val DOUBLE = 0.1f
        const val FAR = 1.0f
        const val SWAP = 0.6f
        const val EXTRA = 0.9f
        const val APOSTROPHE = 0.3f
        const val TRAILING = 0.3f
        const val SPATIAL = 0.6f
        const val MIN_SPATIAL = 0.15f
        /** Score bonus for a candidate often typed after the previous word. */
        const val CONTEXT_BONUS = 1.0
        const val MAX_COST = 2.0f
        const val COST_WEIGHT = 3.0
        const val MIN_GAIN = 2.0
        /** Known words this rare may still be typos of a much more frequent neighbour. */
        const val RARE = 2000
        const val MIN_GAIN_KNOWN = 3.0

        /** Keeps the typed word's capitalisation: "Teh" -> "The", "TEH" -> "THE", "teh" -> "the". */
        fun matchCase(typed: String, word: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }
}
