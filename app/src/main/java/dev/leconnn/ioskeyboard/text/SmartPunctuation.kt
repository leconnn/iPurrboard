package dev.leconnn.ioskeyboard.text

/** Replace [deleteBefore] characters before the cursor with [insert]. */
data class Edit(val deleteBefore: Int, val insert: String)

/**
 * iOS Smart Punctuation: curly quotes and apostrophes, and "--" to an em dash.
 * Pure function of the text before the cursor and the character being typed.
 */
object SmartPunctuation {
    private const val OPENERS = "([{“‘"

    fun transform(before: CharSequence, typed: String): Edit? = when (typed) {
        "\"" -> Edit(0, if (opensHere(before)) "“" else "”")
        "'" -> Edit(0, if (opensHere(before)) "‘" else "’")
        "-" -> if (before.endsWith("-")) Edit(1, "—") else null
        else -> null
    }

    /** A quote opens at the start of text, after whitespace, or after an opening bracket/quote. */
    private fun opensHere(before: CharSequence): Boolean {
        val c = before.lastOrNull() ?: return true
        return c.isWhitespace() || c in OPENERS
    }
}
