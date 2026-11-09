package dev.leconnn.ioskeyboard.emoji

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import dev.leconnn.ioskeyboard.Glyphs
import dev.leconnn.ioskeyboard.KeyboardTheme
import dev.leconnn.ioskeyboard.Prefs
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The emoji keyboard, drawn into the keys area of a host view, with a bottom bar of ABC, category
 * icons and delete. iOS style: a horizontally scrolling grid of 5 rows with pinned section titles.
 * Gboard style ([vertical]): a vertically scrolling grid as wide as the panel, each section under
 * its title. Coordinates inside the area are in points: grid 0..177, bar 177..216.
 *
 * [scrollX] is the scroll position along whichever axis scrolls; [sectionX] the section starts on it.
 */
class EmojiPanel(private val host: View, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onEmoji(emoji: String)
        fun onDelete(repeat: Int)
        fun onExit()
        fun onFeedback()
    }

    var theme: KeyboardTheme? = null
    /** Hold time before the skin-tone panel opens. */
    var longPressMs = 400L
    /** Draw with Apple's art (if the asset exists) instead of the system emoji font. */
    var appleStyle = true
    private var apple: AppleEmojiImages? = null
    private val drawRect = RectF()

    private val prefs = Prefs.get(host.context)
    private var data: EmojiData? = null
    private var loading = false
    private var sections: List<EmojiData.Section> = emptyList()

    /** Gboard style: scroll up and down instead of sideways. */
    var vertical = false
        set(value) {
            if (field == value) return
            field = value
            scroller.forceFinished(true)
            scrollX = 0f
            layout()
            host.invalidate()
        }

    private val area = RectF()
    private var scale = 1f
    private var scrollX = 0f
    /** Start x of each section in content pixels, plus the total width at the end. */
    private var sectionX = FloatArray(0)
    private val scroller = OverScroller(host.context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val greyIcons = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    // Gesture state
    private enum class Target { NONE, GRID, ABC, CATEGORY, DELETE }
    private var target = Target.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var category = -1
    private var deleteRepeats = 0
    private var tones: List<String>? = null
    /** True while the skin-tone panel is open (it draws above the keys). */
    val hasPopup get() = tones != null
    private var toneBase: EmojiData.Emoji? = null
    private var toneSelected = 0
    /** Tone panel head, relative to the top of the area. */
    private val toneRect = RectF()

    private val longPress = Runnable { openTones() }
    private val repeatDelete = object : Runnable {
        override fun run() {
            deleteRepeats++
            callbacks.onDelete(deleteRepeats)
            callbacks.onFeedback()
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    /** Call when the panel opens: loads data once and refreshes Frequently Used. */
    fun open() {
        // iOS reopens the emoji keyboard at the start (Frequently Used).
        scroller.forceFinished(true)
        scrollX = 0f
        val d = data
        if (d != null) { rebuild(d); return }
        load(show = true)
    }

    /**
     * Loads the emoji data and Apple art index in the background ahead of the first open, so
     * opening the panel doesn't wait for them (Gboard prewarms its emoji keyboard the same way).
     */
    fun prewarm() {
        if (data == null) load(show = false)
    }

    private var showWhenLoaded = false

    private fun load(show: Boolean) {
        showWhenLoaded = showWhenLoaded || show
        if (loading) return
        loading = true
        val ctx = host.context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            val loaded = EmojiData.load(ctx)
            val art = AppleEmojiImages.open(ctx)
            handler.post {
                data = loaded
                apple = art
                loading = false
                if (showWhenLoaded) { rebuild(loaded); host.invalidate() }
            }
        }
    }

    private fun rebuild(d: EmojiData) {
        val recents = prefs.emojiRecents.mapNotNull { r -> d.find(r) ?: d.sections.firstNotNullOfOrNull { s -> s.items.firstOrNull { r in it.tones } } }
        sections = buildList {
            if (recents.isNotEmpty()) add(EmojiData.Section(EmojiData.RECENTS_TITLE, EmojiData.RECENTS_ICON, recents.distinct()))
            addAll(d.sections)
        }
        layout()
    }

    /** Gboard style: emoji per row, and the width of each column. */
    private val columns get() = ((area.width() - 2 * PAD_PT * scale) / (COL_PT * scale)).toInt().coerceAtLeast(1)
    private val colW get() = (area.width() - 2 * PAD_PT * scale) / columns

    private fun layout() {
        if (vertical) {
            // Each section: its title, then rows of [columns].
            var y = 0f
            sectionX = FloatArray(sections.size + 1)
            sections.forEachIndexed { i, s ->
                sectionX[i] = y
                y += GRID_TOP_PT * scale + ceil(s.items.size / columns.toFloat()) * ROW_PT * scale + SECTION_GAP_PT * scale
            }
            sectionX[sections.size] = y
            scrollX = scrollX.coerceIn(0f, maxScroll())
            return
        }
        val col = COL_PT * scale
        var x = PAD_PT * scale
        sectionX = FloatArray(sections.size + 1)
        titlePaint.textSize = TITLE_PT * scale
        sections.forEachIndexed { i, s ->
            sectionX[i] = x
            // At least as wide as its title, so a short Frequently Used list doesn't clip it.
            val columns = ceil(s.items.size / ROWS.toFloat()) * col
            x += maxOf(columns, titlePaint.measureText(s.title.uppercase())) + SECTION_GAP_PT * scale
        }
        sectionX[sections.size] = x
        scrollX = scrollX.coerceIn(0f, maxScroll())
    }

    /** The finger's scroll position, unbounded; [scrollX] is it with the rubber band applied. */
    private var rawScroll = 0f

    /** iOS rubber band: the further past the end, the less the content follows. */
    private fun banded(raw: Float): Float {
        val dim = viewport().coerceAtLeast(1f)
        fun rubber(o: Float) = o * dim * 0.55f / (dim + 0.55f * abs(o))
        val max = maxScroll()
        return when {
            raw < 0f -> -rubber(-raw)
            raw > max -> max + rubber(raw - max)
            else -> raw
        }
    }

    /** Length of the visible grid along the scrolling axis. */
    private fun viewport() = if (vertical) barTop() else area.width()

    /** Top of the bottom bar, from the area's top: the bar keeps its height at the bottom of a taller area. */
    private fun barTop() = area.height() - (216f - BAR_TOP_PT) * scale

    private fun maxScroll() = (sectionX.lastOrNull() ?: 0f).minus(viewport()).coerceAtLeast(0f)

    // ---- drawing -------------------------------------------------------------------------

    fun draw(canvas: Canvas, rect: RectF, scale: Float) {
        val t = theme ?: return
        if (this.scale != scale || area.width() != rect.width()) {
            this.scale = scale
            area.set(rect)
            layout()
        }
        area.set(rect)
        if (scroller.computeScrollOffset()) {
            scrollX = (if (vertical) scroller.currY else scroller.currX).toFloat()
            host.postInvalidateOnAnimation()
        }
        if (sections.isEmpty()) { drawBar(canvas, t); return }
        if (vertical) { drawVertical(canvas, t); drawBar(canvas, t); return }

        val col = COL_PT * scale
        val row = ROW_PT * scale
        val gridTop = area.top + GRID_TOP_PT * scale
        canvas.save()
        canvas.clipRect(area.left, area.top, area.right, area.top + barTop())
        emojiPaint.textSize = EMOJI_PT * scale
        val fm = emojiPaint.fontMetrics
        titlePaint.textSize = TITLE_PT * scale
        titlePaint.color = t.text and 0x00FFFFFF or 0x8C000000.toInt()
        sections.forEachIndexed { si, s ->
            val start = sectionX[si] - scrollX
            val end = sectionX[si + 1] - scrollX
            if (end < 0 || start > area.width()) return@forEachIndexed
            // Pinned title: stays at the left edge while its section is on screen.
            val title = s.title.uppercase()
            val tw = titlePaint.measureText(title)
            val tx = start.coerceAtLeast(PAD_PT * scale).coerceAtMost(end - tw - SECTION_GAP_PT * scale)
            canvas.drawText(title, area.left + tx, area.top + 15f * scale, titlePaint)
            s.items.forEachIndexed { i, e ->
                val cx = start + (i / ROWS) * col + col / 2
                if (cx < -col || cx > area.width() + col) return@forEachIndexed
                val cy = gridTop + (i % ROWS) * row + row / 2
                drawEmoji(canvas, display(e), area.left + cx, cy, EMOJI_PT * scale, fm.ascent + fm.descent)
            }
        }
        canvas.restore()
        drawBar(canvas, t)
    }

    /** Gboard style: sections stacked down the panel, each title above its rows. */
    private fun drawVertical(canvas: Canvas, t: KeyboardTheme) {
        val row = ROW_PT * scale
        val cols = columns
        val cw = colW
        canvas.save()
        canvas.clipRect(area.left, area.top, area.right, area.top + barTop())
        emojiPaint.textSize = EMOJI_PT * scale
        val fm = emojiPaint.fontMetrics
        titlePaint.textSize = TITLE_PT * scale
        titlePaint.color = t.text and 0x00FFFFFF or 0x8C000000.toInt()
        val view = viewport()
        sections.forEachIndexed { si, s ->
            val start = sectionX[si] - scrollX
            val end = sectionX[si + 1] - scrollX
            if (end < 0 || start > view) return@forEachIndexed
            canvas.drawText(s.title, area.left + PAD_PT * scale + 4f * scale, area.top + start + 15f * scale, titlePaint)
            val gridTop = start + GRID_TOP_PT * scale
            s.items.forEachIndexed { i, e ->
                val cy = gridTop + (i / cols) * row + row / 2
                if (cy < -row || cy > view + row) return@forEachIndexed
                val cx = PAD_PT * scale + (i % cols) * cw + cw / 2
                drawEmoji(canvas, display(e), area.left + cx, area.top + cy, EMOJI_PT * scale, fm.ascent + fm.descent)
            }
        }
        canvas.restore()
    }

    private fun display(e: EmojiData.Emoji) = prefs.emojiTone(e.base) ?: e.base

    /** Apple art when enabled and available, else the system font. [fontShift] = ascent + descent. */
    /** Draws [emoji] centred on ([cx], [cy]) at [sizePx], with Apple's art when that's on (search results). */
    fun drawEmojiAt(canvas: Canvas, emoji: String, cx: Float, cy: Float, sizePx: Float) {
        emojiPaint.textSize = sizePx
        val fm = emojiPaint.fontMetrics
        drawEmoji(canvas, emoji, cx, cy, sizePx, fm.ascent + fm.descent)
    }

    private fun drawEmoji(canvas: Canvas, emoji: String, cx: Float, cy: Float, sizePx: Float, fontShift: Float, filter: Paint? = null) {
        val art = apple
        if (appleStyle && art != null) {
            // Apple's art fills its square; scale it to match the font's visual size.
            val half = sizePx * 0.62f
            drawRect.set(cx - half, cy - half, cx + half, cy + half)
            if (art.draw(canvas, emoji, drawRect, filter)) return
        }
        canvas.drawText(emoji, cx, cy - fontShift / 2, filter ?: emojiPaint)
    }

    private fun drawBar(canvas: Canvas, t: KeyboardTheme) {
        val top = area.top + barTop()
        val bottom = area.bottom
        val cy = (top + bottom) / 2
        barPaint.color = t.text
        barPaint.textSize = 16f * scale
        barPaint.colorFilter = null
        barPaint.alpha = 255
        val fm = barPaint.fontMetrics
        canvas.drawText("ABC", area.left + ABC_W_PT * scale / 2, cy - (fm.ascent + fm.descent) / 2, barPaint)

        val (left, slot) = iconSlots()
        val current = currentSection()
        barPaint.textSize = 17f * scale
        val ifm = barPaint.fontMetrics
        for ((i, s) in sections.withIndex()) {
            val cx = left + slot * (i + 0.5f)
            if (i == current) {
                barPaint.color = t.functionKey
                canvas.drawRoundRect(cx - 15f * scale, cy - 14f * scale, cx + 15f * scale, cy + 14f * scale, 14f * scale, 14f * scale, barPaint)
            }
            barPaint.colorFilter = if (i == current) null else greyIcons
            barPaint.alpha = if (i == current) 255 else 150
            barPaint.color = t.text
            drawEmoji(canvas, s.icon, cx, cy, 17f * scale, ifm.ascent + ifm.descent, barPaint)
        }
        barPaint.colorFilter = null
        barPaint.alpha = 255

        glyphPaint.color = t.text
        val dx = area.right - DELETE_W_PT * scale / 2
        drawRect.set(dx - 11f * scale, cy - 11f * scale, dx + 11f * scale, cy + 11f * scale)
        Glyphs.delete(canvas, drawRect, glyphPaint, target == Target.DELETE, t.functionKey)
    }

    /** Left x and width of each category icon slot in the bottom bar. */
    private fun iconSlots(): Pair<Float, Float> {
        val left = area.left + ABC_W_PT * scale
        val right = area.right - DELETE_W_PT * scale
        return left to (right - left) / sections.size.coerceAtLeast(1)
    }

    private fun currentSection(): Int {
        val x = scrollX + viewport() * (if (vertical) 0.1f else 0.25f)
        for (i in sections.indices) if (x < sectionX[i + 1]) return i
        return sections.lastIndex
    }

    private val toneHead = RectF()
    private val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Skin-tone panel above the held emoji; [top] is the area's top in the drawing canvas. */
    fun drawPopups(canvas: Canvas, top: Float) {
        val t = theme ?: return
        val list = tones ?: return
        val head = toneHead.apply { set(toneRect); offset(0f, top) }
        val p = tonePaint.apply { color = t.keyShadow }
        val r = 10f * scale
        canvas.drawRoundRect(head.left, head.top + 2f * scale, head.right, head.bottom + 2f * scale, r, r, p)
        p.color = t.popup
        canvas.drawRoundRect(head, r, r, p)
        val cw = head.width() / list.size
        emojiPaint.textSize = EMOJI_PT * scale
        val fm = emojiPaint.fontMetrics
        for ((i, e) in list.withIndex()) {
            val cx = head.left + cw * (i + 0.5f)
            if (i == toneSelected) {
                p.color = t.accent
                canvas.drawRoundRect(cx - cw / 2 + 3f * scale, head.top + 4f * scale, cx + cw / 2 - 3f * scale, head.bottom - 4f * scale, 8f * scale, 8f * scale, p)
            }
            drawEmoji(canvas, e, cx, head.centerY(), EMOJI_PT * scale, fm.ascent + fm.descent)
        }
    }

    // ---- touch ---------------------------------------------------------------------------

    /** Handles a gesture that started inside the area (view coordinates). */
    fun onTouch(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x; downY = y; lastX = x; lastY = y; dragging = false
                rawScroll = scrollX
                target = targetAt(x, y)
                when (target) {
                    Target.GRID -> handler.postDelayed(longPress, longPressMs)
                    Target.DELETE -> {
                        deleteRepeats = 0
                        callbacks.onDelete(0)
                        callbacks.onFeedback()
                        handler.postDelayed(repeatDelete, REPEAT_DELAY_MS)
                    }
                    Target.CATEGORY -> category = categoryAt(x)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (tones != null) {
                    val cw = toneRect.width() / tones!!.size
                    toneSelected = ((x - toneRect.left) / cw).toInt().coerceIn(0, tones!!.size - 1)
                } else if (target == Target.GRID) {
                    val moved = if (vertical) abs(y - downY) else abs(x - downX)
                    if (!dragging && moved > touchSlop) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        rawScroll -= if (vertical) y - lastY else x - lastX
                        scrollX = banded(rawScroll)
                    }
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeatDelete)
                val list = tones
                when {
                    list != null -> pickTone(list[toneSelected])
                    // A flick coasts on and bounces off the ends; dragged past an end, it springs back.
                    target == Target.GRID && dragging && (scrollX < 0f || scrollX > maxScroll()) ->
                        if (vertical) scroller.springBack(0, scrollX.toInt(), 0, 0, 0, maxScroll().toInt())
                        else scroller.springBack(scrollX.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                    target == Target.GRID && dragging -> velocity?.let {
                        it.computeCurrentVelocity(1000)
                        val over = (OVERSCROLL_PT * scale).toInt()
                        if (vertical) {
                            scroller.fling(0, scrollX.toInt(), 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt(), 0, over)
                        } else {
                            scroller.fling(scrollX.toInt(), 0, -it.xVelocity.toInt(), 0, 0, maxScroll().toInt(), 0, 0, over, 0)
                        }
                    }
                    target == Target.GRID -> emojiAt(x, y)?.let { commit(display(it)) }
                    target == Target.ABC && targetAt(x, y) == Target.ABC -> { callbacks.onFeedback(); callbacks.onExit() }
                    target == Target.CATEGORY && categoryAt(x) == category -> scrollToSection(category)
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeatDelete)
                endGesture()
            }
        }
        host.invalidate()
    }

    private fun endGesture() {
        velocity?.recycle()
        velocity = null
        tones = null
        toneBase = null
        target = Target.NONE
    }

    private fun targetAt(x: Float, y: Float): Target {
        val py = (y - area.top) / scale
        if (py < barTop() / scale) return Target.GRID
        val px = (x - area.left) / scale
        return when {
            px < ABC_W_PT -> Target.ABC
            px > area.width() / scale - DELETE_W_PT -> Target.DELETE
            else -> Target.CATEGORY
        }
    }

    private fun categoryAt(x: Float): Int {
        val (left, slot) = iconSlots()
        return ((x - left) / slot).toInt().coerceIn(0, sections.lastIndex)
    }

    private fun scrollToSection(i: Int) {
        if (i !in sections.indices) return
        callbacks.onFeedback()
        val dest = (sectionX[i] - (if (vertical) 0f else PAD_PT * scale)).coerceIn(0f, maxScroll())
        if (vertical) scroller.startScroll(0, scrollX.toInt(), 0, (dest - scrollX).toInt(), 300)
        else scroller.startScroll(scrollX.toInt(), 0, (dest - scrollX).toInt(), 0, 300)
        host.postInvalidateOnAnimation()
    }

    /** Gboard style: the section, index and cell top (relative to the area top) under a point. */
    private fun verticalCell(x: Float, y: Float): Triple<Int, Int, Float>? {
        val cy = y - area.top + scrollX
        val row = ROW_PT * scale
        for (si in sections.indices) {
            if (cy < sectionX[si] || cy >= sectionX[si + 1]) continue
            val local = cy - sectionX[si] - GRID_TOP_PT * scale
            if (local < 0f) return null
            val r = (local / row).toInt()
            val c = ((x - area.left - PAD_PT * scale) / colW).toInt()
            if (c !in 0 until columns) return null
            val i = r * columns + c
            if (i !in sections[si].items.indices) return null
            return Triple(si, i, sectionX[si] + GRID_TOP_PT * scale + r * row - scrollX)
        }
        return null
    }

    private fun emojiAt(x: Float, y: Float): EmojiData.Emoji? {
        if (vertical) return verticalCell(x, y)?.let { (si, i, _) -> sections[si].items[i] }
        val col = COL_PT * scale
        val row = ROW_PT * scale
        val cx = x - area.left + scrollX
        val r = ((y - area.top - GRID_TOP_PT * scale) / row).toInt()
        if (r !in 0 until ROWS) return null
        for (si in sections.indices) {
            if (cx >= sectionX[si] && cx < sectionX[si + 1]) {
                val c = ((cx - sectionX[si]) / col).toInt()
                return sections[si].items.getOrNull(c * ROWS + r)
            }
        }
        return null
    }

    private fun openTones() {
        val e = emojiAt(downX, downY) ?: return
        if (e.tones.isEmpty()) return
        val list = listOf(e.base) + e.tones
        val cw = TONE_CELL_PT * scale
        val w = cw * list.size
        val cellTop = if (vertical) {
            (verticalCell(downX, downY)?.third ?: (downY - area.top)) / scale
        } else {
            (((downY - area.top) / scale - GRID_TOP_PT).toInt() / ROW_PT.toInt()) * ROW_PT + GRID_TOP_PT
        }
        val left = (downX - area.left - w / 2).coerceIn(4f * scale, area.width() - 4f * scale - w)
        toneRect.set(area.left + left, (cellTop - 52f) * scale, area.left + left + w, (cellTop - 6f) * scale)
        tones = list
        toneBase = e
        toneSelected = list.indexOf(display(e)).coerceAtLeast(0)
        callbacks.onFeedback()
        host.invalidate()
    }

    private fun pickTone(emoji: String) {
        val base = toneBase ?: return
        prefs.setEmojiTone(base.base, emoji)
        commit(emoji)
    }

    private fun commit(emoji: String) {
        callbacks.onFeedback()
        callbacks.onEmoji(emoji)
        prefs.addEmojiRecent(emoji)
    }

    private companion object {
        /** How far a flick may bounce past either end. */
        private const val OVERSCROLL_PT = 40f
        const val ROWS = 5
        const val COL_PT = 42f
        const val ROW_PT = 31f
        const val EMOJI_PT = 27f
        const val TITLE_PT = 12.5f
        const val PAD_PT = 8f
        const val SECTION_GAP_PT = 10f
        const val GRID_TOP_PT = 22f
        const val BAR_TOP_PT = 177f
        const val ABC_W_PT = 50f
        const val DELETE_W_PT = 50f
        const val TONE_CELL_PT = 40f
        const val REPEAT_DELAY_MS = dev.leconnn.ioskeyboard.KeyboardView.REPEAT_DELAY_MS
        const val REPEAT_MS = dev.leconnn.ioskeyboard.KeyboardView.REPEAT_MS
    }
}
