package dev.leconnn.ioskeyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/*
 * iOS controls drawn in code: the switch, the slider, glass bar buttons and the SF-style chevron
 * and checkmark. They move on critically damped springs ([Spring]), so a control grabbed or
 * flipped again mid-motion carries on from where it is.
 */

internal fun Context.dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

internal fun Context.isDark() =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * A critically damped spring (Apple's damping 1.0) driven by the display's frames. [response] is
 * Apple's "response" in seconds. Retargeting keeps the current value and velocity, so motion never
 * jumps or stops dead.
 */
class Spring(private val response: Float = 0.35f, private val onUpdate: (Float) -> Unit) {
    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set
    private var lastFrame = 0L
    private var running = false
    private var onDone: (() -> Unit)? = null
    private val omega get() = (2 * Math.PI / response).toFloat()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = frameTimeNanos / 1_000_000L
            val dt = ((now - lastFrame).coerceIn(1L, 34L)) / 1000f
            lastFrame = now
            // Exact solution of the critically damped spring over dt.
            val x0 = value - target
            val w = omega
            val e = exp(-w * dt)
            val x = (x0 + (velocity + w * x0) * dt) * e
            velocity = (velocity - (velocity + w * x0) * w * dt) * e
            value = target + x
            if (abs(x) < 0.0005f && abs(velocity) < 0.01f) {
                value = target
                velocity = 0f
                running = false
                onUpdate(value)
                onDone?.let { onDone = null; it() }
                return
            }
            onUpdate(value)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Jump to [v] with no motion. */
    fun snap(v: Float) {
        stop()
        value = v; target = v; velocity = 0f
        onUpdate(v)
    }

    /** Spring to [to]; [withVelocity] (units per second) replaces the current velocity when given. */
    fun animateTo(to: Float, withVelocity: Float? = null, done: (() -> Unit)? = null) {
        target = to
        if (withVelocity != null) velocity = withVelocity
        onDone = done
        if (!animationsOn()) { snap(to); done?.invoke(); return }
        if (!running) {
            running = true
            lastFrame = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /** Stop where it is (e.g. when a finger grabs what was moving). */
    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        onDone = null
    }

    val isRunning get() = running

    /** Place it at [v] while a finger drags it, remembering the drag's velocity. */
    fun track(v: Float, vel: Float) {
        stop()
        value = v; target = v; velocity = vel
        onUpdate(v)
    }

    companion object {
        /** Android's "Remove animations" (animator scale 0) turns the motion off. */
        var animationsOn: () -> Boolean = { true }
    }
}

/** The iOS switch: 51 × 31 pt, green when on, a white knob that stretches while held. */
@SuppressLint("ViewConstructor")
class IosSwitch(context: Context) : View(context) {
    private val dark = context.isDark()
    private val onColor = 0xFF34C759.toInt()
    private val offColor = if (dark) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(context.dpf(2.5f), 0f, context.dpf(1.5f), 0x33000000)
    }
    private val rect = RectF()
    /** 0 = off, 1 = on (animated). */
    private var pos = 0f
    /** 0..1: how stretched the knob is (while pressed). */
    private var stretch = 0f
    private val posSpring = Spring(0.32f) { pos = it; invalidate() }
    private val stretchSpring = Spring(0.25f) { stretch = it; invalidate() }

    var isChecked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
    }

    fun setChecked(v: Boolean, animate: Boolean = false) {
        if (v == isChecked && !animate) { posSpring.snap(if (v) 1f else 0f); return }
        isChecked = v
        if (animate) posSpring.animateTo(if (v) 1f else 0f) else posSpring.snap(if (v) 1f else 0f)
    }

    /** Flip it as a tap would: animate and report the change. */
    fun toggle() {
        if (!isEnabled) return
        setChecked(!isChecked, animate = true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        SwitchClock.at = SystemClock.uptimeMillis()
        onChange?.invoke(isChecked)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 51 × 31 pt plus room for the knob's shadow.
        setMeasuredDimension(context.dpf(55f).toInt(), context.dpf(35f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = context.dpf(51f)
        val h = context.dpf(31f)
        val left = (width - w) / 2
        val top = (height - h) / 2
        rect.set(left, top, left + w, top + h)
        track.color = blend(offColor, onColor, pos)
        canvas.drawRoundRect(rect, h / 2, h / 2, track)
        val inset = context.dpf(2f)
        val d = h - 2 * inset
        val kw = d + context.dpf(7f) * stretch
        val x = left + inset + (w - 2 * inset - kw) * pos
        rect.set(x, top + inset, x + kw, top + inset + d)
        canvas.drawRoundRect(rect, d / 2, d / 2, knob)
    }

    private var downX = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                stretchSpring.animateTo(1f)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > ViewConfiguration.get(context).scaledTouchSlop) dragging = true
                if (dragging) {
                    // Sliding past the middle flips it, as on iOS.
                    val want = e.x > width / 2f
                    if (want != isChecked) {
                        setChecked(want, animate = true)
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                stretchSpring.animateTo(0f)
                if (dragging) {
                    SwitchClock.at = SystemClock.uptimeMillis()
                    onChange?.invoke(isChecked)
                } else {
                    toggle()
                }
            }
            MotionEvent.ACTION_CANCEL -> stretchSpring.animateTo(0f)
        }
        return true
    }
}

/**
 * The iOS 26 slider: a thin track filled in blue up to a white capsule knob. The knob is dragged
 * (from wherever it was grabbed); taps on the track do nothing, as in UISlider. Values 0..1.
 */
@SuppressLint("ViewConstructor")
class IosSlider(context: Context) : View(context) {
    private val dark = context.isDark()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt() }
    private val rest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF3A3A3C.toInt() else 0xFFE3E3E8.toInt() }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(context.dpf(3f), 0f, context.dpf(1.5f), 0x30000000)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0xFF636366.toInt() else 0xFFC7C7CC.toInt() }
    private val rect = RectF()
    private var grow = 0f
    private val growSpring = Spring(0.25f) { grow = it; invalidate() }

    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** Positions of tick marks under the track (0..1), e.g. a Default stop. */
    var ticks: FloatArray = FloatArray(0)
    /** Called while dragging. */
    var onChange: ((Float) -> Unit)? = null
    /** Called when the finger lifts. */
    var onRelease: ((Float) -> Unit)? = null

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    private val knobW get() = context.dpf(38f)
    private val knobH get() = context.dpf(24f)
    private val trackLeft get() = paddingLeft + knobW / 2
    private val trackRight get() = width - paddingRight - knobW / 2

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dpf(44f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val th = context.dpf(6f)
        val x = trackLeft + (trackRight - trackLeft) * value
        rect.set(trackLeft - th / 2, cy - th / 2, trackRight + th / 2, cy + th / 2)
        canvas.drawRoundRect(rect, th / 2, th / 2, rest)
        rect.set(trackLeft - th / 2, cy - th / 2, x, cy + th / 2)
        canvas.drawRoundRect(rect, th / 2, th / 2, fill)
        for (t in ticks) {
            val tx = trackLeft + (trackRight - trackLeft) * t
            canvas.drawCircle(tx, cy + context.dpf(12f), context.dpf(1.6f), tick)
        }
        // The knob swells a little while held.
        val kw = knobW * (1f + 0.12f * grow)
        val kh = knobH * (1f + 0.12f * grow)
        rect.set(x - kw / 2, cy - kh / 2, x + kw / 2, cy + kh / 2)
        canvas.drawRoundRect(rect, kh / 2, kh / 2, knob)
    }

    private var grabOffset = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = trackLeft + (trackRight - trackLeft) * value
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the knob (with a finger-sized margin) can be grabbed.
                if (abs(e.x - x) > knobW / 2 + context.dpf(10f)) return false
                grabOffset = e.x - x
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                growSpring.animateTo(1f)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                value = (e.x - grabOffset - trackLeft) / (trackRight - trackLeft)
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                growSpring.animateTo(0f)
                onRelease?.invoke(value)
            }
        }
        return true
    }
}

/** SF Symbols-like chevron.right (navigation rows), drawn as a stroke. */
class ChevronDrawable(private val color: Int, private val sizePx: Int, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@ChevronDrawable.color
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val h = b.height() * 0.62f
        val w = h * 0.52f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        path.reset()
        path.moveTo(cx - w / 2, cy - h / 2)
        path.lineTo(cx + w / 2, cy)
        path.lineTo(cx - w / 2, cy + h / 2)
        canvas.drawPath(path, paint)
    }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable API")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** SF Symbols-like checkmark (selected choice), drawn as a stroke. */
class CheckmarkDrawable(private val color: Int, private val sizePx: Int, private val stroke: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@CheckmarkDrawable.color
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) * 0.78f
        val l = b.exactCenterX() - s / 2; val t = b.exactCenterY() - s / 2
        path.reset()
        path.moveTo(l + s * 0.06f, t + s * 0.55f)
        path.lineTo(l + s * 0.38f, t + s * 0.86f)
        path.lineTo(l + s * 0.96f, t + s * 0.12f)
        canvas.drawPath(path, paint)
    }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable API")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * An iOS 26 bar button: a Liquid Glass circle (back chevron, plus) or capsule (a word such as
 * Save). It dims and shrinks a touch while pressed.
 */
@SuppressLint("ViewConstructor")
class GlassButton(context: Context, private val kind: Kind, private val text: String = "") : View(context) {
    enum class Kind { BACK, PLUS, TEXT }

    private val dark = context.isDark()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xCC2C2C2E.toInt() else 0xD9FFFFFF.toInt()
        setShadowLayer(context.dpf(8f), 0f, context.dpf(2f), if (dark) 0x40000000 else 0x1F000000)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(0.7f)
        color = if (dark) 0x33FFFFFF else 0x14000000
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
        textSize = context.dpf(17f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()
    private var press = 0f
    private val pressSpring = Spring(0.2f) { press = it; invalidate() }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        contentDescription = when (kind) { Kind.BACK -> "Back"; Kind.PLUS -> "Add"; Kind.TEXT -> text }
    }

    var textEnabled = true
        set(v) { field = v; isEnabled = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = context.dpf(44f)
        val w = if (kind == Kind.TEXT) max(h, label.measureText(text) + context.dpf(32f)) else h
        // Room for the shadow around the glass.
        setMeasuredDimension((w + context.dpf(8f)).toInt(), (h + context.dpf(8f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val pad = context.dpf(4f)
        val s = 1f - 0.06f * press
        canvas.save()
        canvas.scale(s, s, width / 2f, height / 2f)
        rect.set(pad, pad, width - pad, height - pad)
        val r = rect.height() / 2
        canvas.drawRoundRect(rect, r, r, bg)
        canvas.drawRoundRect(rect, r, r, rim)
        val cx = rect.centerX(); val cy = rect.centerY()
        val a = if (isEnabled) (255 * (1f - 0.35f * press)).toInt() else 90
        when (kind) {
            Kind.BACK -> {
                val h = context.dpf(15f); val w = h * 0.52f
                path.reset()
                path.moveTo(cx + w / 2 - context.dpf(1f), cy - h / 2)
                path.lineTo(cx - w / 2 - context.dpf(1f), cy)
                path.lineTo(cx + w / 2 - context.dpf(1f), cy + h / 2)
                glyph.alpha = a
                canvas.drawPath(path, glyph)
            }
            Kind.PLUS -> {
                val h = context.dpf(8f)
                glyph.alpha = a
                canvas.drawLine(cx - h, cy, cx + h, cy, glyph)
                canvas.drawLine(cx, cy - h, cx, cy + h, glyph)
            }
            Kind.TEXT -> {
                label.alpha = a
                canvas.drawText(text, cx, cy - (label.descent() + label.ascent()) / 2, label)
            }
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressSpring.animateTo(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressSpring.animateTo(0f)
        }
        return super.onTouchEvent(e)
    }
}

/** Channel-wise blend of two opaque colours. */
internal fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
