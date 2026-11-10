package dev.leconnn.ioskeyboard.text

import java.util.Locale

/**
 * Shift with text selected changes its case, as in Gboard ("recapitalization", from Android's
 * LatinIME RecapitalizeStatus): each press moves the selection to the next of
 * original → all lowercase → Title Case → ALL CAPS → original, skipping any that wouldn't change
 * the text on screen. The selection stays selected so Shift can be pressed again.
 */
class Recapitalize(private val original: String, private val locale: Locale) {

    enum class Mode { ORIGINAL, LOWER, TITLE, UPPER }

    /** What the selection shows now. */
    var mode: Mode = initialMode()
        private set

    /** The text currently in the field for this selection. */
    val current get() = apply(mode)

    /** Moves to the next mode that looks different and returns its text. */
    fun next(): String {
        val shown = current
        var m = mode
        repeat(Mode.entries.size) {
            m = Mode.entries[(m.ordinal + 1) % Mode.entries.size]
            val t = apply(m)
            if (t != shown) {
                mode = m
                return t
            }
        }
        return shown
    }

    fun apply(m: Mode): String = when (m) {
        Mode.ORIGINAL -> original
        Mode.LOWER -> original.lowercase(locale)
        Mode.UPPER -> original.uppercase(locale)
        Mode.TITLE -> titleCase(original, locale)
    }

    /**
     * Text that is already all lowercase, Title Case or ALL CAPS starts in that mode (so the first
     * press goes to the next one, as in Gboard); anything else starts as the original.
     */
    private fun initialMode(): Mode = when (original) {
        original.lowercase(locale) -> Mode.LOWER
        original.uppercase(locale) -> Mode.UPPER
        titleCase(original, locale) -> Mode.TITLE
        else -> Mode.ORIGINAL
    }

    companion object {
        /** Longer selections are left alone (LatinIME's limit is 100 000 characters). */
        const val MAX_LENGTH = 100_000

        /** Every word with its first letter capitalised and the rest lowercase. */
        fun titleCase(s: String, locale: Locale): String {
            val out = StringBuilder(s.length)
            var startOfWord = true
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val ch = String(Character.toChars(cp))
                if (Character.isLetter(cp)) {
                    out.append(if (startOfWord) ch.uppercase(locale) else ch.lowercase(locale))
                    startOfWord = false
                } else {
                    out.append(ch)
                    // Apostrophes and hyphens join words ("don't", "well-known" stay one word,
                    // as in LatinIME); digits keep a word going too.
                    startOfWord = Character.isWhitespace(cp) || (!Character.isDigit(cp) && ch != "'" && ch != "’" && ch != "-")
                }
                i += Character.charCount(cp)
            }
            return out.toString()
        }
    }
}
