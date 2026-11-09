package dev.leconnn.ioskeyboard

import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The GIF panel (toolbar GIF button or the emoji-key menu), drawn into the keys area like the
 * other panels: a "Search GIPHY" field at the top and an animated two-column grid of GIFs, each as
 * tall as its aspect ratio. Trending GIFs show until a search; tapping the field starts one (the
 * keys come back and the suggestion bar becomes the search field, see KeyboardView). Tap a GIF to
 * send it. Scrolls like iOS: momentum and a rubber band at the ends.
 *
 * [horizontal] is the iOS style, after Messages' #images: two rows of GIFs scrolling sideways
 * under a "Find images" field. ([scrollY] is then the sideways scroll.)
 */
class GifPanel(private val host: View, private val callbacks: Callbacks) {

    interface Callbacks {
        /** The search field was tapped. */
        fun onSearch()
        fun onPick(gif: Giphy.Gif)
        fun onFeedback()
    }

    var theme: KeyboardTheme? = null

    /** The current search, or null for trending GIFs. */
    var query: String? = null
        private set

    /** iOS (#images) style: two rows scrolling sideways instead of Gboard's two columns. */
    var horizontal = false
        set(value) {
            if (field == value) return
            field = value
            scroller.forceFinished(true)
            scrollY = 0f
            laidOutFor = -1f
            host.invalidate()
        }

    private val area = RectF()
    private var scale = 1f
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(3)
    private var generation = 0

    private var gifs: List<Giphy.Gif> = emptyList()
    private var loading = false
    private var failed = false
    private val images = HashMap<String, Drawable>()
    /** When each image arrived, to fade it in instead of popping. */
    private val imageAt = HashMap<String, Long>()
    private val imagesLoading = HashSet<String>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val r = RectF()
    private val pos = ArrayList<RectF>()
    private var contentH = 0f
    private var laidOutFor = -1f

    // Scrolling and touch.
    private var scrollY = 0f
    private var rawScroll = 0f
    private val scroller = OverScroller(host.context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var downY = 0f
    private var lastY = 0f
    private var scrolling = false
    private var pressed = -1
    private var fieldPressed = false
    private var clearPressed = false

    /** Opens on trending GIFs (or reloads them). */
    fun open() = load(null)

    /** Shows results for [q]. */
    fun search(q: String) = load(q.trim().ifEmpty { null })

    fun close() {
        generation++
        images.values.forEach { (it as? AnimatedImageDrawable)?.stop() }
        images.clear()
        imageAt.clear()
        imagesLoading.clear()
    }

    private fun load(q: String?) {
        query = q
        val gen = ++generation
        gifs = emptyList()
        pos.clear()
        laidOutFor = -1f
        scroller.forceFinished(true)
        scrollY = 0f
        failed = false
        loading = Giphy.configured
        host.invalidate()
        if (!Giphy.configured) return
        worker.execute {
            val ctx = host.context
            val result = runCatching { if (q == null) Giphy.trending(ctx) else Giphy.search(ctx, q) }
            handler.post {
                if (gen != generation) return@post
                loading = false
                gifs = result.getOrDefault(emptyList())
                failed = result.isFailure
                laidOutFor = -1f
                host.invalidate()
            }
        }
    }

    private val fieldH get() = FIELD_PT * scale
    private val gridTop get() = PAD_PT * scale * 2 + fieldH
    private val maxScroll get() = (contentH - if (horizontal) area.width() else area.height() - gridTop).coerceAtLeast(0f)

    /** Two columns; each GIF goes into the shorter one, sized by its aspect ratio. */
    private fun layout() {
        laidOutFor = area.width()
        if (horizontal) { layoutRows(); return }
        val gap = PAD_PT * scale
        val colW = (area.width() - 3 * gap) / 2
        val bottoms = floatArrayOf(0f, 0f)
        pos.clear()
        for (g in gifs) {
            val h = colW * g.previewHeight / g.previewWidth.coerceAtLeast(1).toFloat()
            val col = if (bottoms[0] <= bottoms[1]) 0 else 1
            val left = gap + col * (colW + gap)
            pos.add(RectF(left, bottoms[col], left + colW, bottoms[col] + h))
            bottoms[col] += h + gap
        }
        contentH = maxOf(bottoms[0], bottoms[1]) + gap
    }

    /** iOS style: two rows; each GIF goes into the shorter row, as wide as its aspect ratio. */
    private fun layoutRows() {
        val gap = ROW_GAP_PT * scale
        val pad = PAD_PT * scale
        val rowH = (area.height() - gridTop - pad - gap) / 2
        val rights = floatArrayOf(pad, pad)
        pos.clear()
        for (g in gifs) {
            val w = (rowH * g.previewWidth / g.previewHeight.coerceAtLeast(1).toFloat()).coerceIn(rowH * 0.6f, rowH * 2.2f)
            val row = if (rights[0] <= rights[1]) 0 else 1
            val top = row * (rowH + gap)
            pos.add(RectF(rights[row], top, rights[row] + w, top + rowH))
            rights[row] += w + gap
        }
        contentH = maxOf(rights[0], rights[1]) + pad - gap
    }

    fun draw(canvas: Canvas, area: RectF, scale: Float) {
        val t = theme ?: return
        this.area.set(area)
        this.scale = scale
        if (laidOutFor != area.width()) layout()
        var animating = false
        if (scroller.computeScrollOffset()) {
            scrollY = (if (horizontal) scroller.currX else scroller.currY).toFloat()
            animating = true
        } else if (!scrolling) {
            scrollY = scrollY.coerceIn(0f, maxScroll)
        }
        canvas.save()
        canvas.translate(area.left, area.top)
        drawField(canvas, t)

        // The grid, clipped below the field.
        canvas.save()
        canvas.clipRect(0f, gridTop, area.width(), area.height())
        val message = when {
            !Giphy.configured -> "Add a GIPHY API key to use GIFs"
            loading -> "Loading…"
            failed -> "Couldn't load GIFs. Check your connection."
            gifs.isEmpty() -> "No GIFs found"
            else -> null
        }
        if (message != null) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = 15f * scale
            text.typeface = Typeface.DEFAULT
            text.color = t.text and 0x00FFFFFF or 0x99000000.toInt()
            canvas.drawText(message, area.width() / 2, (gridTop + area.height()) / 2, text)
        }
        val radius = (if (horizontal) 5f else 8f) * scale
        for ((i, g) in gifs.withIndex()) {
            val p = pos.getOrNull(i) ?: break
            r.set(p)
            if (horizontal) r.offset(-scrollY, gridTop) else r.offset(0f, gridTop - scrollY)
            if (r.bottom < gridTop || r.top > area.height() || r.right < 0f || r.left > area.width()) continue
            canvas.save()
            clip.reset()
            clip.addRoundRect(r, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            val d = image(g)
            // The placeholder stays under an image fading in.
            val fade = d?.let { fadeIn(g.id) } ?: 0f
            if (fade < 1f) {
                fill.color = t.letterKey
                canvas.drawRect(r, fill)
            }
            if (d != null) {
                d.setBounds(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                d.alpha = (fade * 255).toInt()
                d.draw(canvas)
                if (d is AnimatedImageDrawable || fade < 1f) animating = true
            }
            if (i == pressed && !scrolling) {
                fill.color = 0x40000000
                canvas.drawRect(r, fill)
            }
            canvas.restore()
        }
        canvas.restore()
        canvas.restore()
        // Animated GIFs need fresh frames; ~30 fps is plenty and kinder to the battery.
        if (animating) host.postInvalidateDelayed(FRAME_MS)
    }

    /** The search field: a rounded pill with a magnifier and the query (or "Search GIPHY"), plus × to clear. */
    private fun drawField(canvas: Canvas, t: KeyboardTheme) {
        val pad = PAD_PT * scale
        r.set(pad, pad, area.width() - pad, pad + fieldH)
        fill.color = if (fieldPressed) t.pressedLetterKey else t.letterKey
        canvas.drawRoundRect(r, fieldH / 2, fieldH / 2, fill)
        val cy = r.centerY()
        glyph.color = t.text and 0x00FFFFFF or 0x99000000.toInt()
        glyph.style = Paint.Style.STROKE
        glyph.strokeWidth = 1.6f * scale
        glyph.strokeCap = Paint.Cap.ROUND
        val mx = r.left + 18f * scale
        canvas.drawCircle(mx, cy - 1f * scale, 5f * scale, glyph)
        canvas.drawLine(mx + 3.6f * scale, cy + 2.6f * scale, mx + 7f * scale, cy + 6f * scale, glyph)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 15f * scale
        text.typeface = Typeface.DEFAULT
        val q = query
        text.color = if (q == null) t.text and 0x00FFFFFF or 0x99000000.toInt() else t.text
        canvas.drawText(q ?: if (horizontal) "Find images" else "Search GIPHY", r.left + 32f * scale,
            cy - (text.descent() + text.ascent()) / 2, text)
        if (q != null) {
            // × clears the search and goes back to trending GIFs.
            val cx = r.right - 18f * scale
            fill.color = if (clearPressed) t.text and 0x00FFFFFF or 0x66000000 else t.text and 0x00FFFFFF or 0x40000000
            canvas.drawCircle(cx, cy, 9f * scale, fill)
            glyph.color = t.letterKey
            val s = 3.5f * scale
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, glyph)
            canvas.drawLine(cx - s, cy + s, cx + s, cy - s, glyph)
        }
        glyph.style = Paint.Style.FILL
    }

    /** 0..1 over [FADE_MS] since the image arrived (1 with animations off). */
    private fun fadeIn(id: String): Float {
        val at = imageAt[id] ?: return 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return 1f
        val p = ((android.os.SystemClock.uptimeMillis() - at) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (p >= 1f) imageAt.remove(id)
        return p
    }

    /** The GIF's preview if loaded; otherwise starts loading it. */
    private fun image(g: Giphy.Gif): Drawable? {
        images[g.id]?.let { return it }
        if (g.id in imagesLoading) return null
        imagesLoading.add(g.id)
        val gen = generation
        worker.execute {
            val bytes = Giphy.download(g.previewUrl)
            val d: Drawable? = bytes?.let { b ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= 28) {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(b)))
                    } else {
                        BitmapFactory.decodeByteArray(b, 0, b.size)?.let { BitmapDrawable(host.resources, it) }
                    }
                }.getOrNull()
            }
            handler.post {
                if (gen != generation) return@post
                imagesLoading.remove(g.id)
                if (d == null) return@post
                if (images.size >= MAX_IMAGES) {
                    images.values.forEach { (it as? AnimatedImageDrawable)?.stop() }
                    images.clear()
                }
                images[g.id] = d
                imageAt[g.id] = android.os.SystemClock.uptimeMillis()
                if (d is AnimatedImageDrawable && ValueAnimator.areAnimatorsEnabled()) d.start()
                host.invalidate()
            }
        }
        return null
    }

    fun onTouch(e: MotionEvent) {
        val x = e.x - area.left
        val y = e.y - area.top
        val pad = PAD_PT * scale
        val inField = y in pad..(pad + fieldH)
        val onClear = inField && query != null && x > area.width() - pad - 36f * scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downY = along(x, y); lastY = downY
                rawScroll = scrollY
                scrolling = false
                clearPressed = onClear
                fieldPressed = inField && !onClear
                pressed = if (inField) -1 else gifAt(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val a = along(x, y)
                if (!scrolling && abs(a - downY) > slop && !fieldPressed && !clearPressed) {
                    scrolling = true
                    pressed = -1
                }
                if (scrolling) {
                    rawScroll -= a - lastY
                    scrollY = banded(rawScroll)
                    lastY = a
                }
            }
            MotionEvent.ACTION_UP -> {
                if (scrolling) {
                    val v = velocity?.run { computeCurrentVelocity(1000); if (horizontal) xVelocity else yVelocity } ?: 0f
                    if (scrollY < 0f || scrollY > maxScroll) springBack()
                    else if (horizontal) scroller.fling(scrollY.toInt(), 0, -v.toInt(), 0, 0, maxScroll.toInt(), 0, 0, (OVERSCROLL_PT * scale).toInt(), 0)
                    else scroller.fling(0, scrollY.toInt(), 0, -v.toInt(), 0, 0, 0, maxScroll.toInt(), 0, (OVERSCROLL_PT * scale).toInt())
                } else when {
                    clearPressed && onClear -> { callbacks.onFeedback(); load(null) }
                    fieldPressed && inField -> { callbacks.onFeedback(); callbacks.onSearch() }
                    pressed >= 0 && gifAt(x, y) == pressed -> gifs.getOrNull(pressed)?.let { callbacks.onFeedback(); callbacks.onPick(it) }
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (scrollY < 0f || scrollY > maxScroll) springBack()
                endTouch()
            }
        }
        host.invalidate()
    }

    /** The touch's position along the scrolling direction. */
    private fun along(x: Float, y: Float) = if (horizontal) x else y

    private fun springBack() {
        if (horizontal) scroller.springBack(scrollY.toInt(), 0, 0, maxScroll.toInt(), 0, 0)
        else scroller.springBack(0, scrollY.toInt(), 0, 0, 0, maxScroll.toInt())
    }

    private fun endTouch() {
        scrolling = false
        pressed = -1
        fieldPressed = false
        clearPressed = false
        velocity?.recycle()
        velocity = null
    }

    private fun gifAt(x: Float, y: Float): Int {
        if (y < gridTop) return -1
        val cx = if (horizontal) x + scrollY else x
        val cy = if (horizontal) y - gridTop else y - gridTop + scrollY
        for (i in pos.indices) if (pos[i].contains(cx, cy)) return i
        return -1
    }

    /** iOS rubber band: the further past the end, the less the content follows. */
    private fun banded(raw: Float): Float {
        val dim = (if (horizontal) area.width() else area.height()).coerceAtLeast(1f)
        fun rubber(o: Float) = o * dim * 0.55f / (dim + 0.55f * abs(o))
        return when {
            raw < 0f -> -rubber(-raw)
            raw > maxScroll -> maxScroll + rubber(raw - maxScroll)
            else -> raw
        }
    }

    private companion object {
        const val FIELD_PT = 36f
        const val PAD_PT = 8f
        /** iOS style: the thin gap between GIFs in the rows. */
        const val ROW_GAP_PT = 3f
        const val OVERSCROLL_PT = 40f
        const val FRAME_MS = 33L
        /** Images fade in when they arrive, as on iOS. */
        const val FADE_MS = 150L
        const val MAX_IMAGES = 60
    }
}
