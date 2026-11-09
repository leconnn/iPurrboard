package dev.leconnn.ioskeyboard.text

/**
 * Built-in next words (assets/dict/bigrams_en.tsv, from Tatoeba's English sentences): for a
 * common word, the words most often typed after it, best first. Words the user has typed after
 * it (UserWords) come before these.
 */
class Bigrams private constructor(private val next: HashMap<String, List<String>>) {

    /** Likely words after [prev] (any case), best first; empty if unknown. */
    fun after(prev: String?): List<String> = prev?.let { next[it.lowercase().replace('’', '\'')] }.orEmpty()

    companion object {
        /** Several lists in order: the first's next words first, then the others' not already there. */
        fun combine(parts: List<Bigrams>): Bigrams {
            if (parts.size == 1) return parts[0]
            val map = HashMap<String, List<String>>()
            for (b in parts) for ((k, v) in b.next) {
                val old = map[k]
                map[k] = if (old == null) v else (old + v).distinctBy { it.lowercase() }
            }
            return Bigrams(map)
        }

        fun parse(lines: Sequence<String>): Bigrams {
            val map = HashMap<String, List<String>>(16384)
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[line.substring(0, tab).lowercase()] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
            }
            return Bigrams(map)
        }
    }
}
