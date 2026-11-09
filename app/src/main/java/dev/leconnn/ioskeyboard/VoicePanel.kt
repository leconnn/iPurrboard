package dev.leconnn.ioskeyboard

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin

/**
 * The dictation panel (toolbar mic or the emoji-key menu), drawn into the keys area like iOS's
 * dictation view: a live waveform that follows the voice, a status line, and a round mic button
 * to stop or start again. The words go into the text field as they are recognised (see the
 * service). Tapping a message such as "Allow microphone access" runs its action.
 */
class VoicePanel(private val host: View, private val callbacks: Callbacks) {

    interface Callbacks {
        /** The mic button: start listening if stopped, stop if listening. */
        fun onToggle()
        /** The message's action (e.g. open Settings to allow the microphone). */
        fun onMessageAction()
        fun onFeedback()
    }

    enum class State { IDLE, LISTENING, NO_PERMISSION, UNAVAILABLE, ERROR }

    var theme: KeyboardTheme? = null
    var state = State.IDLE
        set(value) { field = value; host.invalidate() }
    /** Shown under the waveform for [State.ERROR]. */
    var errorText = ""
    /** Input level 0..1 (from the recogniser's RMS), smoothed for drawing. */
    var level = 0f
        set(value) { field = value.coerceIn(0f, 1f); host.invalidate() }
    private var shownLevel = 0f
    private var lastFrameAt = 0L

    private val area = RectF()
    private var scale = 1f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val button = RectF()
    private var pressed = false

    fun draw(canvas: Canvas, area: RectF, scale: Float) {
        val t = theme ?: return
        this.area.set(area)
        this.scale = scale
        val s = scale
        val cx = area.centerX()
        val waveY = area.top + area.height() * 0.32f
        val frameNow = SystemClock.uptimeMillis()
        val dt = if (lastFrameAt == 0L) 16f else (frameNow - lastFrameAt).toFloat().coerceIn(1f, 50f)
        lastFrameAt = frameNow
        // Time-based smoothing, the same at any refresh rate.
        shownLevel += (level - shownLevel) * (1f - kotlin.math.exp(-dt / LEVEL_TAU_MS))
        val animate = ValueAnimator.areAnimatorsEnabled()

        // Waveform: rounded bars whose heights follow the voice, with a gentle travelling ripple.
        val bars = 23
        val gap = 7f * s
        val barW = 4f * s
        val now = SystemClock.uptimeMillis() / 1000.0
        fill.color = if (state == State.LISTENING) t.accent else t.text and 0x00FFFFFF or 0x59000000
        for (i in 0 until bars) {
            val x = cx + (i - bars / 2) * (barW + gap)
            val envelope = 1f - kotlin.math.abs(i - bars / 2f) / (bars / 2f + 1)
            val ripple = if (animate && state == State.LISTENING) (0.5f + 0.5f * sin(now * 7 + i * 0.7).toFloat()) else 0.5f
            val h = (4f + (if (state == State.LISTENING) shownLevel * 46f * envelope * (0.5f + ripple) else 0f)) * s
            canvas.drawRoundRect(x - barW / 2, waveY - h / 2, x + barW / 2, waveY + h / 2, barW / 2, barW / 2, fill)
        }

        // Status line.
        text.textSize = 15f * s
        text.color = t.text and 0x00FFFFFF or 0x99000000.toInt()
        val status = when (state) {
            State.LISTENING -> "Listening…"
            State.IDLE -> "Tap the microphone to speak"
            State.NO_PERMISSION -> "Allow microphone access"
            State.UNAVAILABLE -> "Voice typing isn't available on this device"
            State.ERROR -> errorText
        }
        if (state == State.NO_PERMISSION) text.color = t.accent
        canvas.drawText(status, cx, waveY + 44f * s, text)

        // Mic button: filled while listening (tap to stop), outlined when stopped.
        val r = 30f * s
        val by = area.bottom - r - 22f * s
        button.set(cx - r, by - r, cx + r, by + r)
        val listening = state == State.LISTENING
        fill.color = when {
            pressed -> t.pressedFunctionKey
            listening -> t.accent
            else -> t.functionKey
        }
        canvas.drawCircle(cx, by, r, fill)
        drawMic(canvas, cx, by, if (listening) t.accentText else t.text)

        if (animate && listening) host.postInvalidateDelayed(FRAME_MS)
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val s = scale
        stroke.color = color
        stroke.strokeWidth = 2.2f * s
        fill.color = color
        canvas.drawRoundRect(cx - 5f * s, cy - 13f * s, cx + 5f * s, cy + 3f * s, 5f * s, 5f * s, fill)
        canvas.drawArc(cx - 9f * s, cy - 8f * s, cx + 9f * s, cy + 8f * s, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + 8f * s, cx, cy + 13f * s, stroke)
    }

    fun onTouch(e: MotionEvent) {
        val onButton = button.contains(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = onButton; if (pressed) callbacks.onFeedback() }
            MotionEvent.ACTION_UP -> {
                if (pressed && onButton) callbacks.onToggle()
                else if (!pressed && (state == State.NO_PERMISSION || state == State.ERROR)) callbacks.onMessageAction()
                pressed = false
            }
            MotionEvent.ACTION_CANCEL -> pressed = false
        }
        host.invalidate()
    }

    private companion object {
        const val FRAME_MS = 33L
        /** The waveform follows the voice level with this time constant. */
        const val LEVEL_TAU_MS = 40f
    }
}
