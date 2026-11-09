package dev.leconnn.ioskeyboard

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * The text-editing panel (Settings > Toolbar > Text Editing), laid out like Gboard's but drawn in
 * the keyboard's iOS style. Its back button and title are in the suggestion bar (see KeyboardView);
 * the keys area has:
 *
 *  - tall ← and → keys, with ↑ / Select / ↓ between them, and Select all / Copy / Paste at the right;
 *  - a bottom row: start (|←), end (→|) and delete.
 *
 * With Select on, movement extends the selection. Copy is greyed without a selection and Paste with
 * an empty clipboard. Arrows and delete repeat while held, with the keyboard's key-repeat timing.
 */
class EditingPanel(private val host: View, private val callbacks: Callbacks) {

    enum class Action(val label: String = "", val repeats: Boolean = false) {
        LEFT(repeats = true), UP(repeats = true), SELECT("Select"), DOWN(repeats = true), RIGHT(repeats = true),
        SELECT_ALL("Select all"), COPY("Copy"), PASTE("Paste"),
        HOME, END, DELETE(repeats = true),
    }

    interface Callbacks {
        /** [selecting]: extend the selection instead of moving the cursor. */
        fun onAction(action: Action, selecting: Boolean)
        fun onFeedback()
        /** True when something is selected (Copy needs it; not in password fields). */
        fun canCopy(): Boolean
        /** True when there is something to paste. */
        fun canPaste(): Boolean
    }

    var theme: KeyboardTheme? = null
    /** Select mode: arrows and Home / End extend the selection. */
    var selecting = false
        private set

    private val area = RectF()
    private var scale = 1f
    private val handler = Handler(Looper.getMainLooper())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()
    private val box = RectF()
    private var pressed: Action? = null
    /** The key let go last and when: it fades from the pressed colour back (like iOS function keys). */
    private var released: Action? = null
    private var releasedAt = 0L
    /** When Select last turned on or off: its highlight cross-fades. */
    private var selectAt = 0L
    private var pasteOk = false
    private val repeat = object : Runnable {
        override fun run() {
            val a = pressed ?: return
            callbacks.onAction(a, selecting)
            callbacks.onFeedback()
            handler.postDelayed(this, KeyboardView.REPEAT_MS)
        }
    }

    private val actions = Action.entries

    fun open() {
        selecting = false
        selectAt = 0L
        released = null
        pressed = null
        pasteOk = callbacks.canPaste()
    }

    /** The clipboard changed (e.g. after Copy): re-check whether Paste can be used. */
    fun refreshPaste() {
        pasteOk = callbacks.canPaste()
        host.invalidate()
    }

    /**
     * Where [a] is drawn. The grid has four columns; the top block is three rows high and the
     * bottom row splits the first three columns in two for start and end.
     */
    private fun rectOf(a: Action, out: RectF): RectF {
        val pad = PAD * scale
        val gap = GAP * scale
        val left = area.left + pad
        val top = area.top + pad
        val cw = (area.width() - 2 * pad - 3 * gap) / 4
        val rh = (area.bottom - pad - top - 3 * gap) / 4
        fun col(c: Int) = left + c * (cw + gap)
        fun row(rw: Int) = top + rw * (rh + gap)
        return out.apply {
            when (a) {
                Action.LEFT -> set(col(0), row(0), col(0) + cw, row(2) + rh)
                Action.RIGHT -> set(col(2), row(0), col(2) + cw, row(2) + rh)
                Action.UP -> set(col(1), row(0), col(1) + cw, row(0) + rh)
                Action.SELECT -> set(col(1), row(1), col(1) + cw, row(1) + rh)
                Action.DOWN -> set(col(1), row(2), col(1) + cw, row(2) + rh)
                Action.SELECT_ALL -> set(col(3), row(0), col(3) + cw, row(0) + rh)
                Action.COPY -> set(col(3), row(1), col(3) + cw, row(1) + rh)
                Action.PASTE -> set(col(3), row(2), col(3) + cw, row(2) + rh)
                Action.DELETE -> set(col(3), row(3), col(3) + cw, row(3) + rh)
                Action.HOME, Action.END -> {
                    // The first three columns, split in two.
                    val half = (3 * cw + 2 * gap - gap) / 2
                    val x = if (a == Action.HOME) col(0) else col(0) + half + gap
                    set(x, row(3), x + half, row(3) + rh)
                }
            }
        }
    }

    private fun enabled(a: Action) = when (a) {
        Action.COPY -> callbacks.canCopy()
        Action.PASTE -> pasteOk
        else -> true
    }

    fun draw(canvas: Canvas, area: RectF, scale: Float) {
        val t = theme ?: return
        this.area.set(area)
        this.scale = scale
        val radius = t.keyRadiusPt * scale * 1.4f
        val now = SystemClock.uptimeMillis()
        var animating = false
        // How far Select is towards its "on" look.
        val selectP = fade(now, selectAt, FADE_MS)
        val selectOn = if (selecting) selectP else 1f - selectP
        if (selectP < 1f) animating = true
        val releaseP = fade(now, releasedAt, FADE_MS)
        if (released != null && releaseP < 1f) animating = true
        for (a in actions) {
            val rect = rectOf(a, r)
            val base = if (a == Action.SELECT) blend(t.functionKey, t.accent, selectOn) else t.functionKey
            val bg = when {
                a == pressed -> t.pressedFunctionKey
                a == released && releaseP < 1f -> blend(t.pressedFunctionKey, base, releaseP)
                else -> base
            }
            fill.color = t.keyShadow
            canvas.drawRoundRect(rect.left, rect.top + scale, rect.right, rect.bottom + scale, radius, radius, fill)
            fill.color = bg
            canvas.drawRoundRect(rect, radius, radius, fill)
            val fg = when {
                a == Action.SELECT -> blend(t.text, t.accentText, selectOn)
                !enabled(a) -> t.text and 0x00FFFFFF or 0x59000000
                else -> t.text
            }
            drawContent(canvas, a, rect, fg, bg)
        }
        if (animating) host.postInvalidateOnAnimation()
    }

    private fun drawContent(canvas: Canvas, a: Action, rect: RectF, fg: Int, bg: Int) {
        val s = scale
        val cx = rect.centerX()
        val cy = rect.centerY()
        stroke.color = fg
        stroke.strokeWidth = 2f * s
        when (a) {
            Action.SELECT, Action.SELECT_ALL, Action.COPY, Action.PASTE -> {
                label.color = fg
                label.textSize = 15f * s
                canvas.drawText(a.label, cx, cy - (label.descent() + label.ascent()) / 2, label)
            }
            Action.LEFT -> chevron(canvas, cx + 2.5f * s, cy, -1, 0)
            Action.RIGHT -> chevron(canvas, cx - 2.5f * s, cy, 1, 0)
            Action.UP -> chevron(canvas, cx, cy + 2.5f * s, 0, -1)
            Action.DOWN -> chevron(canvas, cx, cy - 2.5f * s, 0, 1)
            Action.HOME -> {
                // |<
                chevron(canvas, cx + 4f * s, cy, -1, 0)
                canvas.drawLine(cx - 5f * s, cy - 7f * s, cx - 5f * s, cy + 7f * s, stroke)
            }
            Action.END -> {
                // >|
                chevron(canvas, cx - 4f * s, cy, 1, 0)
                canvas.drawLine(cx + 5f * s, cy - 7f * s, cx + 5f * s, cy + 7f * s, stroke)
            }
            Action.DELETE -> {
                glyph.color = fg
                box.set(cx - 12f * s, cy - 12f * s, cx + 12f * s, cy + 12f * s)
                Glyphs.delete(canvas, box, glyph, a == pressed, bg)
            }
        }
    }

    /** A chevron whose tip is at ([x], [y]) offset by its size towards ([dx], [dy]). */
    private fun chevron(canvas: Canvas, x: Float, y: Float, dx: Int, dy: Int) {
        val l = 6f * scale
        path.reset()
        if (dx != 0) {
            val tip = x + dx * l / 2
            path.moveTo(tip - dx * l, y - l)
            path.lineTo(tip, y)
            path.lineTo(tip - dx * l, y + l)
        } else {
            val tip = y + dy * l / 2
            path.moveTo(x - l, tip - dy * l)
            path.lineTo(x, tip)
            path.lineTo(x + l, tip - dy * l)
        }
        canvas.drawPath(path, stroke)
    }

    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val a = actionAt(e.x, e.y)?.takeIf(::enabled) ?: return
                pressed = a
                callbacks.onFeedback()
                if (a.repeats) {
                    callbacks.onAction(a, selecting)
                    handler.postDelayed(repeat, KeyboardView.REPEAT_DELAY_MS)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeat)
                val a = pressed
                release()
                if (a != null && !a.repeats && actionAt(e.x, e.y) == a) when (a) {
                    Action.SELECT -> setSelecting(!selecting)
                    else -> {
                        callbacks.onAction(a, selecting)
                        // Copying or pasting ends a selection, as in iOS's edit menu.
                        if (a == Action.COPY || a == Action.PASTE) setSelecting(false)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeat)
                release()
            }
        }
        host.invalidate()
    }

    private fun release() {
        released = pressed
        releasedAt = SystemClock.uptimeMillis()
        pressed = null
    }

    private fun setSelecting(on: Boolean) {
        if (selecting == on) return
        selecting = on
        selectAt = SystemClock.uptimeMillis()
    }

    /** 0..1 over [ms] since [since] (1 when animations are off or nothing happened). */
    private fun fade(now: Long, since: Long, ms: Long): Float =
        if (since == 0L || !ValueAnimator.areAnimatorsEnabled()) 1f else ((now - since) / ms.toFloat()).coerceIn(0f, 1f)

    private fun blend(from: Int, to: Int, t: Float): Int {
        if (t <= 0f) return from
        if (t >= 1f) return to
        fun ch(shift: Int) = (((from ushr shift) and 0xFF) + ((((to ushr shift) and 0xFF) - ((from ushr shift) and 0xFF)) * t)).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The action under the finger; gaps belong to the nearest key, so near misses still land. */
    private fun actionAt(x: Float, y: Float): Action? {
        var best: Action? = null
        var bestD = SLOP * scale
        for (a in actions) {
            val rect = rectOf(a, r)
            val dx = maxOf(rect.left - x, 0f, x - rect.right)
            val dy = maxOf(rect.top - y, 0f, y - rect.bottom)
            val d = maxOf(dx, dy)
            if (d < bestD || (d == 0f && best == null)) { best = a; bestD = d }
            if (d == 0f) return a
        }
        return best
    }

    private companion object {
        /** Key release and Select highlight fades, as the keyboard's function keys (100 ms). */
        const val FADE_MS = 100L
        const val PAD = 8f
        const val GAP = 8f
        /** How far outside a key a touch still counts as on it (half a gap). */
        const val SLOP = 5f
    }
}
