package dev.leconnn.ioskeyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * One screen of the settings app: a scrolling grouped list under an iOS 26 navigation bar. The
 * bar is transparent over the top of the page; once content scrolls under it a soft edge fade
 * appears behind it (the scroll edge effect). Pages with a large title ([largeTitle]) show the
 * title in the bar only once the large one has scrolled away; other pages show it from the start.
 */
@SuppressLint("ViewConstructor")
class IosPage(context: Context, val title: String, val largeTitle: Boolean, private val background: Int) : FrameLayout(context) {
    val scroll = ScrollView(context).apply {
        clipToPadding = false
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    private val bar = FrameLayout(context)
    private val edge = View(context)
    private val titleView = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(if (context.isDark()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private var back: GlassButton? = null
    private var right: View? = null
    private var topInset = 0
    /** Bottom of the large title in content coordinates (set by the page's content), for the fade. */
    var largeTitleBottom = Int.MAX_VALUE / 2
        set(v) { field = v; onScrolled(scroll.scrollY) }

    private val barH get() = context.dpf(BAR_DP).toInt()

    init {
        setBackgroundColor(background)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        edge.background = EdgeFade(background) { (topInset + barH).toFloat() }
        edge.alpha = 0f
        // The fade covers the status bar too, so it's the page's child, under the bar.
        addView(edge, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        val side = context.dpf(64f).toInt()
        bar.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, barH, Gravity.BOTTOM).apply {
            leftMargin = side; rightMargin = side; bottomMargin = context.dpf(EDGE_DP).toInt()
        })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        titleView.alpha = if (largeTitle) 0f else 1f
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> onScrolled(y) }
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            topInset = bars.top
            bar.layoutParams = (bar.layoutParams as LayoutParams).apply { height = topInset + barH + context.dpf(EDGE_DP).toInt() }
            edge.layoutParams = (edge.layoutParams as LayoutParams).apply { height = topInset + barH + context.dpf(EDGE_DP * 2).toInt() }
            bar.setPadding(bars.left, topInset, bars.right, 0)
            // The list scrolls under the bar and (as on iOS) behind the keyboard, padded so every
            // row can still be scrolled into view.
            scroll.setPadding(bars.left, topInset + barH, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    /** The glass back button at the left of the bar. */
    fun setBack(onBack: () -> Unit) {
        val b = GlassButton(context, GlassButton.Kind.BACK)
        b.setOnClickListener { onBack() }
        bar.addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = context.dpf(12f).toInt(); bottomMargin = context.dpf(EDGE_DP - 4f).toInt()
        })
        back = b
    }

    /** A glass button at the right of the bar (e.g. + or Save). */
    fun setRight(button: GlassButton?) {
        right?.let { bar.removeView(it) }
        right = button ?: return
        bar.addView(button, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = context.dpf(12f).toInt(); bottomMargin = context.dpf(EDGE_DP - 4f).toInt()
        })
    }

    fun setContent(v: View) {
        scroll.removeAllViews()
        scroll.addView(v)
        post { onScrolled(scroll.scrollY) }
    }

    private fun onScrolled(y: Int) {
        // The edge fade comes in over the first few points of scrolling.
        edge.alpha = (y / context.dpf(16f)).coerceIn(0f, 1f)
        if (largeTitle) {
            // The bar's title appears as the large title slides under the bar.
            val start = largeTitleBottom - context.dpf(24f)
            titleView.alpha = ((y - start) / context.dpf(16f)).coerceIn(0f, 1f)
        }
    }

    companion object {
        /** Height of the bar's content (buttons, title) below the status bar. */
        const val BAR_DP = 52f
        /** Extra fade below the bar, where content softens out under it. */
        const val EDGE_DP = 14f
    }
}

/**
 * The scroll edge effect behind the bar: nearly solid page colour down to the bar's bottom
 * ([solidTo]), then a soft fade, so content dissolves as it slides under the bar.
 */
private class EdgeFade(private val color: Int, private val solidTo: () -> Float) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint()
    private var shaderFor = -1f
    override fun draw(canvas: android.graphics.Canvas) {
        val h = bounds.height().toFloat()
        val solid = solidTo().coerceAtMost(h)
        if (solid != shaderFor) {
            val rgb = color and 0x00FFFFFF
            paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h,
                intArrayOf(color, (0xF0 shl 24) or rgb, (0xB0 shl 24) or rgb, rgb),
                floatArrayOf(0f, solid / h * 0.8f, solid / h, 1f), android.graphics.Shader.TileMode.CLAMP)
            shaderFor = solid
        }
        canvas.drawRect(bounds, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable API")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * A navigation stack like UINavigationController: pushed pages slide in from the right over the
 * previous one, which shifts left and dims. Pop with the back button, the system back gesture,
 * or by swiping right anywhere on the page (as in iOS 26): the page follows the finger and, when
 * let go, carries on at the finger's speed (projected, as in Apple's sample code) to close or
 * settle back. A page in motion can be grabbed again.
 */
class NavStack(context: Context) : FrameLayout(context) {
    val pages = ArrayList<IosPage>()
    /** Called after a page was popped and removed (the new top is [top]). */
    var onPopped: ((IosPage) -> Unit)? = null
    /** Called just before the page under the top one is revealed by a swipe (to refresh it). */
    var onRevealing: ((IosPage) -> Unit)? = null

    val top get() = pages.lastOrNull()
    private val dark = context.isDark()

    /** 0 = top page in place, 1 = top page off to the right. */
    private var progress = 0f
    private val spring = Spring(0.38f) { applyProgress(it) }
    private var popping: IosPage? = null

    fun push(page: IosPage, animated: Boolean) {
        finishMotion()
        val under = top
        pages.add(page)
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        page.requestApplyInsets()
        if (under == null || !animated) {
            under?.visibility = View.INVISIBLE
            spring.snap(0f)
            return
        }
        under.visibility = View.VISIBLE
        spring.snap(1f)
        spring.animateTo(0f) { under.visibility = View.INVISIBLE; under.foreground = null }
    }

    /** Pops the top page (animated); returns false at the root. */
    fun pop(animated: Boolean = true, velocity: Float? = null): Boolean {
        if (pages.size < 2) return false
        val leaving = top!!
        val under = pages[pages.size - 2]
        if (under.visibility != View.VISIBLE) { onRevealing?.invoke(under); under.visibility = View.VISIBLE }
        popping = leaving
        if (!animated) { spring.snap(1f); removeTop(); return true }
        spring.animateTo(1f, velocity) { removeTop() }
        return true
    }

    // ---- the system back gesture (Android 14+ predictive back) drives the same motion ----

    private var backTracking = false

    /** The system back swipe started: show the page underneath. */
    fun beginBack() {
        if (pages.size < 2) return
        finishMotion()
        val under = pages[pages.size - 2]
        onRevealing?.invoke(under)
        under.visibility = View.VISIBLE
        backTracking = true
    }

    /** The system back swipe moved; [p] is its progress (0..1). */
    fun updateBack(p: Float) {
        if (!backTracking) return
        // The system's progress saturates early; the page follows a little behind the finger.
        spring.track(p * 0.9f, 0f)
    }

    /** The system back swipe ended: [commit] pops, otherwise the page settles back. */
    fun endBack(commit: Boolean) {
        if (!backTracking) { if (commit) pop(); return }
        backTracking = false
        if (commit) pop(animated = true) else spring.animateTo(0f) {
            if (pages.size >= 2) { pages[pages.size - 2].visibility = View.INVISIBLE; pages[pages.size - 2].foreground = null }
        }
    }

    private fun removeTop() {
        val leaving = popping ?: return
        popping = null
        pages.remove(leaving)
        removeView(leaving)
        top?.let { it.translationX = 0f; it.foreground = null; it.visibility = View.VISIBLE }
        progress = 0f
        onPopped?.invoke(leaving)
    }

    /** Ends any motion at once (e.g. a push during a pop). */
    private fun finishMotion() {
        if (!spring.isRunning) return
        val t = spring.target
        spring.stop()
        if (popping != null && t == 1f) removeTop() else applyProgress(t)
    }

    private fun applyProgress(p: Float) {
        progress = p
        val n = pages.size
        if (n == 0) return
        val w = width.toFloat()
        val topPage = pages[n - 1]
        topPage.translationX = p * w
        // The page coming to the front casts a soft shadow on the one behind.
        topPage.elevation = if (p > 0f && p < 1f) context.dpf(6f) else 0f
        if (n >= 2) {
            val under = pages[n - 2]
            under.translationX = -(1f - p) * w * PARALLAX
            val dim = ((1f - p) * (if (dark) 0.35f else 0.12f) * 255).toInt()
            under.foreground = if (dim > 0) ColorDrawable(dim shl 24) else null
        }
    }

    // ---- swipe back ------------------------------------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var startProgress = 0f
    private var swiping = false
    private var blocked = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (pages.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x
                swiping = false
                // Controls that drag sideways keep their touches.
                blocked = hitsHorizontalControl(top!!, e.x, e.y)
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                // A page still moving is caught by the finger.
                if (spring.isRunning && popping == null) {
                    spring.stop(); startProgress = progress; swiping = true; return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (blocked) return false
                val dx = e.x - downX
                val dy = e.y - downY
                if (dx > slop && dx > abs(dy) * 1.4f) {
                    swiping = true
                    startProgress = progress
                    downX = e.x
                    onRevealing?.invoke(pages[pages.size - 2])
                    pages[pages.size - 2].visibility = View.VISIBLE
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!swiping) return false
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val p = (startProgress + (e.x - downX) / width).coerceIn(0f, 1f)
                tracker?.computeCurrentVelocity(1000)
                spring.track(p, (tracker?.xVelocity ?: 0f) / width)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swiping = false
                tracker?.computeCurrentVelocity(1000)
                val v = tracker?.xVelocity ?: 0f
                tracker?.recycle(); tracker = null
                // Where the page would come to rest at this speed (Apple's projection).
                val rate = 0.998f
                val projected = progress * width + (v / 1000f) * rate / (1f - rate)
                if (e.actionMasked == MotionEvent.ACTION_UP && projected > width / 2f && v > -200f) {
                    pop(animated = true, velocity = v / width)
                } else {
                    spring.animateTo(0f, v / width) {
                        if (pages.size >= 2) { pages[pages.size - 2].visibility = View.INVISIBLE; pages[pages.size - 2].foreground = null }
                    }
                }
            }
        }
        return true
    }

    /** Is (x, y) on something that is dragged sideways (a slider, a switch, a text field)? */
    private fun hitsHorizontalControl(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is IosSlider || v is IosSwitch || v is HorizontalScrollView || v is android.widget.SeekBar ||
            (v is EditText && v.hasFocus())) return true
        if (v is ViewGroup) {
            val sx = if (v is ScrollView) v.scrollX else 0
            val sy = if (v is ScrollView) v.scrollY else 0
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                val cx = x + sx - c.left - c.translationX
                val cy = y + sy - c.top - c.translationY
                if (cx >= 0 && cy >= 0 && cx < c.width && cy < c.height && hitsHorizontalControl(c, cx, cy)) return true
            }
        }
        return false
    }

    private companion object {
        /** How far the page underneath sits to the left (fraction of the width), as in iOS. */
        const val PARALLAX = 0.3f
    }
}
