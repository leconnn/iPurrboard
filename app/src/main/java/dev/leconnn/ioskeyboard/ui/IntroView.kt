package dev.leconnn.ioskeyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The launch intro, drawn over Settings on a cold start: the keycap logo is pressed (with a
 * haptic tick), bursts into a few pieces, springs back up as the logo while the name types in
 * underneath, then everything fades to show the app. Values were tuned in the web prototype.
 * Every frame is a pure function of time, as in the prototype. A tap skips to the fade.
 */
@SuppressLint("ViewConstructor")
class IntroView(context: Context, private val onHandOff: () -> Unit, private val onDone: () -> Unit) : View(context) {

    private val dark = context.isDark()
    private val ground = if (dark) PLUM else 0xFFF2F2F7.toInt()
    private val nameColor = if (dark) 0xFFFBE9EB.toInt() else 0xFF000000.toInt()

    private var start = 0L
    /**
     * Where the system splash left its key (Android 12+): its width and centre in this view's
     * pixels. The intro's key grows from there into place, so there's no jump.
     */
    var splashKey: android.graphics.PointF? = null
    var splashKeyWidth = 0f
    /** Set when a tap skips ahead: the intro jumps to the hand-off. */
    private var skipFrom = -1f
    private var tickDone = false
    private var handedOff = false
    private var finished = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val rect = RectF()
    private val path = Path()

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var attachedAt = 0L
    /** On Android 12+ the clock starts when the system splash hands over ([begin]). */
    var waitForSplash = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedAt = SystemClock.uptimeMillis()
    }

    /** Starts the timeline now (the splash just left the screen). */
    fun begin() {
        if (start == 0L) start = SystemClock.uptimeMillis()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && skipFrom < 0) skipFrom = elapsed()
        return true
    }

    private fun elapsed() = (SystemClock.uptimeMillis() - start).toFloat()

    /** Time on the intro's timeline; a skip jumps to the start of the fade. */
    private fun timeline(): Float {
        val e = elapsed()
        return if (skipFrom >= 0) max(OUT_AT, e - skipFrom + OUT_AT) else e
    }

    override fun onDraw(canvas: Canvas) {
        // Behind the splash, hold the first frame; start with the first visible frame (or after a
        // second if the splash never says it's gone).
        if (start == 0L) {
            if (!waitForSplash || SystemClock.uptimeMillis() - attachedAt > 1000) begin()
            else { draw(canvas, 0f); postInvalidateOnAnimation(); return }
        }
        val t = timeline()
        if (t >= OUT_AT && !handedOff) { handedOff = true; onHandOff() }
        if (t >= END) {
            if (!finished) { finished = true; post { onDone() } }
            return
        }
        if (t >= PRESS_AT && !tickDone) {
            tickDone = true
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        draw(canvas, t)
        postInvalidateOnAnimation()
    }

    private fun draw(c: Canvas, t: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val out = easeInOut((t - OUT_AT) / 300f)
        // The intro's ground fades out to reveal the app.
        paint.shader = null
        paint.color = ground; paint.alpha = ((1f - out) * 255).toInt()
        c.drawRect(0f, 0f, w, h, paint)
        paint.alpha = 255
        val layer = c.saveLayerAlpha(0f, 0f, w, h, ((1f - out) * 255).toInt())

        val s = min(w, h) * 0.34f
        val cx = w / 2; val cy0 = h * 0.46f
        val popT = PRESS_AT + PRESS_MS + HOLD_MS
        val appear = easeOut(t / 220f)
        val down = when {
            t < PRESS_AT -> 0f
            t < PRESS_AT + PRESS_MS -> easeInOut((t - PRESS_AT) / PRESS_MS)
            else -> 1f
        }
        val pop = spring((t - popT) / 1000f, POP_RESPONSE, POP_DAMPING)
        val rise = spring((t - popT - RISE_DELAY) / 1000f, 0.45f, 1f)
        val iconSize = s * 0.86f
        val finalY = h * 0.40f
        val iconY = lerp(cy0, finalY, rise)

        if (t < popT) {
            // Grows from the splash's key (or from 92 % without one) into place.
            val from = splashKey
            val k0 = if (from != null && splashKeyWidth > 0f) splashKeyWidth / s else 0.92f
            val k = lerp(k0, 1f, appear) * (1f - (1f - PRESS_DEPTH) * down)
            val dy = if (from != null) (from.y - cy0) * (1f - appear) else 0f
            val dx = if (from != null) (from.x - cx) * (1f - appear) else 0f
            c.save(); c.translate(dx, dy); c.scale(k, k * (1f - 0.03f * down), cx, cy0)
            // Fully visible from the first frame: it takes over from the system splash's key.
            keycap(c, cx, cy0, s, down, 1f)
            c.restore()
            if (down > 0f) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * resources.displayMetrics.density
                paint.color = Color.argb((0.25f * (1f - down * 0.5f) * 255).toInt(), 90, 10, 21)
                c.drawCircle(cx, cy0, s * (0.15f + 0.25f * down), paint)
                paint.style = Paint.Style.FILL
            }
        } else {
            val bt = (t - popT) / 1000f
            burst(c, cx, cy0, s, bt)
            // A soft ring at the pop.
            val ring = (bt / 0.35f).coerceIn(0f, 1f)
            if (ring < 1f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (6f * (1f - ring) + 1f) * resources.displayMetrics.density
                paint.color = Color.argb((0.6f * (1f - ring) * 255).toInt(), 246, 169, 177)
                c.drawCircle(cx, cy0, s * (0.45f + 0.55f * easeOut(ring)), paint)
                paint.style = Paint.Style.FILL
            }
            // The key springs back up as the logo.
            val k = lerp(0.55f, 1f, pop)
            c.save(); c.scale(k, k, cx, iconY)
            keycap(c, cx, iconY, iconSize, 0f, (pop * 3f).coerceIn(0f, 1f))
            c.restore()
            name(c, cx, finalY + iconSize / 2, s, t, popT)
        }
        c.restoreToCount(layer)
    }

    /** The keycap with the cat; [press] 0..1 sinks it into its side. */
    private fun keycap(c: Canvas, cx: Float, cy: Float, s: Float, press: Float, alpha: Float) {
        val a = (alpha * 255).toInt()
        val skirt = s * 0.13f * (1f - press * 0.75f)
        // A soft shadow on the surface under the key; pressing brings the key down to it.
        val spread = 1f - press * 0.12f
        val sy = cy + s * 0.52f
        c.save(); c.scale(1f, 0.22f, cx, sy)
        paint.shader = RadialGradient(cx, sy, s * 0.55f * spread,
            Color.argb(((0.42f + 0.18f * press) * alpha * 255).toInt(), 20, 4, 8), Color.argb(0, 20, 4, 8), Shader.TileMode.CLAMP)
        c.drawCircle(cx, sy, s * 0.55f * spread, paint)
        paint.shader = null
        c.restore()
        // Contact shadow, side, face.
        paint.color = Color.argb((0.18f * a).toInt(), 60, 10, 20)
        rrect(c, cx - s * 0.5f, cy - s * 0.42f + skirt + s * 0.06f, s, s * 0.9f, s * 0.2f)
        paint.color = SIDE; paint.alpha = a
        rrect(c, cx - s * 0.5f, cy - s * 0.45f + skirt * 0.5f, s, s * 0.9f + skirt, s * 0.2f)
        val top = cy - s * 0.45f + press * s * 0.05f
        paint.color = mix(CAP, SIDE, press * 0.45f); paint.alpha = a
        rrect(c, cx - s * 0.46f, top, s * 0.92f, s * 0.84f, s * 0.18f)
        paint.color = Color.argb((0.18f * a).toInt(), 255, 255, 255)
        rrect(c, cx - s * 0.42f, top + s * 0.02f, s * 0.84f, s * 0.22f, s * 0.14f)
        cat(c, cx, top + s * 0.44f, s * 0.62f, a)
    }

    /** The cat face: a round head, two ears, eyes and a ω mouth. */
    private fun cat(c: Canvas, cx: Float, cy: Float, s: Float, a: Int) {
        paint.color = CAT; paint.alpha = a
        rect.set(cx - s * 0.5f, cy + s * 0.06f - s * 0.38f, cx + s * 0.5f, cy + s * 0.06f + s * 0.38f)
        c.drawOval(rect, paint)
        for (d in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(cx + d * s * 0.46f, cy - s * 0.02f)
            path.quadTo(cx + d * s * 0.48f, cy - s * 0.42f, cx + d * s * 0.36f, cy - s * 0.44f)
            path.quadTo(cx + d * s * 0.24f, cy - s * 0.38f, cx + d * s * 0.12f, cy - s * 0.26f)
            path.close()
            c.drawPath(path, paint)
        }
        paint.color = CAP; paint.alpha = a
        for (d in intArrayOf(-1, 1)) c.drawCircle(cx + d * s * 0.22f, cy + s * 0.06f, s * 0.05f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.035f; paint.strokeCap = Paint.Cap.ROUND
        for (dx in floatArrayOf(-0.05f, 0.05f)) {
            rect.set(cx + dx * s - s * 0.05f, cy + s * 0.16f - s * 0.05f, cx + dx * s + s * 0.05f, cy + s * 0.16f + s * 0.05f)
            c.drawArc(rect, 5.7f, 168.6f, false, paint)
        }
        paint.style = Paint.Style.FILL
    }

    /** Pieces of the keycap fly out, spin and fall away (the same pieces every time). */
    private fun burst(c: Canvas, cx: Float, cy: Float, s: Float, bt: Float) {
        var seed = 7L
        fun rand(): Float { seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL; return seed / 4294967296f }
        val dp = resources.displayMetrics.density
        val colors = intArrayOf(CAP, CAP_LIGHT, SIDE, CAT)
        for (i in 0 until SHARDS) {
            val ang = (i.toFloat() / max(1, SHARDS)) * 2f * PI.toFloat() + rand() * 0.6f
            val sp = (220f + rand() * 260f) * BURST_SPEED * dp
            val size = s * (0.05f + rand() * 0.07f)
            val spin = (rand() - 0.5f) * 12f
            val life = 0.5f + rand() * 0.35f
            if (bt > life) { rand(); continue }
            val drag = (1f - exp(-3.2f * bt)) / 3.2f
            val x = cx + cos(ang) * sp * drag
            val y = cy + sin(ang) * sp * drag + 520f * dp * bt * bt * 0.5f
            val f = 1f - easeOut(bt / life)
            paint.color = colors[(rand() * 4).toInt().coerceIn(0, 3)]
            paint.alpha = (f * 255).toInt()
            c.save(); c.translate(x, y); c.rotate(Math.toDegrees((spin * bt).toDouble()).toFloat())
            rrect(c, -size / 2, -size / 2, size, size, size * 0.28f)
            c.restore()
        }
    }

    /** The name under the logo, each letter dropping in on its own spring. */
    private fun name(c: Canvas, cx: Float, iconBottom: Float, s: Float, t: Float, popT: Float) {
        val fontPx = (s * 0.28f)
        text.textSize = fontPx
        text.color = nameColor
        text.textAlign = Paint.Align.CENTER
        val widths = NAME.map { text.measureText(it.toString()) }
        var x = cx - widths.sum() / 2
        val baseY = iconBottom + fontPx * 1.35f
        val letterStart = popT + RISE_DELAY + 60f
        NAME.forEachIndexed { i, ch ->
            val lt = (t - letterStart - i * LETTER_STAGGER) / 1000f
            val v = spring(lt, LETTER_RESPONSE, LETTER_DAMPING)
            val a = (lt / 0.08f).coerceIn(0f, 1f)
            if (a > 0f) {
                c.save()
                val lx = x + widths[i] / 2; val ly = baseY - fontPx * 0.35f
                c.translate(lx, ly + (1f - v) * fontPx * 0.9f)
                val sc = 0.6f + 0.4f * v
                c.scale(sc, sc)
                text.alpha = (a * 255).toInt()
                c.drawText(ch.toString(), 0f, fontPx * 0.35f, text)
                c.restore()
            }
            x += widths[i]
        }
    }

    private fun rrect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, r, r, paint)
    }

    private companion object {
        // Tuned in the web prototype.
        const val PRESS_AT = 260f
        const val PRESS_MS = 110f
        const val HOLD_MS = 0f
        const val PRESS_DEPTH = 0.87f
        const val SHARDS = 6
        const val BURST_SPEED = 1f
        const val POP_DAMPING = 0.7f
        const val POP_RESPONSE = 0.42f
        const val RISE_DELAY = 160f
        const val LETTER_STAGGER = 3f
        const val LETTER_DAMPING = 0.6f
        const val LETTER_RESPONSE = 0.52f
        const val OUT_AT = 1550f
        const val END = OUT_AT + 350f
        const val NAME = "iPurrboard"

        const val CAP = 0xFFF6A9B1.toInt()
        const val CAP_LIGHT = 0xFFFBC3C9.toInt()
        const val SIDE = 0xFFE38E99.toInt()
        const val CAT = 0xFF5A0A15.toInt()
        const val PLUM = 0xFF24171A.toInt()

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun easeOut(t: Float) = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)
        fun easeInOut(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
        }

        /** A spring from 0 to 1 at time [t] seconds (closed form; damping 1 = no overshoot). */
        fun spring(t: Float, response: Float, damping: Float): Float {
            if (t <= 0f) return 0f
            val w = 2f * PI.toFloat() / response
            if (damping >= 0.999f) return 1f - (1f + w * t) * exp(-w * t)
            val z = damping
            val wd = w * sqrt(1f - z * z)
            return 1f - exp(-z * w * t) * (cos(wd * t) + (z / sqrt(1f - z * z)) * sin(wd * t))
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int) = (((a shr sh) and 0xFF) + (((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * t).toInt()
            return Color.rgb(ch(16), ch(8), ch(0))
        }
    }
}
