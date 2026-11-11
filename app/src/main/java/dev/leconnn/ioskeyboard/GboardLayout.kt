package dev.leconnn.ioskeyboard

import android.graphics.RectF

/** A key arrangement at its native 375 x 216 pt: rows 0..3 on each page, plus an optional number row. */
interface KeyLayoutSet {
    fun letters(numberRow: Boolean): List<Key>
    val numbers: List<Key>
    val symbols: List<Key>
    /** The row of digits shown above the letters when Number Row is on (row 0, 0..44 pt). */
    fun numberRow(): List<Key>
}

/**
 * Gboard's English (QWERTY) arrangement drawn with iOS keycaps: Gboard's widths in percent of the
 * keyboard (from its layout resources: letter keys 10 %, shift / delete / ?123 / return 15 %,
 * comma / emoji / period 10 %, space taking the rest), its number and symbol pages, and its
 * long-press symbol hints. Row positions and heights stay on the iOS grid so both styles share
 * the same keyboard height and swipe decoding.
 */
object GboardLayouts : KeyLayoutSet {
    private const val UNIT = Geometry.WIDTH / 100f
    private const val H_INSET = 2.5f
    private const val CAP_H = 44f
    private val rowCenter = floatArrayOf(30f, 84f, 138f, 192f)

    /** Keycap for a frame from [leftPct] spanning [widthPct] percent of the width, on [row]. */
    private fun cap(leftPct: Float, widthPct: Float, row: Int, centers: FloatArray = rowCenter, h: Float = CAP_H) = RectF(
        leftPct * UNIT + H_INSET, centers[row] - h / 2, (leftPct + widthPct) * UNIT - H_INSET, centers[row] + h / 2,
    )

    private fun chars(s: String, row: Int, startPct: Float, hints: String? = null, labels: Map<Char, String> = emptyMap()) =
        s.mapIndexed { i, c ->
            Key(KeyKind.CHAR, c.toString(), row, cap(startPct + i * 10f, 10f, row), labels[c] ?: c.toString(),
                hints?.getOrNull(i)?.toString())
        }

    private fun bottomRow(planeKey: Key) = listOf(
        planeKey,
        Key(KeyKind.CHAR, ",", 3, cap(15f, 10f, 3)),
        Key(KeyKind.EMOJI, "", 3, cap(25f, 10f, 3)),
        Key(KeyKind.SPACE, " ", 3, cap(35f, 40f, 3)),
        Key(KeyKind.CHAR, ".", 3, cap(75f, 10f, 3)),
        Key(KeyKind.RETURN, "\n", 3, cap(85f, 15f, 3)),
    )

    private fun leftKey(kind: KeyKind, label: String, row: Int) = Key(kind, label, row, cap(0f, 15f, row))
    private fun delete() = Key(KeyKind.DELETE, "", 2, cap(85f, 15f, 2))

    // Hints on the top letter row: digits normally, symbols when the number row already has the digits.
    private const val TOP_HINTS = "1234567890"
    private const val TOP_HINTS_NUMBER_ROW = "%\\|=[]<>{}"
    private const val MIDDLE_HINTS = "@#\$_&-+()"
    private const val BOTTOM_HINTS = "*\"':;!?"

    /** Gboard's long-press symbol for a letter key, also used for the iOS layout's Symbol Hints. */
    fun hintFor(letter: Char, numberRow: Boolean): String? {
        val rows = listOf(
            "qwertyuiop" to if (numberRow) TOP_HINTS_NUMBER_ROW else TOP_HINTS,
            "asdfghjkl" to MIDDLE_HINTS,
            "zxcvbnm" to BOTTOM_HINTS,
        )
        for ((letters, hints) in rows) {
            val i = letters.indexOf(letter.lowercaseChar())
            if (i >= 0) return hints.getOrNull(i)?.toString()
        }
        return null
    }

    override fun letters(numberRow: Boolean): List<Key> =
        chars("qwertyuiop", 0, 0f, if (numberRow) TOP_HINTS_NUMBER_ROW else TOP_HINTS) +
            chars("asdfghjkl", 1, 5f, MIDDLE_HINTS) +
            listOf(leftKey(KeyKind.SHIFT, "", 2)) +
            chars("zxcvbnm", 2, 15f, BOTTOM_HINTS) +
            listOf(delete()) +
            bottomRow(leftKey(KeyKind.TO_NUMBERS, "123", 3))

    override val numbers: List<Key> =
        chars("1234567890", 0, 0f) +
            chars("@#\$_&-+()/", 1, 0f) +
            listOf(leftKey(KeyKind.TO_SYMBOLS, "=\\<", 2)) +
            chars("*\"':;!?", 2, 15f) +
            listOf(delete()) +
            bottomRow(leftKey(KeyKind.TO_LETTERS, "ABC", 3))

    override val symbols: List<Key> =
        chars("~`|•√π÷×¶∆", 0, 0f) +
            chars("£¢€¥^°={}\\", 1, 0f) +
            listOf(leftKey(KeyKind.TO_NUMBERS, "123", 2)) +
            chars("%©®™✓[]", 2, 15f) +
            listOf(delete()) +
            bottomRow(leftKey(KeyKind.TO_LETTERS, "ABC", 3))

    override fun numberRow(): List<Key> = "1234567890".mapIndexed { i, c ->
        // 42 pt keys (letters are 44) ending 10 pt above the q row, as between letter rows.
        Key(KeyKind.CHAR, c.toString(), 0, RectF(i * 10f * UNIT + H_INSET, 7f, (i + 1) * 10f * UNIT - H_INSET, 49f))
    }
}
