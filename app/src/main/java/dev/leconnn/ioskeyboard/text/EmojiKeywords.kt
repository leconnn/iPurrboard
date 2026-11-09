package dev.leconnn.ioskeyboard.text

/**
 * Word -> emoji for the suggestion bar ("pizza" offers 🍕), from assets/emoji_keywords.tsv
 * (Unicode CLDR annotations, built by tools/emoji/gen_emoji_keywords.py). Pure Kotlin for JVM tests.
 */
class EmojiKeywords private constructor(private val map: Map<String, List<String>>) {

    /** Emoji for [word] (any case), best first; empty when there are none. */
    fun forWord(word: String): List<String> = map[word.lowercase()].orEmpty()

    companion object {
        /** Several lists in order: a word's emoji from the first, then from the others. */
        fun combine(parts: List<EmojiKeywords>): EmojiKeywords {
            if (parts.size == 1) return parts[0]
            val map = HashMap<String, List<String>>()
            for (k in parts) for ((w, es) in k.map) map[w] = ((map[w] ?: emptyList()) + es).distinct().take(3)
            return EmojiKeywords(map)
        }

        /** Parses "word<TAB>emoji emoji ..." lines; '#' lines are comments. */
        fun parse(lines: Sequence<String>): EmojiKeywords {
            val map = HashMap<String, List<String>>()
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[line.substring(0, tab)] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
            }
            return EmojiKeywords(map)
        }
    }
}
