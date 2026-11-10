package dev.leconnn.ioskeyboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.leconnn.ioskeyboard.ui.IosList
import dev.leconnn.ioskeyboard.ui.SwitchClock
import dev.leconnn.ioskeyboard.ui.ThemeMode

/**
 * Settings > Toolbar Buttons: the order of the buttons in the suggestion bar's toolbar (drag a row
 * by its ≡ handle) and which are shown. A hidden button stays reachable from the
 * emoji-key menu. The same order can also be changed in the bar itself by holding a button.
 */
class ToolbarSettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemeMode.wrap(newBase))

    override fun onResume() {
        super.onResume()
        // Light / Dark may have changed on the Appearance page while this screen was underneath.
        ThemeMode.refreshIfChanged(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        scroll = ScrollView(this)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ThemeMode.styleBars(this)
        render()
    }

    private fun render() {
        val list = IosList(this)
        scroll.setBackgroundColor(list.background)
        list.backButton("Settings") { finish() }
        list.largeTitle("Toolbar")
        list.section()
        list.switchRow("Toolbar", prefs.toolbar) {
            prefs.toolbar = it
            scroll.postDelayed({ if (!isFinishing) render() }, SwitchClock.ANIM_MS)
        }
        list.footer("Buttons in the suggestion bar when there are no suggestions; otherwise the › button at its left shows them.")
        list.section("Order")
        // Builds without GIFs have no GIF button to order.
        val order = prefs.toolbarOrder.filter { BuildConfig.GIFS || it != ToolbarItem.GIF }
        val hidden = prefs.toolbarHidden
        cells.clear()
        order.forEachIndexed { i, item ->
            val row = row(list, item, i, order.size, item !in hidden)
            list.customRow(row)
            // The row's wrapper is what moves while dragging.
            cells.add(row.parent as View)
        }
        list.footer("Drag ≡ to reorder. A hidden Clipboard button stays in the menu you get by holding the emoji key. " +
            "Emoji is in one place only: on here, it moves to the toolbar; off here, or with the toolbar off, it's on the keyboard. " +
            "Text Editing adds a panel with arrows, select, copy and paste, " +
            (if (BuildConfig.GIFS) "GIFs a GIF search (GIPHY) and " else "and ") +
            "Voice Typing dictation; off here, they're off everywhere.")
        scroll.removeAllViews()
        scroll.addView(list.root)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun row(list: IosList, item: ToolbarItem, index: Int, count: Int, shown: Boolean): View {
        fun text(s: String, size: Float, color: Int) = TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            gravity = Gravity.CENTER
        }
        val handle = text("≡", 22f, list.label).apply { setPadding(0, 0, list.dp(14), 0) }
        val label = text(item.label, 17f, list.label).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }
        // Emoji is either here or on the keyboard, never both: its switch moves it.
        // Text Editing's switch turns the panel itself on or off (toolbar and emoji key menu).
        val toggle = if (item == ToolbarItem.EMOJI) {
            list.iosSwitch(prefs.emojiInToolbar) { on -> prefs.emojiInToolbar = on }
        } else if (item == ToolbarItem.TEXT_EDITING) {
            list.iosSwitch(prefs.textEditing) { on -> prefs.textEditing = on }
        } else if (item == ToolbarItem.GIF) {
            list.iosSwitch(prefs.gifs) { on -> prefs.gifs = on }
        } else if (item == ToolbarItem.VOICE) {
            list.iosSwitch(prefs.voiceTyping) { on -> prefs.voiceTyping = on }
        } else {
            list.iosSwitch(shown) { on -> prefs.toolbarHidden = if (on) prefs.toolbarHidden - item else prefs.toolbarHidden + item }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = list.dp(44)
            addView(handle)
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }
        // Drag by the handle (see drag()).
        var startY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    row.parent.requestDisallowInterceptTouchEvent(true)
                    pickUp(list, index)
                }
                MotionEvent.ACTION_MOVE -> drag(index, count, e.rawY - startY, row)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop(index)
            }
            true
        }
        return row
    }

    // ---- drag to reorder, iOS style: the row lifts, the others slide aside to open a gap ----

    /** Row wrappers of the Order section, in order. */
    private val cells = ArrayList<View>()
    private var dragTarget = -1

    /** Distance between two rows (row plus separator). */
    private fun pitch(): Float = if (cells.size >= 2) (cells[1].top - cells[0].top).toFloat() else cells[0].height.toFloat()

    /** The card's separators (everything in it that isn't a row). */
    private fun separators(): List<View> {
        val card = cells.firstOrNull()?.parent as? ViewGroup ?: return emptyList()
        return (0 until card.childCount).map { card.getChildAt(it) }.filter { it !in cells }
    }

    private fun pickUp(list: IosList, index: Int) {
        val cell = cells.getOrNull(index) ?: return
        dragTarget = index
        cell.background = ColorDrawable(list.cardColor)
        cell.elevation = list.dp(10).toFloat()
        cell.animate().scaleX(1.03f).scaleY(1.03f).setDuration(LIFT_MS).start()
        separators().forEach { it.animate().alpha(0f).setDuration(LIFT_MS).start() }
        cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun drag(index: Int, count: Int, dy: Float, anchor: View) {
        val cell = cells.getOrNull(index) ?: return
        val pitch = pitch()
        // Follow the finger, but not far past the first or last place.
        val y = dy.coerceIn(-index * pitch - pitch / 3, (count - 1 - index) * pitch + pitch / 3)
        cell.translationY = y
        val target = (index + Math.round(y / pitch)).coerceIn(0, count - 1)
        if (target == dragTarget) return
        dragTarget = target
        anchor.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        // Rows between the old and new place slide one step to open a gap.
        for (j in cells.indices) {
            if (j == index) continue
            val shift = when {
                index < target && j in index + 1..target -> -pitch
                index > target && j in target until index -> pitch
                else -> 0f
            }
            cells[j].animate().translationY(shift).setDuration(SLIDE_MS).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun drop(index: Int) {
        val cell = cells.getOrNull(index) ?: return
        val target = dragTarget.takeIf { it >= 0 } ?: index
        // Glide into the gap, then save; the rebuilt list matches what is on screen, so nothing jumps.
        cell.animate().translationY((target - index) * pitch()).scaleX(1f).scaleY(1f)
            .setDuration(SLIDE_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                cell.elevation = 0f
                if (target != index) {
                    move(index, target)
                } else {
                    cell.background = null
                    separators().forEach { it.animate().alpha(1f).setDuration(LIFT_MS).start() }
                }
            }.start()
        dragTarget = -1
    }

    private companion object {
        const val LIFT_MS = 120L
        const val SLIDE_MS = 180L
    }

    private fun move(from: Int, to: Int) {
        val order = prefs.toolbarOrder.toMutableList()
        if (from !in order.indices || to !in order.indices || isFinishing) return
        order.add(to, order.removeAt(from))
        prefs.toolbarOrder = order
        render()
    }
}
