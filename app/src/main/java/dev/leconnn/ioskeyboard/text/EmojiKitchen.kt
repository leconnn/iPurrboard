package dev.leconnn.ioskeyboard.text

import java.io.DataInputStream
import java.io.InputStream

/**
 * Emoji Kitchen: which pairs of emoji have a mash-up sticker (Gboard's hand-drawn combinations)
 * and where its picture is. The index is assets/emoji_kitchen.dat (tools/emoji/build_kitchen.py);
 * the pictures are Google's, loaded from gstatic.com only when shown, and never included.
 */
class EmojiKitchen private constructor(
    private val dates: Array<String>,
    /** Code points of each emoji ("1f602", "2764-fe0f"), most popular first. */
    private val codes: Array<String>,
    /** For each emoji, its partners (most popular first) and the matching pair's date and order. */
    private val partners: Array<IntArray>,
    private val partnerDate: Array<ByteArray>,
    /** True where the pair is stored with this emoji on the right (the picture's file name order). */
    private val partnerFlipped: Array<BooleanArray>,
) {
    /** One sticker: its picture address and the two emoji it mixes. */
    data class Sticker(val url: String, val left: String, val right: String)

    private val byEmoji = HashMap<String, Int>(codes.size * 2).also { m ->
        codes.forEachIndexed { i, c -> m[key(emojiOf(c))] = i }
    }

    /** True if [emoji] can be mixed at all. */
    fun has(emoji: String) = byEmoji.containsKey(key(emoji))

    /**
     * Stickers for [emoji], best first: mixed with [other] first if that pair exists (two emoji
     * typed in a row), then with itself, then with its most popular partners; at most [limit].
     */
    fun stickers(emoji: String, other: String? = null, limit: Int = 16): List<Sticker> {
        val a = byEmoji[key(emoji)] ?: return emptyList()
        val out = ArrayList<Sticker>(limit)
        val b = other?.let { byEmoji[key(it)] }
        fun add(p: Int) {
            val i = partners[a].indexOf(p)
            if (i < 0 || out.size >= limit) return
            val s = sticker(a, i)
            if (out.none { it.url == s.url }) out.add(s)
        }
        if (b != null) add(b)
        add(a)
        for (p in partners[a]) { if (out.size >= limit) break; add(p) }
        return out
    }

    private fun sticker(a: Int, i: Int): Sticker {
        val p = partners[a][i]
        val (l, r) = if (partnerFlipped[a][i]) p to a else a to p
        val date = dates[partnerDate[a][i].toInt() and 0xFF]
        val lc = codes[l].replace("-", "-u")
        val rc = codes[r].replace("-", "-u")
        return Sticker("$BASE/$date/u$lc/u${lc}_u$rc.png", emojiOf(codes[l]), emojiOf(codes[r]))
    }

    companion object {
        private const val BASE = "https://www.gstatic.com/android/keyboard/emojikitchen"

        /** An emoji compared without its variation selector (❤ and ❤️ are the same). */
        private fun key(emoji: String) = emoji.replace("️", "")

        private fun emojiOf(code: String) = buildString {
            for (cp in code.split('-')) appendCodePoint(cp.toInt(16))
        }

        fun read(input: InputStream): EmojiKitchen {
            val d = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "EKIT" && d.readUnsignedShort() == 1) { "not an Emoji Kitchen index" }
            val dates = Array(d.readUnsignedByte()) { ByteArray(8).also { b -> d.readFully(b) }.toString(Charsets.US_ASCII) }
            val codes = Array(d.readUnsignedShort()) {
                ByteArray(d.readUnsignedByte()).also { b -> d.readFully(b) }.toString(Charsets.US_ASCII)
            }
            val n = d.readInt()
            val left = IntArray(n)
            val right = IntArray(n)
            val date = ByteArray(n)
            val count = IntArray(codes.size)
            for (i in 0 until n) {
                left[i] = d.readUnsignedShort()
                right[i] = d.readUnsignedShort()
                date[i] = d.readByte()
                count[left[i]]++
                if (right[i] != left[i]) count[right[i]]++
            }
            // Partner lists per emoji, then sorted by the partner's popularity.
            val partners = Array(codes.size) { IntArray(count[it]) }
            val dateOf = Array(codes.size) { ByteArray(count[it]) }
            val flipped = Array(codes.size) { BooleanArray(count[it]) }
            val fill = IntArray(codes.size)
            for (i in 0 until n) {
                val l = left[i]
                val r = right[i]
                partners[l][fill[l]] = r; dateOf[l][fill[l]] = date[i]; flipped[l][fill[l]] = false; fill[l]++
                if (r != l) { partners[r][fill[r]] = l; dateOf[r][fill[r]] = date[i]; flipped[r][fill[r]] = true; fill[r]++ }
            }
            for (e in codes.indices) {
                val p = partners[e]
                val dt = dateOf[e]
                val fl = flipped[e]
                val order = p.indices.sortedBy { p[it] }
                partners[e] = IntArray(order.size) { p[order[it]] }
                dateOf[e] = ByteArray(order.size) { dt[order[it]] }
                flipped[e] = BooleanArray(order.size) { fl[order[it]] }
            }
            return EmojiKitchen(dates, codes, partners, dateOf, flipped)
        }
    }
}
