package dev.leconnn.ioskeyboard

import android.graphics.RectF

/**
 * Keyboard geometry in iOS points on a 375 x 216 keyboard: the `iPhone-PortraitChoco` layouts
 * (Face ID iPhones) decoded from TextInputUI's KBLayouts_iPhone.dat. Each key there has a frame
 * (touch area, tiling the row) and a padded frame. iOS draws the keycap 2 pt inside the padded
 * frame, which gives the familiar 31.5 x 42 letter keys with 6 pt / 12 pt gaps; that inset is
 * from screenshots, not from the data. Width scales proportionally on other screens.
 */
object Geometry {
    const val WIDTH = 375f
    const val HEIGHT = 216f
    const val KEY_H = 42f
    private const val PITCH = 37.5f
    private const val CAP_INSET = 2f

    /** Rows: frames start at y = 7, 61, 115, 169 and are 46 tall. */
    val ROW_CENTER_Y = floatArrayOf(30f, 84f, 138f, 192f)

    /** Keycap for a padded frame starting at [x] with width [w]. */
    fun cap(x: Float, w: Float, row: Int): RectF {
        val cy = ROW_CENTER_Y[row]
        return RectF(x + CAP_INSET, cy - KEY_H / 2, x + w - CAP_INSET, cy + KEY_H / 2)
    }

    /** Padded frames of a ten-on-ten style row: 35.5 wide on a 37.5 pitch from [firstX]. */
    fun letterCap(i: Int, firstX: Float, row: Int) = cap(firstX + i * PITCH, 35.5f, row)
}

enum class KeyKind { CHAR, SHIFT, DELETE, TO_NUMBERS, TO_SYMBOLS, TO_LETTERS, GLOBE, EMOJI, SPACE, RETURN }

enum class Plane { LETTERS, NUMBERS, SYMBOLS }

class Key(
    val kind: KeyKind,
    /** Lowercase output for letters, literal output otherwise. */
    val text: String,
    val row: Int,
    val rect: RectF,
    /** Keycap label when it differs from the output (iOS shows curly quotes but types straight ones). */
    val label: String = text,
    /** Small symbol in the keycap corner, typed by a long press (Gboard layout). */
    val hint: String? = null,
) {
    /** The same key with its frame moved/scaled vertically: y' = (y + [dy]) * [sy]. */
    fun moved(dy: Float, sy: Float, row: Int = this.row) =
        Key(kind, text, row, RectF(rect.left, (rect.top + dy) * sy, rect.right, (rect.bottom + dy) * sy), label, hint)

    val isLetter get() = kind == KeyKind.CHAR && text.length == 1 && text[0].isLetter()
    /** Grey "function" keycap (shift, delete, 123, globe, return). */
    val isFunction get() = kind != KeyKind.CHAR && kind != KeyKind.SPACE
}

private fun charRow(chars: String, row: Int, firstX: Float, labels: Map<Char, String> = emptyMap()): List<Key> =
    chars.mapIndexed { i, c ->
        Key(KeyKind.CHAR, c.toString(), row, Geometry.letterCap(i, firstX, row), labels[c] ?: c.toString())
    }

/** The 123 / #+= third row: five punctuation keys, padded frames 50.5 wide on a 52.5 pitch from x = 57. */
private fun punctuationRow(row: Int): List<Key> =
    listOf(".", ",", "?", "!", "'").mapIndexed { i, s ->
        Key(KeyKind.CHAR, s, row, Geometry.cap(57f + i * 52.5f, 50.5f, row), if (s == "'") "’" else s)
    }

// Padded frames from the Shift-Delete and Row4 control-key geometry lists.
private fun shiftLike(kind: KeyKind, text: String) = Key(kind, text, 2, Geometry.cap(1f, 46f, 2))
private fun deleteKey() = Key(KeyKind.DELETE, "", 2, Geometry.cap(328f, 46f, 2))

/**
 * Row 4: More (123/ABC) 1..45.5, emoji 47.5..92.5 (the International key's frame), space, return
 * 282.5..374. As on Face ID iPhones, the globe and dictation keys sit in a strip below the keys,
 * so the emoji key takes the globe's slot and space spans the dictation key's 94.5..280.5 too.
 */
private fun bottomRow(planeKey: Key): List<Key> = listOf(
    planeKey,
    Key(KeyKind.EMOJI, "", 3, Geometry.cap(47.5f, 45f, 3)),
    Key(KeyKind.SPACE, " ", 3, Geometry.cap(94.5f, 186f, 3)),
    Key(KeyKind.RETURN, "\n", 3, Geometry.cap(282.5f, 91.5f, 3)),
)

private fun moreKey(kind: KeyKind, label: String) = Key(kind, label, 3, Geometry.cap(1f, 44.5f, 3))

/** The iOS layout at its native 375 x 216 pt. */
object Layouts : KeyLayoutSet {
    override fun letters(numberRow: Boolean) = letters

    val letters: List<Key> =
        charRow("qwertyuiop", 0, 1f) +
            charRow("asdfghjkl", 1, 19.5f) +
            listOf(shiftLike(KeyKind.SHIFT, "")) +
            charRow("zxcvbnm", 2, 57f) +
            listOf(deleteKey()) +
            bottomRow(moreKey(KeyKind.TO_NUMBERS, "123"))

    override val numbers: List<Key> =
        charRow("1234567890", 0, 1f) +
            charRow("-/:;()$&@\"", 1, 1f, mapOf('"' to "”")) +
            listOf(shiftLike(KeyKind.TO_SYMBOLS, "#+=")) +
            punctuationRow(2) +
            listOf(deleteKey()) +
            bottomRow(moreKey(KeyKind.TO_LETTERS, "ABC"))

    override val symbols: List<Key> =
        charRow("[]{}#%^*+=", 0, 1f) +
            charRow("_\\|~<>€£¥•", 1, 1f) +
            listOf(shiftLike(KeyKind.TO_NUMBERS, "123")) +
            punctuationRow(2) +
            listOf(deleteKey()) +
            bottomRow(moreKey(KeyKind.TO_LETTERS, "ABC"))

    fun keys(plane: Plane) = when (plane) {
        Plane.LETTERS -> letters
        Plane.NUMBERS -> numbers
        Plane.SYMBOLS -> symbols
    }

    /**
     * Number row: 40 pt keys (a letter key is 42) on the letter pitch, in the 51 pt band above the
     * letters, with the same 12 pt gap to the q row as between letter rows.
     */
    override fun numberRow(): List<Key> = "1234567890".mapIndexed { i, c ->
        Key(KeyKind.CHAR, c.toString(), 0, RectF(1f + i * 37.5f + 2f, 8f, 1f + i * 37.5f + 35.5f - 2f, 48f))
    }
}

/** Which key arrangement to use (Settings > Key Layout); both are drawn in the iOS style. */
enum class KeyStyle { IOS, GBOARD }

/**
 * The keys actually shown: a [KeyStyle]'s layout, plus the optional number row above the letters,
 * scaled vertically by [heightScale] (Settings > Keyboard Height). Widths always stay at 375 pt.
 * With the number row, pages follow Gboard's resources (gboard.apk): the keyboard body (every row
 * but the bottom one) has one height on every page. On the letters page it holds the number row
 * (weight 238) and three letter rows (254 each); on the 123 and #+= pages their three upper rows
 * share it equally, with keys [PAGE_KEY_GROW] taller and even gaps. The bottom row never moves.
 */
class KeyboardSpec(
    val style: KeyStyle = KeyStyle.IOS,
    val numberRow: Boolean = false,
    val heightScale: Float = 1f,
    /** Show the small long-press symbols in key corners (Gboard layout). */
    val hints: Boolean = true,
    /** The emoji key on the bottom row; without it the space bar takes its place. */
    val emojiKey: Boolean = true,
) {
    fun sameAs(o: KeyboardSpec) = style == o.style && numberRow == o.numberRow && heightScale == o.heightScale &&
        hints == o.hints && emojiKey == o.emojiKey

    /** Applies [hints] and [emojiKey] to a page of keys. */
    private fun adjust(keys: List<Key>): List<Key> {
        var out = when {
            !hints -> keys.map { if (it.hint == null) it else Key(it.kind, it.text, it.row, it.rect, it.label) }
            // The iOS layout has no hints of its own; Symbol Hints gives its letters Gboard's.
            style == KeyStyle.IOS -> keys.map { k ->
                if (!k.isLetter || k.hint != null) k
                else Key(k.kind, k.text, k.row, k.rect, k.label, GboardLayouts.hintFor(k.text[0], numberRow))
            }
            else -> keys
        }
        if (!emojiKey) {
            val emoji = out.firstOrNull { it.kind == KeyKind.EMOJI }
            if (emoji != null) {
                out = out.filter { it !== emoji }.map { k ->
                    if (k.kind != KeyKind.SPACE) k else Key(k.kind, k.text, k.row,
                        RectF(minOf(k.rect.left, emoji.rect.left), k.rect.top, maxOf(k.rect.right, emoji.rect.right), k.rect.bottom), k.label)
                }
            }
        }
        return out
    }

    private val base: KeyLayoutSet = when (style) {
        KeyStyle.IOS -> Layouts
        KeyStyle.GBOARD -> GboardLayouts
    }
    private val baseHeight = Geometry.HEIGHT
    private val extra = if (numberRow) NUMBER_ROW_PT else 0f

    /** Height of the keys area in points (the same on every page). */
    val height = (baseHeight + extra) * heightScale

    /**
     * A 123 / #+= page with the number row on: its three upper rows spread over the body (their
     * own rows plus the number row's room), keys [PAGE_KEY_GROW] taller, gaps even down to the
     * bottom row, which stays where it is on the letters page.
     */
    private fun spread(keys: List<Key>): List<Key> {
        if (!numberRow) return keys.map { it.moved(0f, heightScale) }
        val top = keys.filter { it.row == 0 }.minOf { it.rect.top }
        val bottomTop = keys.filter { it.row == 3 }.minOf { it.rect.top } + extra
        return keys.map { key ->
            if (key.row == 3) return@map key.moved(extra, heightScale)
            val h = key.rect.height() * PAGE_KEY_GROW
            val gap = (bottomTop - top - 3 * h) / 3
            val y = top + key.row * (h + gap)
            Key(key.kind, key.text, key.row, RectF(key.rect.left, y * heightScale, key.rect.right, (y + h) * heightScale),
                key.label, key.hint)
        }
    }

    private val letters: List<Key> = adjust(
        (if (numberRow) base.numberRow().map { it.moved(0f, heightScale) } else emptyList()) +
            base.letters(numberRow).map { it.moved(extra, heightScale, it.row + if (numberRow) 1 else 0) },
    )
    private val numbers = adjust(spread(base.numbers))
    private val symbols = adjust(spread(base.symbols))

    fun keys(plane: Plane) = when (plane) {
        Plane.LETTERS -> letters
        Plane.NUMBERS -> numbers
        Plane.SYMBOLS -> symbols
    }

    private val centres = Plane.entries.associateWith { p ->
        val byRow = keys(p).groupBy { it.row }
        FloatArray(byRow.keys.max() + 1) { r -> byRow[r]?.map { it.rect.centerY() }?.average()?.toFloat() ?: 0f }
    }

    /** Centre y of each row of [plane], in points from the top of the keys area. */
    fun rowCenters(plane: Plane): FloatArray = centres.getValue(plane)

    /** Centres of the three letter rows (q, a, z rows). */
    private val letterRows = FloatArray(3) { r ->
        letters.filter { it.isLetter && it.text[0] == "qaz"[r] }.first().rect.centerY()
    }

    /**
     * A y in the keys area mapped to the slide-to-type decoder's frame, where the letter rows'
     * centres are at 0, 54 and 108 (the iOS layout); linear between and beyond the rows. The
     * letter rows sit at the same x in every style, so x needs no mapping.
     */
    fun decoderY(y: Float): Float {
        val (a, b, c) = Triple(letterRows[0], letterRows[1], letterRows[2])
        return if (y <= b) (y - a) / (b - a) * 54f else 54f + (y - b) / (c - b) * 54f
    }

    companion object {
        /** The number row's band: Gboard's row weights 238 vs 254 for a letter row, on the 54 pt pitch. */
        const val NUMBER_ROW_PT = 54f * 238f / 254f
        /** How much taller the 123 / #+= keys get when those pages fill the number row's room (Gboard: ~16 %). */
        const val PAGE_KEY_GROW = 1.16f
    }
}

/**
 * Long-press variants, from TextInput_en.bundle/Keyboard-en.plist and
 * TextInput.framework/Keyboard-default.plist. [opensLeft] mirrors the plist's Direction.
 */
class Variants(val items: List<String>, val opensLeft: Boolean)

object Accents {
    private val lower = mapOf(
        "a" to Variants("a à á â ä ǎ æ ã å ā ă ą".split(' '), false),
        "c" to Variants("c ç ć č ċ".split(' '), false),
        "d" to Variants("d ď ð".split(' '), false),
        "e" to Variants("e è é ê ë ě ẽ ē ė ę".split(' '), false),
        "g" to Variants("g ğ ġ".split(' '), false),
        "h" to Variants("h ħ".split(' '), false),
        "i" to Variants("i ì í î ï ǐ ĩ ī ı į".split(' '), true),
        "k" to Variants("k ķ".split(' '), true),
        "l" to Variants("l ł ļ ľ".split(' '), true),
        "n" to Variants("n ñ ń ņ ň".split(' '), true),
        "o" to Variants("o ò ó ô ö ǒ œ ø õ ō ő".split(' '), true),
        "r" to Variants("r ř".split(' '), false),
        "s" to Variants("s ß ş ș ś š".split(' '), false),
        "t" to Variants("t ț ť þ".split(' '), false),
        "u" to Variants("u ù ú û ü ǔ ũ ū ű ů ų".split(' '), true),
        "w" to Variants("w ŵ".split(' '), false),
        "y" to Variants("y ý ŷ ÿ".split(' '), true),
        "z" to Variants("z ź ž ż".split(' '), false),
    )

    private val upper = mapOf(
        "A" to "A À Á Â Ä Ǎ Æ Ã Å Ā Ă Ą", "C" to "C Ç Ć Č Ċ", "D" to "D Ď Ð",
        "E" to "E È É Ê Ë Ě Ẽ Ē Ė Ę", "G" to "G Ğ Ġ", "H" to "H Ħ",
        "I" to "I Ì Í Î Ï Ǐ Ĩ Ī İ Į", "K" to "K Ķ", "L" to "L Ł Ļ Ľ", "N" to "N Ñ Ń Ņ Ň",
        "O" to "O Ò Ó Ô Ö Ǒ Œ Ø Õ Ō Ő", "R" to "R Ř", "S" to "S ẞ Ś Š Ş Ș", "T" to "T Ț Ť Þ",
        "U" to "U Ù Ú Û Ü Ǔ Ũ Ū Ű Ů Ų", "W" to "W Ŵ", "Y" to "Y Ý Ŷ Ÿ", "Z" to "Z Ź Ž Ż",
    ).mapValues { (k, v) -> Variants(v.split(' '), lower.getValue(k.lowercase()).opensLeft) }

    private val punctuation = mapOf(
        "!" to Variants(listOf("¡", "!"), false),
        "\"" to Variants(listOf("”", "“", "„", "»", "«", "\""), true),
        "%" to Variants(listOf("‰", "%"), false),
        "&" to Variants(listOf("§", "&"), false),
        "'" to Variants(listOf("’", "‘", "`", "'"), true),
        "-" to Variants(listOf("–", "—", "•", "-"), false),
        "." to Variants(listOf("…", "."), false),
        "/" to Variants(listOf("\\", "/"), false),
        "0" to Variants(listOf("°", "0"), true),
        "?" to Variants(listOf("¿", "?"), false),
        "=" to Variants(listOf("=", "≠", "≈"), true),
        "$" to Variants(listOf("$", "¢"), false),
    )

    fun forOutput(s: String): Variants? = lower[s] ?: upper[s] ?: punctuation[s]
}
