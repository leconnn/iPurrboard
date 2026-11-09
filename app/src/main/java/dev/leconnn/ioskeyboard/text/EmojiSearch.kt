package dev.leconnn.ioskeyboard.text

/**
 * Emoji search, as in the iOS emoji keyboard's "Search Emoji": every word typed must start a word
 * of the emoji's name or keywords ("red hea" finds ❤️). Matches on the name rank above keyword
 * matches, whole words above prefixes, and ties keep Unicode's order (common smileys first).
 * Data: assets/emoji_search.tsv (Unicode CLDR annotations, tools/emoji/gen_emoji_search.py).
 */
class EmojiSearch private constructor(private val entries: List<Entry>) {

    private class Entry(val emoji: String, val name: String, val nameWords: List<String>, val keywords: List<String>)

    /** Emoji matching [query], best first, at most [limit]. */
    fun search(query: String, limit: Int = 60): List<String> {
        val words = query.lowercase().replace('’', '\'').split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val q = words.joinToString(" ")
        val hits = ArrayList<Triple<Int, Int, String>>()
        for ((i, e) in entries.withIndex()) {
            var total = if (e.name == q) -10 else if (e.name.startsWith(q)) -5 else 0
            var ok = true
            for (w in words) {
                val s = score(e, w)
                if (s < 0) { ok = false; break }
                total += s
            }
            if (ok) hits.add(Triple(total, i, e.emoji))
        }
        return hits.sortedWith(compareBy({ it.first }, { it.second })).take(limit).map { it.third }
    }

    /** 0 name word, 1 start of a name word, 2 keyword, 3 start of a keyword, -1 no match. */
    private fun score(e: Entry, w: String): Int {
        var best = -1
        for (n in e.nameWords) {
            if (n == w) return 0
            if (n.startsWith(w)) best = 1
        }
        if (best >= 0) return best
        for (k in e.keywords) {
            if (k == w) return 2
            if (k.startsWith(w)) best = 3
        }
        return best
    }

    companion object {
        private val SPACES = Regex("""\s+""")

        /**
         * Several indexes (one per enabled keyboard language) as one: order and names from the
         * first, and each emoji also matches the other languages' names and keywords.
         */
        fun combine(parts: List<EmojiSearch>): EmojiSearch {
            if (parts.size == 1) return parts[0]
            val extra = HashMap<String, Pair<MutableList<String>, MutableList<String>>>()
            for (p in parts.drop(1)) for (e in p.entries) {
                val (n, k) = extra.getOrPut(e.emoji) { mutableListOf<String>() to mutableListOf() }
                n += e.nameWords; k += e.keywords
            }
            return EmojiSearch(parts[0].entries.map { e ->
                val x = extra[e.emoji] ?: return@map e
                Entry(e.emoji, e.name, (e.nameWords + x.first).distinct(), (e.keywords + x.second).distinct())
            })
        }

        /** Parses "emoji<TAB>name<TAB>keywords" lines; '#' lines are comments. */
        fun parse(lines: Sequence<String>): EmojiSearch {
            val list = ArrayList<Entry>(2048)
            for (line in lines) {
                if (line.isEmpty() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size < 2) continue
                val name = cols[1]
                list.add(Entry(cols[0], name, name.split(SPACES).map { it.trim(':', ',', '"') },
                    cols.getOrNull(2)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()))
            }
            return EmojiSearch(list)
        }
    }
}
