package dev.leconnn.ioskeyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import dev.leconnn.ioskeyboard.Glyphs

/**
 * Coloured rounded-square icons for settings rows, like iOS Settings: a system colour and a white
 * symbol drawn in code (no image assets).
 */
enum class SettingsIcon(val color: Int) {
    KEYBOARDS(0xFF8E8E93.toInt()),
    TYPING(0xFF007AFF.toInt()),
    PREDICTIONS(0xFF34C759.toInt()),
    SLIDE(0xFFAF52DE.toInt()),
    APPEARANCE(0xFF5856D6.toInt()),
    LAYOUT(0xFFFF9500.toInt()),
    TOOLBAR(0xFF30B0C7.toInt()),
    CLIPBOARD(0xFF8E8E93.toInt()),
    FEEDBACK(0xFFFF2D55.toInt()),
    REPLACEMENT(0xFF8E8E93.toInt()),
    LEARNED(0xFFFF9F0A.toInt()),
    ;

    fun drawable(context: Context): Drawable = Icon(this, context.resources.displayMetrics.density)

    private class Icon(val icon: SettingsIcon, val density: Float) : Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = icon.color }
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val r = RectF()
        private val path = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            r.set(b)
            val s = r.width()
            canvas.drawRoundRect(r, s * 0.23f, s * 0.23f, bg)
            val box = RectF(r.left + s * 0.18f, r.top + s * 0.18f, r.right - s * 0.18f, r.bottom - s * 0.18f)
            fun label(t: String, size: Float) {
                text.textSize = s * size
                canvas.drawText(t, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
            }
            fg.style = Paint.Style.STROKE
            fg.strokeWidth = s * 0.075f
            when (icon) {
                TYPING -> label("Aa", 0.42f)
                PREDICTIONS -> label("abc", 0.34f)
                SLIDE -> {
                    // A swipe trail.
                    path.reset()
                    path.moveTo(box.left, box.bottom - box.height() * 0.2f)
                    path.cubicTo(box.left + box.width() * 0.3f, box.top - box.height() * 0.1f,
                        box.left + box.width() * 0.55f, box.bottom + box.height() * 0.15f, box.right, box.top + box.height() * 0.2f)
                    canvas.drawPath(path, fg)
                }
                APPEARANCE -> {
                    // Half-filled circle (light / dark).
                    canvas.drawCircle(box.centerX(), box.centerY(), box.width() * 0.42f, fg)
                    fg.style = Paint.Style.FILL
                    val c = RectF(box.centerX() - box.width() * 0.42f, box.centerY() - box.width() * 0.42f,
                        box.centerX() + box.width() * 0.42f, box.centerY() + box.width() * 0.42f)
                    canvas.drawArc(c, 90f, 180f, true, fg)
                }
                LAYOUT -> {
                    // A small keyboard: rounded outline with key dots.
                    fg.strokeWidth = s * 0.06f
                    val k = RectF(box.left - s * 0.02f, box.top + box.height() * 0.18f, box.right + s * 0.02f, box.bottom - box.height() * 0.12f)
                    canvas.drawRoundRect(k, s * 0.06f, s * 0.06f, fg)
                    fg.style = Paint.Style.FILL
                    val dot = s * 0.045f
                    for (row in 0..1) for (col in 0..3) {
                        canvas.drawCircle(k.left + k.width() * (0.2f + col * 0.2f), k.top + k.height() * (0.32f + row * 0.28f), dot, fg)
                    }
                    canvas.drawRect(k.left + k.width() * 0.3f, k.bottom - k.height() * 0.2f, k.right - k.width() * 0.3f,
                        k.bottom - k.height() * 0.12f, fg)
                }
                TOOLBAR -> Glyphs.chevron(canvas, box, fg, 0f)
                CLIPBOARD -> Glyphs.clipboard(canvas, box, fg)
                FEEDBACK -> {
                    // A speaker with a sound wave.
                    fg.style = Paint.Style.FILL
                    path.reset()
                    path.moveTo(box.left, box.top + box.height() * 0.36f)
                    path.lineTo(box.left + box.width() * 0.22f, box.top + box.height() * 0.36f)
                    path.lineTo(box.left + box.width() * 0.48f, box.top + box.height() * 0.12f)
                    path.lineTo(box.left + box.width() * 0.48f, box.bottom - box.height() * 0.12f)
                    path.lineTo(box.left + box.width() * 0.22f, box.bottom - box.height() * 0.36f)
                    path.lineTo(box.left, box.bottom - box.height() * 0.36f)
                    path.close()
                    canvas.drawPath(path, fg)
                    fg.style = Paint.Style.STROKE
                    val w = RectF(box.left + box.width() * 0.3f, box.top + box.height() * 0.2f, box.right, box.bottom - box.height() * 0.2f)
                    canvas.drawArc(w, -45f, 90f, false, fg)
                }
                REPLACEMENT -> label("→", 0.5f)
                LEARNED -> label("Ab", 0.4f)
                KEYBOARDS -> {
                    fg.strokeWidth = s * 0.065f
                    dev.leconnn.ioskeyboard.Glyphs.globe(canvas, box, fg)
                }
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
