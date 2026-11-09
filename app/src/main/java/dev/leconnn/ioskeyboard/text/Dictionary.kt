package dev.leconnn.ioskeyboard.text

/**
 * Word list with frequencies (assets/dict/en.tsv, built by tools/dict/build_dict.py).
 * [keys] are lowercase and sorted, so words sharing a prefix are adjacent; [words] keep the
 * display form ("I", "Monday", "don't").
 */
class Dictionary private constructor(val keys: Array<String>, val words: Array<String>, val freq: IntArray) {

    val size get() = keys.size

    fun indexOf(word: String): Int = keys.binarySearch(word.lowercase()).let { if (it >= 0) it else -1 }

    fun contains(word: String) = indexOf(word) >= 0

    fun frequency(word: String): Int = indexOf(word).let { if (it >= 0) freq[it] else 0 }

    fun displayForm(word: String): String? = indexOf(word).let { if (it >= 0) words[it] else null }

    /** Index range [from, to) of keys starting with [prefix] (lowercase). */
    fun prefixRange(prefix: String): IntRange {
        val p = prefix.lowercase()
        val from = lowerBound(p)
        var lo = from
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].startsWith(p)) lo = mid + 1 else hi = mid
        }
        return from until lo
    }

    /** Most frequent words that extend [prefix] (excluding the prefix itself). */
    fun completions(prefix: String, n: Int): List<String> {
        if (prefix.isEmpty() || n <= 0) return emptyList()
        // One pass keeping the n most frequent (n is tiny; ranges can hold thousands of words).
        val top = IntArray(n)
        var count = 0
        for (i in prefixRange(prefix)) {
            if (keys[i].length <= prefix.length) continue
            if (count == n && freq[i] <= freq[top[n - 1]]) continue
            var at = if (count < n) count++ else n - 1
            while (at > 0 && freq[top[at - 1]] < freq[i]) { top[at] = top[at - 1]; at-- }
            top[at] = i
        }
        return List(count) { words[top[it]] }
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /**
         * One dictionary from several, each with a weight on its counts (the current keyboard's
         * language 1, other enabled ones less). A word in more than one keeps its highest
         * weighted count and that list's spelling.
         */
        fun merge(parts: List<Pair<Dictionary, Double>>): Dictionary {
            if (parts.size == 1 && parts[0].second == 1.0) return parts[0].first
            val best = HashMap<String, Pair<String, Int>>(parts.sumOf { it.first.size })
            for ((d, weight) in parts) {
                for (i in 0 until d.size) {
                    val f = (d.freq[i] * weight).toInt().coerceAtLeast(1)
                    val old = best[d.keys[i]]
                    if (old == null || f > old.second) best[d.keys[i]] = d.words[i] to f
                }
            }
            val keys = best.keys.sorted()
            return Dictionary(keys.toTypedArray(), Array(keys.size) { best[keys[it]]!!.first },
                IntArray(keys.size) { best[keys[it]]!!.second })
        }

        /** Parses "word<TAB>count" lines; '#' lines are comments. */
        fun parse(lines: Sequence<String>): Dictionary {
            val entries = lines.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else Triple(line.substring(0, tab).lowercase(), line.substring(0, tab),
                    line.substring(tab + 1).trim().toIntOrNull() ?: 0)
            }.sortedBy { it.first }.toList()
            return Dictionary(
                Array(entries.size) { entries[it].first },
                Array(entries.size) { entries[it].second },
                IntArray(entries.size) { entries[it].third },
            )
        }
    }
}
