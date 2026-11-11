package dev.leconnn.ioskeyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * When a switch was last flipped: a screen that rebuilds itself after a setting changes waits for
 * the switch's slide to finish, so the knob isn't cut off mid-way.
 */
object SwitchClock {
    var at = 0L
    /** Long enough for the switch's spring to settle visibly. */
    const val ANIM_MS = 280L
    fun justFlipped() = android.os.SystemClock.uptimeMillis() - at < 100L
}

/**
 * A modern iOS "inset grouped" list built in code: rounded cards of rows with optional coloured
 * icons, checkmark rows for choices, switches, value rows that open a page, sliders, form fields
 * and visual pickers. Metrics follow iOS 26 (26 pt card corners, 52 pt rows, 30 pt icons).
 */
class IosList(private val context: Context) {

    private val dark = context.isDark()

    // iOS system colours (UIColor.systemGroupedBackground and friends).
    val background = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val card = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    /** Card (row) background colour, e.g. for a row lifted while dragging. */
    val cardColor get() = card
    private val pressed = if (dark) 0xFF3A3A3C.toInt() else 0xFFD1D1D6.toInt()
    val label = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val secondary = if (dark) 0xFF8D8D93.toInt() else 0xFF6D6D72.toInt()
    private val tertiary = if (dark) 0xFF5A5A5F.toInt() else 0xFFC4C4C7.toInt()
    private val separator = if (dark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
    val blue = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    val green = 0xFF34C759.toInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), 0, dp(16), dp(40))
    }

    /**
     * Called when a navigation row is tapped, before it acts: the page uses it to remember which
     * row to fade back out when the user returns (see [fadeRow]).
     */
    var onNavigate: ((String) -> Unit)? = null
    /** The row (by title) that stays highlighted and fades out once the page shows again. */
    var fadeRow: String? = null

    private var currentCard: LinearLayout? = null

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    /** The root page's large title (34 pt bold); returns its view so the bar can follow it. */
    fun largeTitle(text: String): TextView {
        val t = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.01f
            setTextColor(label)
            setPadding(dp(4), dp(2), 0, dp(6))
        }
        root.addView(t)
        return t
    }

    /** A card at the top of a page with a large icon, a title and a subtitle (like the Apple Account card). */
    fun headerCard(icon: Int, title: String, subtitle: String, subtitleColor: Int = secondary, onClick: (() -> Unit)? = null) {
        section()
        val img = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(15).toFloat() }
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
            setPadding(dp(16), dp(14), dp(14), dp(14))
            addView(img, LinearLayout.LayoutParams(dp(64), dp(64)))
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

    /** A card row with a large icon, a title and a detail line (Software Update's new version). */
    fun headerCardRow(icon: Int, title: String, detail: String) {
        val img = ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(13).toFloat() }
            clipToOutline = true
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(label)
            })
            if (detail.isNotEmpty()) addView(TextView(context).apply {
                text = detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(secondary)
            })
        }
        addToCard(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(img, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        }, dp(16))
    }

    /** Starts a section; rows added afterwards go into its card until the next section. */
    fun section(header: String? = null) {
        if (header != null) {
            root.addView(TextView(context).apply {
                text = header
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(secondary)
                setPadding(dp(16), dp(22), dp(16), dp(7))
            })
        } else {
            root.addView(View(context), LinearLayout.LayoutParams(1, dp(SECTION_GAP_DP)))
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
            setLineSpacing(0f, 1.1f)
            setPadding(dp(16), dp(7), dp(16), 0)
        })
    }

    /** An iOS switch, for rows built outside [switchRow]. */
    fun iosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = IosSwitch(context).apply {
        setChecked(checked)
        this.onChange = onChange
    }

    fun switchRow(
        title: String, checked: Boolean, icon: SettingsIcon? = null, subtitle: String? = null, onChange: (Boolean) -> Unit,
    ): IosSwitch {
        val sw = iosSwitch(checked, onChange)
        val row = row(title, sw, icon = icon, subtitle = subtitle)
        // The whole row doesn't toggle on iOS (only the switch does), and doesn't highlight.
        row.background = null
        row.isClickable = false
        return sw
    }

    /** An iOS search field (a grey capsule above the lists), e.g. in Learned Words. */
    fun searchField(hint: String, text: String): EditText {
        val f = EditText(context).apply {
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
                cornerRadius = dp(20).toFloat()
            }
            // The magnifier at the start, as in iOS search fields.
            setCompoundDrawablesRelative(Magnifier(secondary, dp(17)), null, null, null)
            compoundDrawablePadding = dp(7)
        }
        root.addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) })
        return f
    }

    /** A row with a value and chevron, like iOS navigation rows. Returns the value label. */
    fun navRow(
        title: String,
        value: String = "",
        titleColor: Int = label,
        icon: SettingsIcon? = null,
        badge: Int = 0,
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
            // iOS's red count badge (e.g. Software Update Available).
            if (badge > 0) addView(TextView(context).apply {
                text = badge.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                minWidth = dp(24)
                setPadding(dp(7), 0, dp(7), 0)
                background = GradientDrawable().apply { setColor(red); cornerRadius = dp(12).toFloat() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)).apply { marginStart = dp(8) })
            addView(chevron())
        }
        val r = row(title, trailing, titleColor, icon)
        r.setOnClickListener {
            // Stays highlighted while the next page slides in, as in iOS (behind the content).
            r.background = ColorDrawable(pressed)
            onNavigate?.invoke(title)
            onClick()
        }
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
    fun actionRow(title: String, color: Int = blue, centered: Boolean = false, onClick: () -> Unit) {
        // No trailing view: an unsized View would take the whole row (and hide the title).
        val none = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 0) }
        val r = row(title, none, color)
        if (centered) (r.getChildAt(0) as TextView).gravity = Gravity.CENTER
        r.setOnClickListener { onClick() }
    }

    /** One choice of a list: a blue checkmark marks the selected one, as in iOS. */
    fun checkRow(title: String, checked: Boolean, subtitle: String? = null, onClick: () -> Unit) {
        val mark = View(context).apply {
            background = if (checked) CheckmarkDrawable(blue, dp(18), dp(2).toFloat() * 1.35f) else null
        }
        mark.layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
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

    /**
     * A form row: a title at the left and a text field filling the rest (iOS's Text Replacement
     * editor). Returns the field.
     */
    fun fieldRow(title: String, text: String, hint: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val field = EditText(context).apply {
            setText(text)
            this.hint = hint
            setSingleLine(true)
            inputType = type
            background = null
            setPadding(0, dp(8), 0, dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(label)
            setHintTextColor(tertiary)
        }
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_DP)
            setPadding(dp(16), 0, dp(16), 0)
            addView(TextView(context).apply {
                this.text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(label)
            }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addToCard(r, dp(16))
        return field
    }

    /**
     * A slider row with optional small/large icons at its ends (iOS's volume slider) and optional
     * captions under it (iOS 27's Liquid Glass Tint Amount: More Clear / Default / More Tinted).
     */
    fun sliderRow(
        value: Float,
        minIcon: android.graphics.drawable.Drawable? = null,
        maxIcon: android.graphics.drawable.Drawable? = null,
        captions: List<String> = emptyList(),
        ticks: FloatArray = FloatArray(0),
        onChange: (Float) -> Unit = {},
        onRelease: (Float) -> Unit,
    ): IosSlider {
        val slider = IosSlider(context).apply {
            this.value = value
            this.ticks = ticks
            this.onChange = onChange
            this.onRelease = onRelease
        }
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (minIcon != null) addView(View(context).apply { background = minIcon }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(slider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (minIcon != null) dp(6) else 0; marginEnd = if (maxIcon != null) dp(6) else 0
            })
            if (maxIcon != null) addView(View(context).apply { background = maxIcon }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), if (captions.isEmpty()) dp(6) else dp(12))
            addView(line)
            if (captions.isNotEmpty()) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(4), 0, dp(4), 0)
                    captions.forEachIndexed { i, s ->
                        addView(TextView(context).apply {
                            text = s
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setTextColor(secondary)
                            gravity = when (i) {
                                0 -> Gravity.START
                                captions.size - 1 -> Gravity.END
                                else -> Gravity.CENTER_HORIZONTAL
                            }
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                })
            }
        }
        addToCard(box, dp(16))
        return slider
    }

    /** One option of a [tilesRow]: a drawn preview, a name, and whether it's the chosen one. */
    class Tile(val name: String, val selected: Boolean, val preview: (Canvas, RectF) -> Unit, val onPick: () -> Unit)

    /**
     * iOS's visual picker (Display & Brightness › Light / Dark): previews side by side, each with
     * its name and a round check under it.
     */
    fun tilesRow(tiles: List<Tile>) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(18), dp(8), dp(14))
        }
        for (t in tiles) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { t.onPick() }
            }
            col.addView(TilePreview(context, t.preview, t.selected, blue), LinearLayout.LayoutParams(dp(78), dp(150)))
            col.addView(TextView(context).apply {
                text = t.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(label)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(6))
            })
            col.addView(RadioCheck(context, t.selected, blue, tertiary), LinearLayout.LayoutParams(dp(24), dp(24)))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addToCard(row, dp(16))
    }

    private fun chevron() = View(context).apply {
        background = ChevronDrawable(tertiary, dp(14), dp(2).toFloat() * 0.9f)
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(16)).apply { marginStart = dp(10) }
    }

    /** Rows highlight grey while pressed, like iOS table cells, and fade back out on release. */
    private fun rowBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressed))
        addState(intArrayOf(), ColorDrawable(0))
        setExitFadeDuration(250)
    }

    private fun row(
        title: String, trailing: View, titleColor: Int = label, icon: SettingsIcon? = null, subtitle: String? = null,
    ): LinearLayout {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (icon != null) ICON_ROW_DP else ROW_DP)
            setPadding(dp(16), dp(6), dp(16), dp(6))
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
        // Back on this page after following this row: its highlight fades out, as in iOS.
        if (title == fadeRow) {
            val d = ColorDrawable(pressed)
            val normal = r.background
            r.background = d
            ValueAnimator.ofInt(255, 0).apply {
                startDelay = 60
                duration = 420
                addUpdateListener { d.alpha = it.animatedValue as Int }
                doOnEnd { r.background = normal }
                start()
            }
        }
        // Separators start at the title, after the icon, as in iOS.
        addToCard(r, dp(if (icon != null) 16 + ICON_DP + 14 else 16))
        return r
    }

    private fun addToCard(v: View, separatorInset: Int) {
        val c = currentCard ?: error("call section() first")
        if (c.childCount > 0) {
            c.addView(View(context).apply { setBackgroundColor(separator) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2)).apply { marginStart = separatorInset })
        }
        c.addView(v)
    }

    private companion object {
        /** iOS 26 groups are rounder than the old 10 pt. */
        const val CARD_RADIUS_DP = 26
        const val ROW_DP = 52
        const val ICON_ROW_DP = 52
        const val ICON_DP = 30
        const val SECTION_GAP_DP = 24
    }
}

private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = block()
})

/** A tile's preview: a phone-shaped rounded rectangle, ringed in blue when chosen. */
private class TilePreview(
    context: Context, private val draw: (Canvas, RectF) -> Unit, private val selected: Boolean, private val accent: Int,
) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2.5f)
        color = accent
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1f)
        color = if (context.isDark()) 0x33FFFFFF else 0x22000000
    }
    private val r = RectF()
    private val clip = android.graphics.Path()
    override fun onDraw(canvas: Canvas) {
        val inset = context.dpf(3f)
        r.set(inset, inset, width - inset, height - inset)
        val rad = context.dpf(12f)
        canvas.save()
        clip.reset()
        clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        draw(canvas, r)
        canvas.restore()
        canvas.drawRoundRect(r, rad, rad, frame)
        if (selected) {
            r.inset(-context.dpf(2.5f), -context.dpf(2.5f))
            canvas.drawRoundRect(r, rad + context.dpf(2.5f), rad + context.dpf(2.5f), ring)
        }
    }
}

/** iOS's round selection check: a blue disc with a white tick, or an empty grey ring. */
private class RadioCheck(context: Context, private val on: Boolean, private val accent: Int, private val off: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = CheckmarkDrawable(0xFFFFFFFF.toInt(), 0, context.dpf(2f))
    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val rad = c - context.dpf(1f)
        if (on) {
            p.style = Paint.Style.FILL; p.color = accent
            canvas.drawCircle(c, c, rad, p)
            val s = (rad * 0.62f).toInt()
            tick.setBounds((c - s).toInt(), (c - s).toInt(), (c + s).toInt(), (c + s).toInt())
            tick.draw(canvas)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.5f); p.color = off
            canvas.drawCircle(c, c, rad - context.dpf(0.75f), p)
        }
    }
}

/** A small magnifying-glass icon for search fields, drawn in [color]. */
private class Magnifier(color: Int, private val size: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = size / 9f
        strokeCap = Paint.Cap.ROUND
    }
    init { setBounds(0, 0, size, size) }
    override fun draw(canvas: Canvas) {
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
