package dev.leconnn.ioskeyboard

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow

/**
 * A transparent, non-touchable window over the keyboard where [KeyboardView] draws preview
 * bubbles and accent panels. Keeping them out of the IME window lets that window end at the keys,
 * so the glass blur doesn't spill over the app.
 *
 * Normally it covers only the keyboard. Since Android 12 the system blocks touches that pass
 * through another app's window (untrusted touch occlusion), so wherever the overlay reaches over
 * the app, the app can't be tapped. It therefore reaches up only as far as a popup showing now
 * needs ([KeyboardView.popupHeadroomNeededPx]): nothing for keys below the top row, a thin strip
 * for top-row bubbles under the suggestion bar, and shrinks back afterwards.
 *
 * Resizing a window takes the system a frame or two, so the overlay grows as soon as a key is
 * touched ([hold]) and stays tall through a burst of typing, shrinking [SHRINK_DELAY_MS] after the
 * last popup. Popups are drawn from the overlay's actual size, so one can never be drawn at the
 * new offset before the window has moved (the old "jumping" bubble).
 */
@SuppressLint("ViewConstructor")
class PopupOverlay(private val keyboard: KeyboardView) : View(keyboard.context) {

    private val window = PopupWindow(this).apply {
        isTouchable = false
        isFocusable = false
        isClippingEnabled = false
        setBackgroundDrawable(null)
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        animationStyle = 0
    }
    private val location = IntArray(2)
    /** Pixels the overlay reaches above the keyboard now. */
    private var reach = 0f
    /** When a popup was last needed (touch down or drawn). */
    private var lastNeeded = 0L
    private val shrink = Runnable {
        if (keyboard.popupsVisible() || SystemClock.uptimeMillis() - lastNeeded < SHRINK_DELAY_MS) reschedule()
        // Down to what the keys on show always need (the number row's bubbles), not below.
        else setReach(kotlin.math.ceil(keyboard.standingHeadroomPx()))
    }


    init {
        keyboard.popupHost = this
        keyboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (window.isShowing) place() }
    }

    override fun onDraw(canvas: Canvas) {
        // Keys start below the headroom the window really has now, and the suggestion bar.
        val actualHeadroom = (height - keyboard.height).coerceAtLeast(0).toFloat()
        val need = keyboard.popupHeadroomNeededPx()
        if (need > 0f) hold(need)
        val standing = kotlin.math.ceil(keyboard.standingHeadroomPx())
        if (standing > reach) setReach(standing)
        // The window grows a frame after a touch: until it has room, show nothing rather than a
        // popup cut off at the top (the number row's bubble used to flash clipped first).
        if (need > actualHeadroom + 1f) { postInvalidateOnAnimation(); return }
        if (keyboard.drawPopups(canvas, actualHeadroom + keyboard.barPx)) postInvalidateOnAnimation()
    }

    /**
     * Keeps the standing room ready (the keys' bubbles need it the moment a key is touched); a
     * change of keys (number row switched, another page) grows it now or shrinks it a bit later.
     */
    fun ensureStanding() {
        if (!window.isShowing) return
        val s = kotlin.math.ceil(keyboard.standingHeadroomPx())
        if (s > reach) setReach(s) else if (s < reach) reschedule()
    }

    /** A popup reaching [px] above the keyboard is about to show or is showing: make room, keep it a while. */
    fun hold(px: Float) {
        lastNeeded = SystemClock.uptimeMillis()
        setReach(maxOf(reach, kotlin.math.ceil(px)))
        reschedule()
    }

    private fun reschedule() {
        removeCallbacks(shrink)
        postDelayed(shrink, SHRINK_DELAY_MS)
    }

    /** Reach over the app only as far, and as long, as a popup needs. */
    private fun setReach(px: Float) {
        if (reach == px) return
        reach = px
        if (window.isShowing) place()
    }

    fun show() {
        if (!keyboard.isAttachedToWindow || keyboard.width == 0) {
            keyboard.post { if (keyboard.isAttachedToWindow && keyboard.width > 0) show() }
            return
        }
        reach = maxOf(reach, kotlin.math.ceil(keyboard.standingHeadroomPx()))
        if (window.isShowing) place() else {
            val (y, w, h) = bounds()
            window.width = w
            window.height = h
            window.showAtLocation(keyboard, Gravity.NO_GRAVITY, location[0], y)
        }
    }

    fun dismiss() {
        removeCallbacks(shrink)
        reach = 0f
        window.dismiss()
    }

    private fun place() {
        val (y, w, h) = bounds()
        window.update(location[0], y, w, h)
        invalidate()
    }

    /**
     * y, width and height: the keyboard, plus the headroom above it while expanded. The popup is
     * a child of the IME window and positioned relative to it, so y is negative when expanded.
     */
    private fun bounds(): Triple<Int, Int, Int> {
        keyboard.getLocationInWindow(location)
        val h = reach.toInt()
        return Triple(location[1] - h, keyboard.width, keyboard.height + h)
    }

    private companion object {
        /**
         * Stay tall this long after the last popup, so a burst of typing doesn't resize the window
         * between letters, but short enough that a tap just above the keyboard right after typing
         * (e.g. Send) isn't blocked for long.
         */
        const val SHRINK_DELAY_MS = 250L
    }
}
