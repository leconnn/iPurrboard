package dev.leconnn.ioskeyboard

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.leconnn.ioskeyboard.text.ClipHistory
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The clipboard panel (opened from the toolbar or the emoji-key menu), drawn into the keys area
 * like the emoji panel. Its title, back button and Clear are in the suggestion bar (see
 * KeyboardView). Tap a clip to paste it; hold it for Pin / Unpin and Delete.
 *
 *  - Gboard style: "Pinned" and "Recent" sections of cards in two columns, each card as tall as its
 *    text (up to five lines) and packed into the shorter column, pinned cards marked with a pin, and
 *    a tip about pinning above them.
 *  - iOS style ([listStyle]): one inset-grouped list of rows, pinned rows marked with a dot.
 *
 * Coordinates are in pixels from the top of the area.
 */
class ClipboardPanel(private val host: View, private val callbacks: Callbacks) {

    interface Callbacks {
        fun clips(): List<ClipHistory.Clip>
        fun onPaste(clip: ClipHistory.Clip)
        /** Held card: show its Pin / Delete menu anchored at [anchor] (pixels, relative to the area top). */
        fun onCardMenu(clip: ClipHistory.Clip, anchor: RectF)
        fun onClear()
        fun onFeedback()
    }

    var theme: KeyboardTheme? = null
    var longPressMs = 400L

    /** iOS style: one column of rows in a rounded group, instead of Gboard's sections of cards. */
    var listStyle = true
        set(value) {
            if (field == value) return
            field = value
            layouts.clear()
            gridDirty = true
            cardX.clear(); cardY.clear(); cardAt.clear(); ghosts.clear()
            scrollY = 0f
            host.invalidate()
        }

    private val prefs = Prefs.get(host.context)
    private val area = RectF()
    private var scale = 1f
    private var scrollY = 0f
    private var items: List<ClipHistory.Clip> = emptyList()
    private val layouts = HashMap<Long, StaticLayout>()
    private var layoutWidth = 0
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private val groupRect = RectF()
    private val groupPath = Path()

    // Screenshot thumbnails, loaded in the background the first time a card is drawn.
    private val thumbs = HashMap<String, Bitmap>()
    private val thumbAt = HashMap<String, Long>()
    private val thumbsLoading = HashSet<String>()
    private val thumbsFailed = HashSet<String>()
    private val thumbWorker = Executors.newSingleThreadExecutor()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val clipPath = Path()
    private val listThumb = RectF()

    // Gboard layout: each card's place in the content (unscrolled), the section labels and the tip.
    private val gridPos = ArrayList<RectF>()
    private val sectionLabels = ArrayList<Pair<String, Float>>()
    private var tipLayout: StaticLayout? = null
    private var tipY = 0f
    private var gridH = 0f
    private var gridDirty = true
    private var gridWidth = -1f

    private enum class Target { NONE, CARD, LIST }
    private var target = Target.NONE
    private var card = -1
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var scrolling = false
    private var longPressed = false
    private val longPress = Runnable { onLongPress() }

    // iOS scrolling: momentum after a flick, and rubber-banding past the ends that springs back.
    private val scroller = OverScroller(host.context)
    private var velocity: VelocityTracker? = null
    /** The finger's scroll position, unbounded; [scrollY] is it with the rubber band applied. */
    private var rawScroll = 0f

    // Cards glide to new places when the list changes (pin, delete), new ones fade in, and removed
    // ones fade out where they were.
    private val cardY = HashMap<Long, Float>()
    private val cardX = HashMap<Long, Float>()
    private val cardAt = HashMap<Long, Long>()
    private class Ghost(val clip: ClipHistory.Clip, val rect: RectF, val at: Long)
    private val ghosts = ArrayList<Ghost>()
    /** The card let go last, fading from the pressed colour back. */
    private var releasedCard = -1L
    private var releasedAt = 0L

    /** Call when the panel opens: reloads the clips and scrolls to the top. */
    fun open() {
        items = callbacks.clips()
        scroller.forceFinished(true)
        scrollY = 0f
        layouts.clear()
        gridDirty = true
        cardX.clear(); cardY.clear(); cardAt.clear(); ghosts.clear()
    }

    /** True when Clear would remove something (pinned clips stay). */
    val canClear get() = items.any { !it.pinned }

    /** Removes the unpinned clips (the bar's Clear). */
    fun clear() = callbacks.onClear()

    fun refresh() {
        val next = callbacks.clips()
        val now = SystemClock.uptimeMillis()
        val kept = next.map { it.id }.toSet()
        val animate = ValueAnimator.areAnimatorsEnabled() && area.width() > 0f
        if (animate) {
            for ((i, c) in items.withIndex()) {
                if (c.id !in kept && (listStyle || i < gridPos.size)) ghosts.add(Ghost(c, RectF(cardRect(i, r)), now))
            }
            for (c in next) if (c.id !in cardY && items.isNotEmpty()) cardAt[c.id] = now
        }
        items = next
        // Pinning changes a card's text width (room for the pin), so lay the text out again.
        layouts.clear()
        gridDirty = true
        host.invalidate()
    }

    private val listTop get() = 0f
    private val listBottom get() = area.height()
    private val rowH get() = ROW_PT * scale
    private val listW get() = area.width() - 2 * GAP_PT * scale
    private val colW get() = (area.width() - 3 * GAP_PT * scale) / 2
    private val contentH get() = if (listStyle) items.size * rowH + 2 * GAP_PT * scale else gridH
    private val maxScroll get() = (contentH - (listBottom - listTop)).coerceAtLeast(0f)

    /** Card [i]'s rect relative to the area top (before clipping to the list). */
    private fun cardRect(i: Int, out: RectF): RectF {
        if (!listStyle) {
            return out.apply { set(gridPos.getOrNull(i) ?: RectF()); offset(0f, -scrollY) }
        }
        val left = GAP_PT * scale
        val top = listTop + GAP_PT * scale + i * rowH - scrollY
        return out.apply { set(left, top, left + listW, top + rowH) }
    }

    /** The clip's text, laid out for its card or row (cached). */
    private fun textLayout(c: ClipHistory.Clip): StaticLayout = layouts.getOrPut(c.id) {
        text.textSize = (if (listStyle) 15f else 14f) * scale
        val w = if (listStyle) listW - 2 * LIST_PAD_PT * scale - (if (c.pinned) 12f * scale else 0f)
        else colW - 2 * CARD_PAD_PT * scale - (if (c.pinned) PIN_ROOM_PT * scale else 0f)
        // The preview: no leading / trailing blank space; the list flows it onto one line (a blank
        // line after "Hi Aleksandra," would use up its two lines). Pasting still uses c.text as is.
        val head = if (c.text.length > PREVIEW_MAX_CHARS) c.text.substring(0, PREVIEW_MAX_CHARS) else c.text
        val preview = if (listStyle) head.replace(WHITESPACE_RUN, " ").trim() else head.trim()
        StaticLayout.Builder.obtain(preview, 0, preview.length, text, w.toInt().coerceAtLeast(1))
            .setMaxLines(if (listStyle) 2 else 5).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    /** Gboard: the tip, then "Pinned" and "Recent", each packing its cards into the shorter column. */
    private fun layoutGrid() {
        gridDirty = false
        gridWidth = area.width()
        val gap = GAP_PT * scale
        var y = gap
        sectionLabels.clear()
        while (gridPos.size < items.size) gridPos.add(RectF())
        while (gridPos.size > items.size) gridPos.removeAt(gridPos.size - 1)
        tipLayout = null
        if (items.isNotEmpty() && items.none { it.pinned }) {
            label.textSize = 13f * scale
            val tip = "Touch and hold a clip to pin it. Unpinned clips will be deleted after " +
                "${prefs.clipboardRetention.label.lowercase()}."
            val tl = StaticLayout.Builder.obtain(tip, 0, tip.length, TextPaint(label).apply { typeface = Typeface.DEFAULT },
                (area.width() - 2 * (gap + 4f * scale)).toInt().coerceAtLeast(1)).build()
            tipLayout = tl
            tipY = y
            y += tl.height + gap
        }
        fun section(title: String, indices: List<Int>) {
            if (indices.isEmpty()) return
            sectionLabels.add(title to y + 14f * scale)
            y += SECTION_LABEL_PT * scale
            val bottoms = floatArrayOf(y, y)
            for (i in indices) {
                // Screenshots get a fixed, portrait-ish card; text cards fit their text.
                val h = if (items[i].image != null) colW * IMAGE_ASPECT
                else maxOf(textLayout(items[i]).height + 2 * CARD_PAD_PT * scale, MIN_CARD_PT * scale)
                val col = if (bottoms[0] <= bottoms[1]) 0 else 1
                val left = gap + col * (colW + gap)
                gridPos[i].set(left, bottoms[col], left + colW, bottoms[col] + h)
                bottoms[col] += h + gap
            }
            y = maxOf(bottoms[0], bottoms[1]) + gap / 2
        }
        section("Pinned", items.indices.filter { items[it].pinned })
        section("Recent", items.indices.filter { !items[it].pinned })
        gridH = y + gap
    }

    /** Time since the last frame, for the glide. */
    private var frameDt = 16f
    private var lastFrameAt = 0L

    fun draw(canvas: Canvas, area: RectF, scale: Float) {
        val t = theme ?: return
        if (this.scale != scale) { layouts.clear(); gridDirty = true }
        this.area.set(area)
        this.scale = scale
        val w = listW.toInt()
        if (w != layoutWidth) { layouts.clear(); layoutWidth = w; gridDirty = true }
        if (!listStyle && (gridDirty || gridWidth != area.width())) layoutGrid()
        val now = SystemClock.uptimeMillis()
        frameDt = if (lastFrameAt == 0L) 16f else (now - lastFrameAt).toFloat().coerceIn(1f, 50f)
        lastFrameAt = now
        var animating = false
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            animating = true
        } else if (target != Target.LIST && !scrolling) {
            scrollY = scrollY.coerceIn(0f, maxScroll)
        }
        canvas.save()
        canvas.translate(area.left, area.top)

        // Cards, clipped to the list area.
        canvas.save()
        canvas.clipRect(0f, listTop, area.width(), listBottom)
        if (items.isEmpty()) drawEmpty(canvas, t)
        if (!listStyle) drawGridLabels(canvas, t)
        // iOS list: one rounded group behind the rows, which are clipped to it.
        if (listStyle && items.isNotEmpty()) {
            val first = cardRect(0, r).top
            val last = cardRect(items.lastIndex, r).bottom
            groupRect.set(GAP_PT * scale, first, area.width() - GAP_PT * scale, last)
            fill.color = t.letterKey
            canvas.drawRoundRect(groupRect, GROUP_RADIUS_PT * scale, GROUP_RADIUS_PT * scale, fill)
            groupPath.reset()
            groupPath.addRoundRect(groupRect, GROUP_RADIUS_PT * scale, GROUP_RADIUS_PT * scale, Path.Direction.CW)
            canvas.clipPath(groupPath)
        }
        // Cards removed by Clear / Delete fade out where they were.
        ghosts.removeAll { now - it.at >= MOVE_MS || !ValueAnimator.areAnimatorsEnabled() }
        for (g in ghosts) {
            val p = (now - g.at) / MOVE_MS.toFloat()
            drawCard(canvas, t, g.clip, g.rect, 1f - p, pressed = false, index = -1)
            animating = true
        }
        for ((i, c) in items.withIndex()) {
            val slot = cardRect(i, r)
            // Glide from where the card was drawn last towards its slot.
            val px = cardX[c.id]
            val py = cardY[c.id]
            if (px != null && py != null && ValueAnimator.areAnimatorsEnabled() && (abs(px - slot.left) > 0.5f || abs(py - (slot.top + scrollY)) > 0.5f)) {
                // Time-based, so the glide takes as long at 120 Hz as at 60 Hz.
                val k = 1f - kotlin.math.exp(-frameDt / GLIDE_TAU_MS)
                val nx = px + (slot.left - px) * k
                val ny = py + (slot.top + scrollY - py) * k
                slot.offsetTo(nx, ny - scrollY)
                animating = true
            }
            cardX[c.id] = slot.left
            cardY[c.id] = slot.top + scrollY
            if (slot.bottom < listTop || slot.top > listBottom) continue
            val born = cardAt[c.id]
            val alpha = if (born == null) 1f else ((now - born) / MOVE_MS.toFloat()).coerceIn(0f, 1f).also {
                if (it >= 1f) cardAt.remove(c.id) else animating = true
            }
            drawCard(canvas, t, c, slot, alpha, pressed = target == Target.CARD && card == i && !scrolling, released = c.id == releasedCard, index = i)
            if (c.id == releasedCard && now - releasedAt < RELEASE_MS) animating = true
        }
        canvas.restore()

        canvas.restore()
        if (animating) host.postInvalidateOnAnimation()
    }

    /** Nothing copied yet: a short title and what will appear here. */
    private fun drawEmpty(canvas: Canvas, t: KeyboardTheme) {
        val cx = area.width() / 2
        val cy = (listTop + listBottom) / 2
        label.textAlign = Paint.Align.CENTER
        label.textSize = 16f * scale
        label.color = t.text
        canvas.drawText("Your clipboard is empty", cx, cy - 4f * scale, label)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13f * scale
        text.color = t.text and 0x00FFFFFF or 0x99000000.toInt()
        canvas.drawText("Text you copy is kept here for ${prefs.clipboardRetention.label.lowercase()}.", cx, cy + 18f * scale, text)
        text.textAlign = Paint.Align.LEFT
        label.textAlign = Paint.Align.LEFT
    }

    /** Gboard: the pinning tip and the "Pinned" / "Recent" headings, scrolled with the cards. */
    private fun drawGridLabels(canvas: Canvas, t: KeyboardTheme) {
        val grey = t.text and 0x00FFFFFF or 0x99000000.toInt()
        val x = GAP_PT * scale + 4f * scale
        tipLayout?.let { tl ->
            canvas.save()
            canvas.translate(x, tipY - scrollY)
            tl.paint.color = grey
            tl.draw(canvas)
            canvas.restore()
        }
        label.textSize = 13f * scale
        label.color = grey
        for ((title, y) in sectionLabels) canvas.drawText(title, x, y - scrollY, label)
    }

    private fun drawCard(canvas: Canvas, t: KeyboardTheme, c: ClipHistory.Clip, rect: RectF, alpha: Float, pressed: Boolean, released: Boolean = false, index: Int) {
        val layer = if (alpha < 1f) canvas.saveLayerAlpha(rect.left - 4f, rect.top - 4f, rect.right + 4f, rect.bottom + 4f, (alpha * 255).toInt()) else -1
        val rp = ((SystemClock.uptimeMillis() - releasedAt) / RELEASE_MS.toFloat()).coerceIn(0f, 1f)
        val fading = released && rp < 1f && ValueAnimator.areAnimatorsEnabled()
        fill.color = when {
            pressed -> t.pressedLetterKey
            fading -> blend(t.pressedLetterKey, t.letterKey, rp)
            else -> t.letterKey
        }
        if (listStyle) {
            // Rows sit on the group's background; only a pressed row is filled. iOS hairline
            // separators start at the text.
            if (pressed || fading) canvas.drawRect(rect, fill)
            if (index > 0) {
                fill.color = t.text and 0x00FFFFFF or 0x26000000
                canvas.drawRect(rect.left + LIST_PAD_PT * scale, rect.top, rect.right, rect.top + maxOf(1f, 0.5f * scale), fill)
            }
        } else {
            canvas.drawRoundRect(rect, CARD_RADIUS_PT * scale, CARD_RADIUS_PT * scale, fill)
        }
        if (c.image != null) {
            drawImage(canvas, t, c, rect)
            if (layer >= 0) canvas.restoreToCount(layer)
            return
        }
        val layout = textLayout(c)
        layout.paint.color = t.text
        canvas.save()
        // List rows centre their text vertically; cards start at the top.
        if (listStyle) canvas.translate(rect.left + LIST_PAD_PT * scale, rect.centerY() - layout.height / 2f)
        else canvas.translate(rect.left + CARD_PAD_PT * scale, rect.top + CARD_PAD_PT * scale)
        layout.draw(canvas)
        canvas.restore()
        if (c.pinned) {
            if (listStyle) {
                fill.color = t.accent
                canvas.drawCircle(rect.right - LIST_PAD_PT * scale, rect.centerY(), 3.5f * scale, fill)
            } else {
                drawPin(canvas, rect.right - 13f * scale, rect.top + 13f * scale, t.text and 0x00FFFFFF or 0xB3000000.toInt())
            }
        }
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    /**
     * A screenshot: Gboard style fills the card with it (cropped to fit); iOS style shows a
     * thumbnail at the start of the row and "Screenshot" beside it.
     */
    private fun drawImage(canvas: Canvas, t: KeyboardTheme, c: ClipHistory.Clip, rect: RectF) {
        val uri = c.image ?: return
        val bmp = thumb(uri)
        val box = if (listStyle) {
            val s = rect.height() - 2 * LIST_THUMB_INSET_PT * scale
            listThumb.apply { set(rect.left + LIST_PAD_PT * scale, rect.centerY() - s / 2, rect.left + LIST_PAD_PT * scale + s, rect.centerY() + s / 2) }
        } else rect
        val radius = (if (listStyle) 6f else CARD_RADIUS_PT) * scale
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        if (bmp != null) {
            // Centre-crop the thumbnail into the box.
            val scaleFit = maxOf(box.width() / bmp.width, box.height() / bmp.height)
            val w = (box.width() / scaleFit).toInt()
            val h = (box.height() / scaleFit).toInt()
            val left = (bmp.width - w) / 2
            val top = if (listStyle) (bmp.height - h) / 2 else 0
            src.set(left, top, left + w, top + h)
            // Fade in on arrival (over the placeholder) instead of popping.
            val at = thumbAt[uri]
            val p = if (at == null || !ValueAnimator.areAnimatorsEnabled()) 1f
            else ((SystemClock.uptimeMillis() - at) / THUMB_FADE_MS.toFloat()).coerceIn(0f, 1f)
            if (p < 1f) {
                fill.color = t.pressedLetterKey
                canvas.drawRect(box, fill)
                host.postInvalidateOnAnimation()
            } else {
                thumbAt.remove(uri)
            }
            imagePaint.alpha = (p * 255).toInt()
            canvas.drawBitmap(bmp, src, box, imagePaint)
            imagePaint.alpha = 255
        } else {
            fill.color = t.pressedLetterKey
            canvas.drawRect(box, fill)
        }
        canvas.restore()
        if (listStyle || bmp == null) {
            label.textSize = (if (listStyle) 15f else 13f) * scale
            label.color = if (listStyle) t.text else t.text and 0x00FFFFFF or 0x99000000.toInt()
            val label0 = if (uri in thumbsFailed) "Screenshot unavailable" else "Screenshot"
            if (listStyle) {
                canvas.drawText(label0, box.right + 12f * scale, rect.centerY() - (label.descent() + label.ascent()) / 2, label)
            } else {
                label.textAlign = Paint.Align.CENTER
                canvas.drawText(label0, rect.centerX(), rect.centerY() - (label.descent() + label.ascent()) / 2, label)
                label.textAlign = Paint.Align.LEFT
            }
        }
        if (c.pinned) {
            if (listStyle) {
                fill.color = t.accent
                canvas.drawCircle(rect.right - LIST_PAD_PT * scale, rect.centerY(), 3.5f * scale, fill)
            } else {
                // A white pin on a dark dot, readable over any screenshot.
                fill.color = 0x99000000.toInt()
                canvas.drawCircle(rect.right - 13f * scale, rect.top + 13f * scale, 10f * scale, fill)
                drawPin(canvas, rect.right - 13f * scale, rect.top + 13f * scale, 0xFFFFFFFF.toInt())
            }
        }
    }

    /** The thumbnail for [uri] if loaded; otherwise starts loading it and returns null. */
    private fun thumb(uri: String): Bitmap? {
        thumbs[uri]?.let { return it }
        if (uri in thumbsLoading || uri in thumbsFailed) return null
        thumbsLoading.add(uri)
        val px = (THUMB_PT * scale).toInt().coerceAtLeast(64)
        val ctx = host.context
        thumbWorker.execute {
            val bmp = Screenshots.thumbnail(ctx, uri, px)
            handler.post {
                thumbsLoading.remove(uri)
                if (bmp != null) {
                    if (thumbs.size >= MAX_THUMBS) thumbs.clear()
                    thumbs[uri] = bmp
                    thumbAt[uri] = SystemClock.uptimeMillis()
                } else {
                    thumbsFailed.add(uri)
                }
                host.invalidate()
            }
        }
        return null
    }

    /** A small push pin, tilted like Gboard's, centred on ([cx], [cy]). */
    private fun drawPin(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val s = scale
        canvas.save()
        canvas.rotate(45f, cx, cy)
        fill.color = color
        canvas.drawRoundRect(cx - 2.6f * s, cy - 6f * s, cx + 2.6f * s, cy - 0.5f * s, 1f * s, 1f * s, fill)
        canvas.drawRoundRect(cx - 4.5f * s, cy - 1.2f * s, cx + 4.5f * s, cy + 0.6f * s, 0.9f * s, 0.9f * s, fill)
        stroke.color = color
        stroke.strokeWidth = 1.3f * s
        canvas.drawLine(cx, cy + 0.6f * s, cx, cy + 6f * s, stroke)
        canvas.restore()
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) = (((from ushr shift) and 0xFF) + ((((to ushr shift) and 0xFF) - ((from ushr shift) and 0xFF)) * t)).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** iOS rubber band: the further past the end, the less the content follows. */
    private fun rubber(over: Float): Float {
        val dim = area.height().coerceAtLeast(1f)
        return over * dim * 0.55f / (dim + 0.55f * abs(over))
    }

    private fun banded(raw: Float): Float = when {
        raw < 0f -> -rubber(-raw)
        raw > maxScroll -> maxScroll + rubber(raw - maxScroll)
        else -> raw
    }

    fun onTouch(e: MotionEvent) {
        val x = e.x - area.left
        val y = e.y - area.top
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touching a moving list stops it, as on iOS.
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                rawScroll = scrollY
                downX = x; downY = y; lastY = y
                scrolling = false
                longPressed = false
                card = -1
                target = when {
                    y in listTop..listBottom -> cardAt(x, y).let { card = it; if (it >= 0) Target.CARD else Target.LIST }
                    else -> Target.NONE
                }
                if (target == Target.CARD) handler.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (!scrolling && (target == Target.CARD || target == Target.LIST) && abs(y - downY) > slop) {
                    scrolling = true
                    handler.removeCallbacks(longPress)
                }
                if (scrolling) {
                    rawScroll -= y - lastY
                    scrollY = banded(rawScroll)
                    lastY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (scrolling) {
                    // Hand the finger's speed to the scroll: a flick coasts on, and past an end
                    // the list springs back.
                    val v = velocity?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
                    val y0 = scrollY.toInt()
                    if (scrollY < 0f || scrollY > maxScroll) {
                        scroller.springBack(0, y0, 0, 0, 0, maxScroll.toInt())
                    } else {
                        scroller.fling(0, y0, 0, -v.toInt(), 0, 0, 0, maxScroll.toInt(), 0, (OVERSCROLL_PT * scale).toInt())
                    }
                }
                if (!scrolling && !longPressed) when (target) {
                    Target.CARD -> items.getOrNull(card)?.let { callbacks.onFeedback(); callbacks.onPaste(it) }
                    else -> Unit
                }
                if (target == Target.CARD && !scrolling) items.getOrNull(card)?.let {
                    releasedCard = it.id
                    releasedAt = SystemClock.uptimeMillis()
                }
                target = Target.NONE
                scrolling = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                target = Target.NONE
                if (scrollY < 0f || scrollY > maxScroll) scroller.springBack(0, scrollY.toInt(), 0, 0, 0, maxScroll.toInt())
                scrolling = false
                velocity?.recycle(); velocity = null
            }
        }
        host.invalidate()
    }

    private fun cardAt(x: Float, y: Float): Int {
        for (i in items.indices) if (cardRect(i, r).contains(x, y)) return i
        return -1
    }

    private fun onLongPress() {
        val c = items.getOrNull(card) ?: return
        longPressed = true
        callbacks.onFeedback()
        callbacks.onCardMenu(c, RectF(cardRect(card, r)))
        target = Target.NONE
        host.invalidate()
    }

    private companion object {
        /** Cards gliding, fading in and out when the list changes. */
        const val MOVE_MS = 220L
        /** A preview never shows more than this much of a clip (five lines at most). */
        const val PREVIEW_MAX_CHARS = 1000
        val WHITESPACE_RUN = Regex("""\s+""")
        /** Cards gliding to a new place: time constant of the exponential ease. */
        const val GLIDE_TAU_MS = 47f
        const val RELEASE_MS = 100L
        const val OVERSCROLL_PT = 40f
        /** Gboard cards: padding, corner, smallest height, room for the pin, section heading. */
        const val CARD_PAD_PT = 12f
        const val CARD_RADIUS_PT = 10f
        const val MIN_CARD_PT = 44f
        const val PIN_ROOM_PT = 14f
        const val SECTION_LABEL_PT = 22f
        /** iOS list row, its text inset and the group corner. */
        const val ROW_PT = 52f
        const val LIST_PAD_PT = 16f
        const val GROUP_RADIUS_PT = 12f
        const val GAP_PT = 8f
        /** Screenshot cards: height as a share of the width; thumbnail size; list thumbnail inset. */
        const val IMAGE_ASPECT = 1.25f
        const val THUMB_PT = 200f
        const val LIST_THUMB_INSET_PT = 6f
        const val MAX_THUMBS = 24
        const val THUMB_FADE_MS = 150L
    }
}
