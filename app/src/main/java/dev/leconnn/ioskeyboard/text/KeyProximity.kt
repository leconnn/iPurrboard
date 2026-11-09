package dev.leconnn.ioskeyboard.text

import kotlin.math.hypot

/**
 * Letter key centres on the 375 pt QWERTY layout (padded frames from the decoded
 * iPhone-PortraitChoco geometry: rows start at x 1 / 19.5 / 57 on a 37.5 pt pitch, 54 pt apart).
 * Kept free of Android types so the corrector can be unit tested.
 */
object KeyProximity {
    private const val PITCH = 37.5f
    private val rows = listOf("qwertyuiop" to 1f, "asdfghjkl" to 19.5f, "zxcvbnm" to 57f)

    private val centres: Map<Char, Pair<Float, Float>> = buildMap {
        rows.forEachIndexed { r, (letters, x0) ->
            letters.forEachIndexed { i, c -> put(c, x0 + i * PITCH + 35.5f / 2 to r * 54f) }
        }
    }

    /** Distance between two keys in key pitches, or null if either isn't a letter key. */
    fun distance(a: Char, b: Char): Float? {
        val p = centres[a.lowercaseChar()] ?: return null
        val q = centres[b.lowercaseChar()] ?: return null
        return hypot(p.first - q.first, (p.second - q.second) * PITCH / 54f) / PITCH
    }

    /** a-z x a-z neighbour table, so the corrector's inner loop is a lookup instead of a distance. */
    private val near = BooleanArray(26 * 26).also { t ->
        for (a in 'a'..'z') for (b in 'a'..'z') t[(a - 'a') * 26 + (b - 'a')] = (distance(a, b) ?: Float.MAX_VALUE) <= 1.3f
    }

    /** Neighbouring keys: within 1.3 pitches (left/right and the keys diagonally above/below). */
    fun adjacent(a: Char, b: Char): Boolean {
        val i = a.lowercaseChar() - 'a'
        val j = b.lowercaseChar() - 'a'
        return i in 0..25 && j in 0..25 && near[i * 26 + j]
    }

    /** Centre of a letter key in points, for swipe templates. */
    fun centre(c: Char): Pair<Float, Float>? = centres[c.lowercaseChar()]

    /**
     * Where a tap on letter [c] landed, in the same points as [centre]: [dx] and [dy] are its
     * offset from the key's centre in key widths and heights (from the real keyboard, whatever
     * its layout or size).
     */
    fun touch(c: Char, dx: Float, dy: Float): Pair<Float, Float>? {
        val (x, y) = centres[c.lowercaseChar()] ?: return null
        return x + dx * PITCH to y + dy * ROW_PITCH
    }

    /** Distance from a touch point to letter [c]'s centre in key pitches, or null for non-letters. */
    fun distanceFrom(x: Float, y: Float, c: Char): Float? {
        val (cx, cy) = centres[c.lowercaseChar()] ?: return null
        return hypot(cx - x, (cy - y) * PITCH / ROW_PITCH) / PITCH
    }

    private const val ROW_PITCH = 54f
}
