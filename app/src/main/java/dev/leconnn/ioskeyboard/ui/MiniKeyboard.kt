package dev.leconnn.ioskeyboard.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import dev.leconnn.ioskeyboard.KeyboardTheme

/** Little phone screens with a keyboard at the bottom, for the visual pickers (Settings, Welcome). */
object MiniKeyboard {
    /** A tile preview: a little screen with the keyboard at its bottom, in the given look. */
    fun draw(c: Canvas, r: RectF, glass: Boolean, dark: Boolean, extended: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // The screen: a soft wallpaper behind Glass, plain grouped background behind Classic.
        if (glass) {
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                if (dark) intArrayOf(0xFF1D3A6E.toInt(), 0xFF4A2A6B.toInt()) else intArrayOf(0xFF8EC5FF.toInt(), 0xFFE0B3FF.toInt()),
                null, Shader.TileMode.CLAMP)
        } else {
            p.color = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
        }
        c.drawRect(r, p)
        p.shader = null
        // A few lines of "text" at the top.
        p.color = if (dark) 0x40FFFFFF else 0x30000000
        val lh = r.height() * 0.035f
        for (i in 0..2) {
            val y = r.top + r.height() * (0.12f + i * 0.08f)
            c.drawRoundRect(r.left + r.width() * 0.12f, y, r.right - r.width() * (0.2f + 0.15f * i), y + lh, lh / 2, lh / 2, p)
        }
        // The keyboard.
        val top = r.top + r.height() * (if (extended) 0.6f else 0.56f)
        val kr = RectF(r.left, top, r.right, r.bottom)
        val theme = if (glass) null else if (dark) KeyboardTheme.dark else KeyboardTheme.light
        p.color = when {
            glass && dark -> 0xB0242527.toInt()
            glass -> 0xA8E9EAED.toInt()
            else -> theme!!.background
        }
        val corner = if (glass) r.width() * 0.08f else 0f
        c.drawRoundRect(kr.left, kr.top, kr.right, kr.bottom + corner, corner, corner, p)
        val keyColor = when {
            glass && dark -> 0x40FFFFFF
            glass -> 0xF5FFFFFF.toInt()
            else -> theme!!.letterKey
        }
        val pad = r.width() * 0.035f
        val gap = r.width() * 0.018f
        val rows = intArrayOf(10, 9, 7)
        val rowH = (kr.height() - pad * 2) / 4.4f
        val kw = (kr.width() - pad * 2 - gap * 9) / 10
        val rad = if (glass) kw * 0.4f else kw * 0.2f
        for ((i, n) in rows.withIndex()) {
            val y = kr.top + pad + i * (rowH + gap)
            val start = kr.left + (kr.width() - (n * kw + (n - 1) * gap)) / 2
            for (k in 0 until n) {
                val x = start + k * (kw + gap)
                p.color = keyColor
                c.drawRoundRect(x, y, x + kw, y + rowH, rad, rad, p)
            }
        }
        val y = kr.top + pad + 3 * (rowH + gap)
        val fn = if (glass) keyColor else theme!!.functionKey
        if (!extended) {
            p.color = keyColor
            c.drawRoundRect(kr.left + pad + kw * 2.5f, y, kr.right - pad - kw * 2.5f, y + rowH, rad, rad, p)
            p.color = fn
            c.drawRoundRect(kr.left + pad, y, kr.left + pad + kw * 2.2f, y + rowH, rad, rad, p)
            c.drawRoundRect(kr.right - pad - kw * 2.2f, y, kr.right - pad, y + rowH, rad, rad, p)
        } else {
            // 123, comma, space, period, return.
            p.color = fn
            c.drawRoundRect(kr.left + pad, y, kr.left + pad + kw * 1.6f, y + rowH, rad, rad, p)
            c.drawRoundRect(kr.right - pad - kw * 1.6f, y, kr.right - pad, y + rowH, rad, rad, p)
            p.color = keyColor
            c.drawRoundRect(kr.left + pad + kw * 1.6f + gap, y, kr.left + pad + kw * 2.6f + gap, y + rowH, rad, rad, p)
            c.drawRoundRect(kr.right - pad - kw * 2.6f - gap, y, kr.right - pad - kw * 1.6f - gap, y + rowH, rad, rad, p)
            c.drawRoundRect(kr.left + pad + kw * 2.6f + gap * 2, y, kr.right - pad - kw * 2.6f - gap * 2, y + rowH, rad, rad, p)
            // The toolbar: a row of small icons above the keys.
            p.color = if (dark) 0x99FFFFFF.toInt() else 0x80000000.toInt()
            val ty = kr.top - rowH * 0.55f
            for (k in 0..3) c.drawCircle(r.left + r.width() * (0.2f + k * 0.2f), ty, rowH * 0.22f, p)
        }
    }
}
