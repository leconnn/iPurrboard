package dev.leconnn.ioskeyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Approximate iOS keyboard colours (sampled by eye, not extracted). Classic is the flat
 * pre-iOS 26 look; Glass is translucent keys over a tinted, blurred panel ([panelTint]).
 */
class KeyboardTheme(
    val background: Int,
    val letterKey: Int,
    val functionKey: Int,
    val pressedLetterKey: Int,
    val pressedFunctionKey: Int,
    val keyShadow: Int,
    val text: Int,
    val accent: Int,
    val accentText: Int,
    val glass: Boolean = false,
    /** Glass: panel colour over the blur (or over the app when blur is unavailable). */
    val panelTint: Int = background,
    /** Glass: bright rim along the top edge of keys. */
    val rim: Int = 0,
    /** Fill of preview bubbles and accent panels; they sit outside the blurred window. */
    val popup: Int = letterKey,
) {
    val keyRadiusPt get() = if (glass) 8.5f else 5f

    companion object {
        val light = KeyboardTheme(
            background = 0xFFD1D4DA.toInt(),
            letterKey = 0xFFFFFFFF.toInt(),
            functionKey = 0xFFABB0BA.toInt(),
            pressedLetterKey = 0xFFABB0BA.toInt(),
            pressedFunctionKey = 0xFFFFFFFF.toInt(),
            keyShadow = 0xFF898A8D.toInt(),
            text = 0xFF000000.toInt(),
            accent = 0xFF007AFF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
        )
        val dark = KeyboardTheme(
            background = 0xFF2B2B2D.toInt(),
            letterKey = 0xFF6B6B6E.toInt(),
            functionKey = 0xFF464648.toInt(),
            pressedLetterKey = 0xFF464648.toInt(),
            pressedFunctionKey = 0xFF6B6B6E.toInt(),
            keyShadow = 0xFF000000.toInt(),
            text = 0xFFFFFFFF.toInt(),
            accent = 0xFF0A84FF.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
        )

        // iOS 27 Liquid Glass (tinted), measured from an iOS 27 screenshot: a mostly opaque
        // panel (the app only faintly shows through the blur), every key the same soft colour
        // (function keys included), no key shadows and no bright rim.
        private fun glassLight(blur: Boolean) = KeyboardTheme(
            background = 0, letterKey = 0xF2FFFFFF.toInt(), functionKey = 0xF2FFFFFF.toInt(),
            pressedLetterKey = 0xFFD9DADE.toInt(), pressedFunctionKey = 0xFFD9DADE.toInt(),
            keyShadow = 0, text = 0xFF000000.toInt(),
            accent = 0xFF007AFF.toInt(), accentText = 0xFFFFFFFF.toInt(), glass = true,
            panelTint = if (blur) 0xE0E9EAED.toInt() else 0xF2E9EAED.toInt(),
            rim = 0, popup = 0xFFFFFFFF.toInt(),
        )

        private fun glassDark(blur: Boolean) = KeyboardTheme(
            background = 0, letterKey = 0x2BFFFFFF, functionKey = 0x2BFFFFFF,
            pressedLetterKey = 0x59FFFFFF, pressedFunctionKey = 0x59FFFFFF,
            keyShadow = 0, text = 0xFFFFFFFF.toInt(),
            accent = 0xFF0A84FF.toInt(), accentText = 0xFFFFFFFF.toInt(), glass = true,
            panelTint = if (blur) 0xE6242527.toInt() else 0xF2222325.toInt(),
            rim = 0, popup = 0xFF464749.toInt(),
        )

        fun isNight(context: Context) =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        /** Dark keyboard? Follows the system unless Settings forces Light or Dark. */
        fun isDark(context: Context) = when (Prefs.get(context).themeMode) {
            Prefs.ThemeMode.AUTO -> isNight(context)
            Prefs.ThemeMode.LIGHT -> false
            Prefs.ThemeMode.DARK -> true
        }

        fun forContext(context: Context, glass: Boolean = false, blur: Boolean = false): KeyboardTheme {
            val night = isDark(context)
            return when {
                glass && night -> glassDark(blur)
                glass -> glassLight(blur)
                night -> dark
                else -> light
            }
        }
    }
}

/**
 * Key glyphs drawn as paths in a 24 x 24 box, scaled into the keycap. Paths and the working paint
 * are built once (drawing happens on the UI thread only), so animated frames allocate nothing.
 */
object Glyphs {
    enum class ShiftState { OFF, ON, LOCKED }

    private val shiftPath = Path().apply {
        moveTo(12f, 3.5f)
        lineTo(21f, 12.5f)
        lineTo(16f, 12.5f)
        lineTo(16f, 19f)
        lineTo(8f, 19f)
        lineTo(8f, 12.5f)
        lineTo(3f, 12.5f)
        close()
    }
    private val deletePath = Path().apply {
        moveTo(8.5f, 5f)
        lineTo(21f, 5f)
        quadTo(22f, 5f, 22f, 6f)
        lineTo(22f, 18f)
        quadTo(22f, 19f, 21f, 19f)
        lineTo(8.5f, 19f)
        lineTo(2f, 12f)
        close()
    }
    private val globeOval = RectF(8f, 3f, 16f, 21f)
    private val smileArc = RectF(7.5f, 8.5f, 16.5f, 16.8f)
    private val work = Paint(Paint.ANTI_ALIAS_FLAG)

    fun shift(canvas: Canvas, box: RectF, state: ShiftState, paint: Paint) {
        val filled = state != ShiftState.OFF
        draw(canvas, box, paint, filled) { c, pt ->
            c.drawPath(shiftPath, pt)
            if (state == ShiftState.LOCKED) {
                pt.style = Paint.Style.FILL
                c.drawRect(8f, 20.8f, 16f, 22.3f, pt)
            }
        }
    }

    /** [cutout] is the keycap colour, used for the cross when the glyph is filled (pressed). */
    fun delete(canvas: Canvas, box: RectF, paint: Paint, filled: Boolean, cutout: Int) {
        draw(canvas, box, paint, filled) { c, pt ->
            c.drawPath(deletePath, pt)
            pt.style = Paint.Style.STROKE
            pt.color = if (filled) cutout else paint.color
            c.drawLine(11.5f, 9f, 17.5f, 15f, pt)
            c.drawLine(17.5f, 9f, 11.5f, 15f, pt)
        }
    }

    fun globe(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawCircle(12f, 12f, 9f, pt)
            c.drawOval(globeOval, pt)
            c.drawLine(12f, 3f, 12f, 21f, pt)
            c.drawLine(3f, 12f, 21f, 12f, pt)
            c.drawLine(4.6f, 7.5f, 19.4f, 7.5f, pt)
            c.drawLine(4.6f, 16.5f, 19.4f, 16.5f, pt)
        }
    }

    fun emoji(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawCircle(12f, 12f, 9f, pt)
            c.drawArc(smileArc, 20f, 140f, false, pt)
            pt.style = Paint.Style.FILL
            c.drawCircle(9.2f, 9.8f, 1.1f, pt)
            c.drawCircle(14.8f, 9.8f, 1.1f, pt)
        }
    }

    private val clipBoard = RectF(5f, 4.5f, 19f, 21.5f)
    private val clipTab = RectF(8.5f, 2.5f, 15.5f, 6.5f)

    /** A clipboard: board with a clip at the top and two text lines. */
    fun clipboard(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawRoundRect(clipBoard, 2f, 2f, pt)
            pt.style = Paint.Style.FILL_AND_STROKE
            c.drawRoundRect(clipTab, 1.5f, 1.5f, pt)
            pt.style = Paint.Style.STROKE
            c.drawLine(8.5f, 11f, 15.5f, 11f, pt)
            c.drawLine(8.5f, 15f, 13.5f, 15f, pt)
        }
    }

    /** Link: two chain links at an angle. */
    fun link(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.save()
            c.rotate(-45f, 12f, 12f)
            c.drawRoundRect(linkA, 3.5f, 3.5f, pt)
            c.drawRoundRect(linkB, 3.5f, 3.5f, pt)
            c.restore()
        }
    }
    private val linkA = RectF(3f, 9f, 13.5f, 15f)
    private val linkB = RectF(10.5f, 9f, 21f, 15f)

    /** Email: an envelope. */
    fun email(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawRoundRect(envelope, 2f, 2f, pt)
            c.drawLine(4f, 7.5f, 12f, 13f, pt)
            c.drawLine(12f, 13f, 20f, 7.5f, pt)
        }
    }
    private val envelope = RectF(3.5f, 6f, 20.5f, 18f)

    /** Phone number: a phone handset outline (a mobile phone). */
    fun phone(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawRoundRect(phoneBody, 2.5f, 2.5f, pt)
            c.drawLine(10.5f, 17.5f, 13.5f, 17.5f, pt)
        }
    }
    private val phoneBody = RectF(7f, 3f, 17f, 21f)

    /** Address: a map pin. */
    fun pin(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            pinPath.reset()
            pinPath.moveTo(12f, 21f)
            pinPath.cubicTo(12f, 21f, 5f, 14.5f, 5f, 10f)
            pinPath.cubicTo(5f, 6f, 8f, 3f, 12f, 3f)
            pinPath.cubicTo(16f, 3f, 19f, 6f, 19f, 10f)
            pinPath.cubicTo(19f, 14.5f, 12f, 21f, 12f, 21f)
            c.drawPath(pinPath, pt)
            c.drawCircle(12f, 10f, 2.5f, pt)
        }
    }
    private val pinPath = android.graphics.Path()

    /** A code or number: "#". */
    fun hash(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawLine(9.5f, 4f, 7.5f, 20f, pt)
            c.drawLine(16.5f, 4f, 14.5f, 20f, pt)
            c.drawLine(5f, 9f, 20f, 9f, pt)
            c.drawLine(4f, 15f, 19f, 15f, pt)
        }
    }

    /** Voice typing: a microphone. */
    fun mic(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawRoundRect(micBody, 3f, 3f, pt)
            c.drawArc(micArc, 0f, 180f, false, pt)
            c.drawLine(12f, 18f, 12f, 21f, pt)
            c.drawLine(9f, 21f, 15f, 21f, pt)
        }
    }
    private val micBody = RectF(9f, 3f, 15f, 14f)
    private val micArc = RectF(5.5f, 5.5f, 18.5f, 18f)

    /** GIFs: "GIF" in a rounded box, like Gboard's button. */
    fun gif(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawRoundRect(gifBox, 3f, 3f, pt)
            val tp = gifText.apply {
                color = pt.color
                textSize = 7.6f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            }
            c.drawText("GIF", 12f, 12f - (tp.descent() + tp.ascent()) / 2, tp)
        }
    }
    private val gifBox = RectF(3f, 6f, 21f, 18f)
    private val gifText = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Text editing: an I-beam cursor between two small arrows. */
    fun textEditing(canvas: Canvas, box: RectF, paint: Paint) {
        draw(canvas, box, paint, false) { c, pt ->
            c.drawLine(12f, 5f, 12f, 19f, pt)
            c.drawLine(9.5f, 5f, 14.5f, 5f, pt)
            c.drawLine(9.5f, 19f, 14.5f, 19f, pt)
            c.drawLine(3f, 12f, 7.5f, 12f, pt); c.drawLine(3f, 12f, 5f, 10f, pt); c.drawLine(3f, 12f, 5f, 14f, pt)
            c.drawLine(16.5f, 12f, 21f, 12f, pt); c.drawLine(21f, 12f, 19f, 10f, pt); c.drawLine(21f, 12f, 19f, 14f, pt)
        }
    }

    /** A right-pointing chevron, rotated by [degrees] (the toolbar dropdown turns it when open). */
    fun chevron(canvas: Canvas, box: RectF, paint: Paint, degrees: Float) {
        draw(canvas, box, paint, false) { c, pt ->
            c.save()
            c.rotate(degrees, 12f, 12f)
            c.drawLine(9.5f, 6f, 15.5f, 12f, pt)
            c.drawLine(15.5f, 12f, 9.5f, 18f, pt)
            c.restore()
        }
    }

    private inline fun draw(canvas: Canvas, box: RectF, paint: Paint, filled: Boolean, body: (Canvas, Paint) -> Unit) {
        val s = minOf(box.width(), box.height()) / 24f
        val pt = work.apply {
            set(paint)
            style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            strokeWidth = 1.6f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.save()
        canvas.translate(box.centerX() - 12f * s, box.centerY() - 12f * s)
        canvas.scale(s, s)
        body(canvas, pt)
        canvas.restore()
    }
}
