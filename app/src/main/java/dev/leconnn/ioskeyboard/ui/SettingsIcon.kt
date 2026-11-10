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
    /** Settings > Keyboard (iOS: grey, a keyboard). */
    KEYBOARD(0xFF8E8E93.toInt()),
    /** Settings > Sounds & Haptics (iOS: red, a speaker). */
    SOUNDS(0xFFFF3B30.toInt()),
    /** Settings > Privacy Policy (iOS Privacy & Security: blue, a raised hand). */
    PRIVACY(0xFF007AFF.toInt()),
    /** Settings > Reset (grey, a circular arrow). */
    RESET(0xFF8E8E93.toInt()),
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
                KEYBOARD, LAYOUT -> {
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
                FEEDBACK, SOUNDS -> {
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
                PRIVACY -> {
                    // A raised hand: four fingers and a thumb over a palm.
                    fg.style = Paint.Style.FILL
                    val pw = box.width() * 0.62f
                    val left = box.centerX() - pw / 2 + box.width() * 0.04f
                    val palm = RectF(left, box.top + box.height() * 0.48f, left + pw, box.bottom)
                    canvas.drawRoundRect(palm, pw * 0.32f, pw * 0.32f, fg)
                    val fw = pw / 4.6f
                    for (i in 0..3) {
                        val fx = left + i * (pw - fw) / 3
                        val ft = box.top + box.height() * (if (i == 1 || i == 2) 0.02f else 0.1f)
                        canvas.drawRoundRect(RectF(fx, ft, fx + fw, palm.top + fw), fw / 2, fw / 2, fg)
                    }
                    path.reset()
                    path.addRoundRect(RectF(left - fw * 1.5f, box.top + box.height() * 0.45f, left + fw * 0.2f, box.top + box.height() * 0.45f + fw),
                        fw / 2, fw / 2, Path.Direction.CW)
                    val m = android.graphics.Matrix().apply { setRotate(45f, left, box.top + box.height() * 0.5f) }
                    path.transform(m)
                    canvas.drawPath(path, fg)
                }
                RESET -> {
                    // A circular arrow.
                    canvas.drawArc(RectF(box.left + box.width() * 0.08f, box.top + box.height() * 0.08f,
                        box.right - box.width() * 0.08f, box.bottom - box.height() * 0.08f), -60f, 290f, false, fg)
                    fg.style = Paint.Style.FILL
                    val ax = box.centerX() + box.width() * 0.42f * kotlin.math.cos(Math.toRadians(-60.0)).toFloat()
                    val ay = box.centerY() + box.height() * 0.42f * kotlin.math.sin(Math.toRadians(-60.0)).toFloat()
                    path.reset()
                    path.moveTo(ax + box.width() * 0.2f, ay - box.height() * 0.02f)
                    path.lineTo(ax - box.width() * 0.08f, ay - box.height() * 0.2f)
                    path.lineTo(ax - box.width() * 0.04f, ay + box.height() * 0.14f)
                    path.close()
                    canvas.drawPath(path, fg)
                }
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

/** A speaker glyph with [waves] sound waves (0 = quiet end of a volume slider), in [color]. */
class SpeakerDrawable(private val color: Int, private val waves: Int) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@SpeakerDrawable.color
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = RectF(bounds)
        val s = b.height()
        p.style = Paint.Style.FILL
        path.reset()
        path.moveTo(b.left, b.top + s * 0.36f)
        path.lineTo(b.left + s * 0.2f, b.top + s * 0.36f)
        path.lineTo(b.left + s * 0.46f, b.top + s * 0.14f)
        path.lineTo(b.left + s * 0.46f, b.bottom - s * 0.14f)
        path.lineTo(b.left + s * 0.2f, b.bottom - s * 0.36f)
        path.lineTo(b.left, b.bottom - s * 0.36f)
        path.close()
        canvas.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.08f
        for (i in 1..waves) {
            val r = s * (0.16f + 0.17f * i)
            val cx = b.left + s * 0.4f
            canvas.drawArc(RectF(cx - r, b.centerY() - r, cx + r, b.centerY() + r), -40f, 80f, false, p)
        }
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { p.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
