package dev.leconnn.ioskeyboard.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * When a switch was last flipped: a screen that rebuilds itself after a setting changes waits for
 * the switch's slide to finish, so the thumb isn't cut off mid-way.
 */
object SwitchClock {
    var at = 0L
    /** Long enough for the platform switch's thumb animation. */
    const val ANIM_MS = 250L
    fun justFlipped() = android.os.SystemClock.uptimeMillis() - at < 100L
}

/**
 * A modern iOS "inset grouped" list built in code: rounded cards of rows with optional coloured
 * icons, checkmark rows for choices, switches and navigation rows.
 */
class IosList(private val context: Context) {

    private val dark = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    // iOS system colours (UIColor.systemGroupedBackground and friends).
    val background = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val card = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    /** Card (row) background colour, e.g. for a row lifted while dragging. */
    val cardColor get() = card
    private val pressed = if (dark) 0xFF2C2C2E.toInt() else 0xFFE5E5EA.toInt()
    val label = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val secondary = if (dark) 0xFF8D8D93.toInt() else 0xFF6D6D72.toInt()
    private val separator = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
    private val green = 0xFF34C759.toInt()
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(40))
    }

    private var currentCard: LinearLayout? = null

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    /** iOS-style back link ("‹ Settings") above the large title. */
    fun backButton(text: String, onClick: () -> Unit) {
        root.addView(TextView(context).apply {
            this.text = "‹ $text"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(blue)
            setPadding(0, dp(10), dp(16), dp(2))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    fun largeTitle(text: String) {
        root.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(label)
            setPadding(dp(4), dp(20), 0, dp(4))
        })
    }

    /** A card at the top of a page with a large icon, a title and a subtitle (like the Apple Account card). */
    fun headerCard(icon: Int, title: String, subtitle: String, subtitleColor: Int = secondary, onClick: (() -> Unit)? = null) {
        section()
        val img = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
            clipToOutline = true
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(label)
            })
            addView(TextView(context).apply {
                text = subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(subtitleColor)
            })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(img, LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            if (onClick != null) {
                addView(chevron())
                isClickable = true
                background = rowBackground()
                setOnClickListener { onClick() }
            }
        }
        addToCard(row, 0)
    }

    /** Starts a section; rows added afterwards go into its card until the next section. */
    fun section(header: String? = null) {
        if (header != null) {
            root.addView(TextView(context).apply {
                text = header
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(secondary)
                setPadding(dp(18), dp(26), dp(18), dp(7))
            })
        } else {
            root.addView(View(context), LinearLayout.LayoutParams(1, dp(26)))
        }
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(CARD_RADIUS_DP).toFloat() }
            clipToOutline = true
        }
        root.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        currentCard = c
    }

    fun footer(text: String) {
        root.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondary)
            setPadding(dp(18), dp(7), dp(18), 0)
        })
    }

    /** An iOS-styled switch (green when on), for rows built outside [switchRow]. */
    fun iosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(context).apply {
        isChecked = checked
        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(green, if (dark) 0xFF39393D.toInt() else 0xFFE9E9EA.toInt()),
        )
        trackTintMode = android.graphics.PorterDuff.Mode.SRC
        setOnCheckedChangeListener { _, v ->
            SwitchClock.at = android.os.SystemClock.uptimeMillis()
            onChange(v)
        }
    }

    fun switchRow(
        title: String, checked: Boolean, icon: SettingsIcon? = null, subtitle: String? = null, onChange: (Boolean) -> Unit,
    ): Switch {
        val sw = iosSwitch(checked, onChange)
        val row = row(title, sw, icon = icon, subtitle = subtitle)
        row.setOnClickListener { sw.toggle() }
        return sw
    }

    /** An iOS search field (a grey capsule above the lists), e.g. in Learned Words. */
    fun searchField(hint: String, text: String): android.widget.EditText {
        val f = android.widget.EditText(context).apply {
            this.hint = hint
            setText(text)
            setSelection(text.length)
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(label)
            setHintTextColor(secondary)
            setPadding(dp(12), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(if (dark) 0x3D767680 else 0x1F767680)
                cornerRadius = dp(18).toFloat()
            }
            // The magnifier at the start, as in iOS search fields.
            setCompoundDrawablesRelative(Magnifier(secondary, dp(17)), null, null, null)
            compoundDrawablePadding = dp(7)
        }
        root.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(12) })
        return f
    }

    /** A row with a value and chevron, like iOS navigation rows. Returns the value label. */
    fun navRow(
        title: String,
        value: String = "",
        titleColor: Int = label,
        icon: SettingsIcon? = null,
        onClick: () -> Unit,
    ): TextView {
        val valueView = TextView(context).apply {
            // One line, cut with "…", and at most half the row: a long value (a text replacement's
            // phrase) never pushes the title out of sight.
            text = value.replace('\n', ' ')
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(secondary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.5f).toInt()
        }
        val trailing = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(valueView)
            addView(chevron())
        }
        row(title, trailing, titleColor, icon).setOnClickListener { onClick() }
        return valueView
    }

    /** A non-interactive grey line in a card, for empty lists ("None", "No learned words yet"). */
    fun placeholderRow(text: String) {
        addToCard(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_DP)
            setPadding(dp(16), dp(6), dp(14), dp(6))
            addView(TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(secondary)
            })
        }, dp(16))
    }

    /** A plain action row (blue or red text, no chevron), e.g. "Reset". */
    fun actionRow(title: String, color: Int = blue, onClick: () -> Unit) {
        // No trailing view: an unsized View would take the whole row (and hide the title).
        val none = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0) }
        row(title, none, color).setOnClickListener { onClick() }
    }

    /** One choice of a list: a blue checkmark marks the selected one, as in iOS. */
    fun checkRow(title: String, checked: Boolean, subtitle: String? = null, onClick: () -> Unit) {
        val mark = TextView(context).apply {
            text = if (checked) "✓" else ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(blue)
        }
        row(title, mark, subtitle = subtitle).setOnClickListener { onClick() }
    }

    /** Any custom view as a full-width row (e.g. a text field). */
    fun customRow(view: View) {
        addToCard(LinearLayout(context).apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            minimumHeight = dp(ROW_DP)
            gravity = Gravity.CENTER_VERTICAL
            addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, dp(16))
    }

    private fun chevron() = TextView(context).apply {
        text = "›"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(if (dark) 0xFF5A5A5F.toInt() else 0xFFC4C4C7.toInt())
        setPadding(dp(10), 0, 0, dp(2))
    }

    /** Rows highlight grey while pressed, like iOS table cells. */
    private fun rowBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressed))
        addState(intArrayOf(), ColorDrawable(0))
    }

    private fun row(
        title: String, trailing: View, titleColor: Int = label, icon: SettingsIcon? = null, subtitle: String? = null,
    ): LinearLayout {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (icon != null) ICON_ROW_DP else ROW_DP)
            setPadding(dp(16), dp(6), dp(14), dp(6))
            isClickable = true
            background = rowBackground()
        }
        if (icon != null) {
            r.addView(View(context).apply { background = icon.drawable(context) },
                LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { marginEnd = dp(14) })
        }
        val titleView = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(titleColor)
        }
        if (subtitle == null) {
            r.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        } else {
            // Title with a smaller grey line under it (iOS's subtitle cell).
            r.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), dp(8), dp(4))
                addView(titleView)
                addView(TextView(context).apply {
                    text = subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(secondary)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        r.addView(trailing)
        // Separators start at the title, after the icon, as in iOS.
        addToCard(r, dp(if (icon != null) 16 + ICON_DP + 14 else 16))
        return r
    }

    private fun addToCard(v: View, separatorInset: Int) {
        val c = currentCard ?: error("call section() first")
        if (c.childCount > 0) {
            c.addView(View(context).apply { setBackgroundColor(separator) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = separatorInset })
        }
        c.addView(v)
    }

    private companion object {
        /** Modern iOS groups are more rounded than the old 10 pt. */
        const val CARD_RADIUS_DP = 22
        const val ROW_DP = 48
        const val ICON_ROW_DP = 52
        const val ICON_DP = 30
    }
}

/** A small magnifying-glass icon for search fields, drawn in [color]. */
private class Magnifier(color: Int, private val size: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = size / 9f
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    init { setBounds(0, 0, size, size) }
    override fun draw(canvas: android.graphics.Canvas) {
        val s = size.toFloat()
        canvas.drawCircle(s * 0.42f, s * 0.42f, s * 0.3f, paint)
        canvas.drawLine(s * 0.64f, s * 0.64f, s * 0.9f, s * 0.9f, paint)
    }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable API")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
