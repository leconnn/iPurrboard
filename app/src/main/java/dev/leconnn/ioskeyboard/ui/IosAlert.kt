package dev.leconnn.ioskeyboard.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.leconnn.ioskeyboard.Prefs

/**
 * An iOS alert, used for every dialog in the app. It follows Settings > Appearance:
 *
 *  - Classic: the familiar iOS alert, a 270 pt card with 14 pt corners, centred bold title and
 *    message, and buttons separated by hairlines (side by side for two, stacked for more).
 *  - Liquid Glass (iOS 26): a wider, rounder card with a light rim and capsule buttons, the
 *    preferred one filled blue.
 *
 * Text is blue, the preferred action bold, destructive actions red. It appears the iOS way, from
 * slightly larger and transparent, and fades out when dismissed. Usage:
 * `IosAlert(activity).title("…").message("…").action("Cancel", Style.CANCEL).action("Reset", Style.DESTRUCTIVE) { … }.show()`
 */
class IosAlert(private val activity: Activity) {

    enum class Style { DEFAULT, CANCEL, PREFERRED, DESTRUCTIVE }

    private class Action(val label: String, val style: Style, val onClick: (() -> Unit)?)

    private var title: String? = null
    private var message: String? = null
    private val fields = ArrayList<EditText>()
    private val actions = ArrayList<Action>()

    private val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val glass = Prefs.get(activity).appearance == Prefs.Appearance.GLASS
    private val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    private val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    private val label = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val separator = if (dark) 0x995B5B60.toInt() else 0x4D3C3C43

    fun title(t: String) = apply { title = t }
    fun message(m: String) = apply { message = m }

    /** A text field under the message (iOS alerts with input); returns it to read later. */
    fun field(hint: String, text: String = "", type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES): EditText {
        val f = EditText(activity).apply {
            this.hint = hint
            setText(text)
            setSelection(text.length)
            inputType = type
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (glass) 16f else 14f)
            setTextColor(label)
            setHintTextColor(if (dark) 0xFF8D8D93.toInt() else 0xFF8E8E93.toInt())
            setPadding(dp(if (glass) 14 else 8), dp(if (glass) 10 else 6), dp(if (glass) 14 else 8), dp(if (glass) 10 else 6))
            background = GradientDrawable().apply {
                if (glass) {
                    setColor(if (dark) 0x33787880 else 0x1F787880)
                    cornerRadius = dp(12).toFloat()
                } else {
                    setColor(if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
                    cornerRadius = dp(6).toFloat()
                    setStroke(1, separator)
                }
            }
        }
        fields.add(f)
        return f
    }

    fun action(label: String, style: Style = Style.DEFAULT, onClick: (() -> Unit)? = null) = apply {
        actions.add(Action(label, style, onClick))
    }

    fun show(): Dialog {
        if (actions.isEmpty()) action("OK", Style.PREFERRED)
        // A full-screen, see-through window with the card centred in it: the card can then grow
        // past its own size while it animates in (a window as small as the card clipped it to a
        // fixed box with square corners).
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(if (glass) 34 else 14).toFloat()
                setColor(cardColor())
                if (glass) setStroke(dp(1).coerceAtLeast(1), if (dark) 0x2EFFFFFF else 0x8CFFFFFF.toInt())
            }
        }

        // Title, message and fields.
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (glass) 22 else 16), dp(if (glass) 22 else 19), dp(if (glass) 22 else 16), dp(if (glass) 18 else 19))
        }
        title?.let {
            content.addView(TextView(activity).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(label)
                gravity = Gravity.CENTER
            })
        }
        message?.let {
            content.addView(TextView(activity).apply {
                text = it
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (glass) 15f else 13f)
                setTextColor(if (glass) (label and 0x00FFFFFF or 0xCC000000.toInt()) else label)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.12f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (title != null) 4 else 0) })
        }
        for (f in fields) content.addView(f, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (glass) 12 else 14) })
        // The text scrolls if the alert would be taller than the screen (large text sizes, small
        // phones); the buttons always stay visible below it.
        val scroller = android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        card.addView(scroller, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Buttons.
        val sideBySide = actions.size == 2
        if (glass) {
            val row = LinearLayout(activity).apply {
                orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                setPadding(dp(18), 0, dp(18), dp(18))
            }
            actions.forEachIndexed { i, a ->
                val lp = if (sideBySide) LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(10) }
                else LinearLayout.LayoutParams(-1, dp(48)).apply { if (i > 0) topMargin = dp(8) }
                row.addView(glassButton(a, dialog), lp)
            }
            card.addView(row)
        } else {
            card.addView(hairline(horizontal = true))
            val row = LinearLayout(activity).apply { orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
            actions.forEachIndexed { i, a ->
                if (i > 0) row.addView(hairline(horizontal = !sideBySide))
                val lp = if (sideBySide) LinearLayout.LayoutParams(0, dp(44), 1f) else LinearLayout.LayoutParams(-1, dp(44))
                row.addView(classicButton(a, dialog), lp)
            }
            card.addView(row)
        }

        val width = dp(if (glass) 300 else 270)
        val frame = android.widget.FrameLayout(activity)
        // The dimmed backdrop is this view, faded with the card (opacity only, no window updates).
        val scrim = View(activity).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        frame.addView(scrim, android.widget.FrameLayout.LayoutParams(-1, -1))
        shownScrim = scrim
        // The card is centred in a holder padded by the bars and the keyboard, so it sits in the
        // middle of the space left. (Margins on a centred child shift it by the whole margin.)
        val holder = android.widget.FrameLayout(activity)
        holder.addView(card, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        frame.addView(holder, android.widget.FrameLayout.LayoutParams(-1, -1))
        dialog.setContentView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shownCard = card
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Reach under the status and navigation bars so the backdrop dims the whole screen.
            if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (Build.VERSION.SDK_INT >= 28) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            // Fields: show the keyboard with the alert, as iOS does; the card stays above it.
            // Android 11+: the card is kept above the keyboard from the insets (below), so the
            // window must not also be resized or panned. Older versions pan it into view.
            setSoftInputMode((if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN) or
                if (fields.isNotEmpty()) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0)
        }
        // Keep the card clear of the system bars and the on-screen keyboard (alerts with fields).
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                // The window reaches under the bars and the keyboard, so keep the card above both.
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            } else null
            @Suppress("DEPRECATION")
            val bottom = bars?.bottom ?: insets.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            val topInset = bars?.top ?: insets.systemWindowInsetTop
            holder.setPadding(0, topInset, 0, bottom)
            insets
        }
        // As on iOS, tapping outside does nothing; Back acts as Cancel (with the same fade).
        dialog.setCancelable(false)
        actions.firstOrNull { it.style == Style.CANCEL }?.let { cancel ->
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    dismiss(dialog, cancel); true
                } else keyCode == android.view.KeyEvent.KEYCODE_BACK
            }
        }
        dialog.setOnShowListener {
            fields.firstOrNull()?.requestFocus()
            if (ValueAnimator.areAnimatorsEnabled()) {
                card.alpha = 0f
                card.scaleX = 1.12f; card.scaleY = 1.12f
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(SHOW_MS)
                    .setInterpolator(DecelerateInterpolator(2.2f)).start()
                scrim.animate().alpha(dimTarget).setDuration(SHOW_MS).setInterpolator(DecelerateInterpolator(2.2f)).start()
            } else {
                scrim.alpha = dimTarget
            }
        }
        dialog.show()
        return dialog
    }

    /**
     * The card's fill, solid in both styles: iOS's glass alert is translucent but blurred so
     * heavily it reads as solid, and where the blur is missing or weak (some phones, emulators) a
     * see-through card shows the text behind it. Glass keeps its rim, rounder corners and capsules.
     */
    private fun cardColor() = when {
        glass -> if (dark) 0xFF262628.toInt() else 0xFFF7F7F9.toInt()
        else -> if (dark) 0xFF2C2C2E.toInt() else 0xFFF2F2F2.toInt()
    }

    private fun textColor(a: Action) = if (a.style == Style.DESTRUCTIVE) red else blue

    /** Classic: a full-width text button that greys while pressed. */
    private fun classicButton(a: Action, dialog: Dialog) = TextView(activity).apply {
        text = a.label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(textColor(a))
        typeface = if (a.style == Style.PREFERRED) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(if (dark) 0x33FFFFFF else 0x1A000000))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        setOnClickListener { dismiss(dialog, a) }
    }

    /** Liquid Glass: a capsule; the preferred action is filled blue, others are tinted glass. */
    private fun glassButton(a: Action, dialog: Dialog) = TextView(activity).apply {
        text = a.label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        val filled = a.style == Style.PREFERRED
        setTextColor(if (filled) Color.WHITE else if (a.style == Style.DESTRUCTIVE) red else label)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(color) }
        val base = if (filled) blue else if (dark) 0x3D787880 else 0x29787880
        val down = if (filled) (blue and 0x00FFFFFF or 0xCC000000.toInt()) else if (dark) 0x5C787880 else 0x47787880
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pill(down))
            addState(intArrayOf(), pill(base))
        }
        setOnClickListener { dismiss(dialog, a) }
    }

    private val dimTarget = if (dark) 0.48f else 0.2f

    private fun hairline(horizontal: Boolean) = View(activity).apply {
        setBackgroundColor(separator)
        layoutParams = if (horizontal) LinearLayout.LayoutParams(-1, 1) else LinearLayout.LayoutParams(1, -1)
    }

    /** Fades the alert out (iOS), then runs the action. */
    private fun dismiss(dialog: Dialog, a: Action) {
        val card = shownCard
        val done = { if (dialog.isShowing) dialog.dismiss(); a.onClick?.invoke() }
        if (card == null || !ValueAnimator.areAnimatorsEnabled()) { done(); return }
        card.animate().alpha(0f).setDuration(HIDE_MS).withEndAction { done() }.start()
        shownScrim?.animate()?.alpha(0f)?.setDuration(HIDE_MS)?.start()
    }

    private var shownCard: View? = null
    private var shownScrim: View? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    private companion object {
        const val SHOW_MS = 260L
        const val HIDE_MS = 160L
    }
}
