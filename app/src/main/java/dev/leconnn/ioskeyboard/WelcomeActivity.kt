package dev.leconnn.ioskeyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.leconnn.ioskeyboard.ui.CheckmarkDrawable
import dev.leconnn.ioskeyboard.ui.IosList
import dev.leconnn.ioskeyboard.ui.IosPage
import dev.leconnn.ioskeyboard.ui.MiniKeyboard
import dev.leconnn.ioskeyboard.ui.NavStack
import dev.leconnn.ioskeyboard.ui.ProminentButton
import dev.leconnn.ioskeyboard.ui.SettingsIcon
import dev.leconnn.ioskeyboard.ui.Spring
import dev.leconnn.ioskeyboard.ui.SwitchClock
import dev.leconnn.ioskeyboard.ui.ThemeMode

/**
 * The first-run welcome, like iOS's Setup Assistant: one step per screen, each with a large icon,
 * a bold title, a short explanation and its choices, and a Continue button pinned at the bottom.
 * Steps push like Settings pages (back with the glass button, a swipe or the system gesture).
 * Every step can be skipped with "Set Up Later in Settings"; everything here is also in Settings.
 * It shows once, on a fresh install ([shouldShow]), and again from Settings › Reset.
 */
class WelcomeActivity : Activity() {

    private enum class Step { WELCOME, TURN_ON, APPEARANCE, LAYOUT, EXTRAS, DONE }

    private lateinit var prefs: Prefs
    private lateinit var stack: NavStack
    private val steps = HashMap<IosPage, Step>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A forced light/dark mode restyles the screen (the steps so far are kept).
        if (key == Prefs.KEY_THEME_MODE) { recreate(); return@OnSharedPreferenceChangeListener }
        stack.removeCallbacks(rerender)
        if (SwitchClock.justFlipped()) stack.postDelayed(rerender, SwitchClock.ANIM_MS) else rerender.run()
    }
    private val rerender = Runnable { if (!isFinishing) stack.top?.let { render(it) } }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemeMode.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Spring.animationsOn = { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
        stack = NavStack(this)
        stack.setBackgroundColor(IosList(this).background)
        stack.onRevealing = { render(it) }
        stack.onPopped = { steps.remove(it); updateBackCallback() }
        setContentView(stack)
        ThemeMode.styleBars(this)
        val saved = savedInstanceState?.getStringArrayList(STATE_STEPS)?.mapNotNull { n -> Step.entries.firstOrNull { it.name == n } }
        for (s in saved ?: listOf(Step.WELCOME)) push(s, animated = false)
        setupBack()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_STEPS, ArrayList(stack.pages.mapNotNull { steps[it]?.name }))
    }

    override fun onResume() {
        super.onResume()
        if (ThemeMode.refreshIfChanged(this)) return
        prefs.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        stack.top?.let { render(it) }
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    // Android's keyboard picker is a dialog over this screen: refresh when it closes.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && steps[stack.top] == Step.TURN_ON) stack.top?.let { render(it) }
    }

    private val keyboardSelected
        get() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
    private val keyboardEnabled
        get() = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    private fun push(step: Step, animated: Boolean = true) {
        val page = IosPage(this, "", largeTitle = false, background = IosList(this).background)
        steps[page] = step
        if (step != Step.WELCOME) page.setBack { stack.pop() }
        render(page)
        stack.push(page, animated)
        updateBackCallback()
    }

    private fun next(current: Step) {
        val n = Step.entries.getOrNull(current.ordinal + 1) ?: return done()
        push(n)
    }

    /** Leaves the welcome for Settings; it won't show again by itself. */
    private fun done() {
        prefs.onboarded = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun render(page: IosPage) {
        val step = steps[page] ?: return
        val y = page.scroll.scrollY
        val list = IosList(this)
        when (step) {
            Step.WELCOME -> {
                hero(list, appIcon(list), "Welcome to iPurrboard",
                    "The iPhone keyboard's look, feel and typing, on Android. A few choices and you're ready to type.")
                feature(list, "Types like an iPhone", "The same keys, autocorrection, predictions and slide to type.")
                feature(list, "Your words stay yours", "No accounts, ads or tracking. What you type never leaves your phone.")
                feature(list, "Make it yours", "Liquid Glass or Classic, light or dark, and extras from Android keyboards.")
            }
            Step.TURN_ON -> turnOn(list)
            Step.APPEARANCE -> appearance(list)
            Step.LAYOUT -> layout(list)
            Step.EXTRAS -> extras(list)
            Step.DONE -> hero(list, DoneMark(this), "You're All Set",
                "Change any of this later in Settings. Try the keyboard in the Try It field there.")
        }
        page.setContent(list.root)
        page.scroll.post { page.scroll.scrollTo(0, y) }
        page.setFooter(footer(list, step))
    }

    // ---- steps -----------------------------------------------------------------------------

    private fun turnOn(list: IosList) {
        val enabled = keyboardEnabled
        val selected = keyboardSelected
        hero(list, iconView(list, SettingsIcon.KEYBOARD), "Turn On iPurrboard",
            "Android asks you to turn on a new keyboard yourself. It will mention that keyboards can see what " +
                "you type: iPurrboard keeps it on your phone and never sends it anywhere.")
        list.section()
        list.navRow("1.  Enable iPurrboard", if (enabled) "Done" else "", titleColor = if (enabled) list.label else list.blue) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        list.navRow("2.  Select iPurrboard", if (selected) "Done" else "", titleColor = if (selected || !enabled) list.secondary else list.blue) {
            if (keyboardEnabled) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        list.footer(if (selected) "iPurrboard is your keyboard." else "Turn it on in the list of keyboards, come back, then pick it.")
    }

    private fun appearance(list: IosList) {
        val dark = KeyboardTheme.isDark(this)
        val glass = prefs.appearance == Prefs.Appearance.GLASS
        hero(list, iconView(list, SettingsIcon.APPEARANCE), "Appearance",
            "Liquid Glass is the iOS 26 look: see-through keys over a blurred panel. Classic is the look from before.")
        list.section()
        list.tilesRow(listOf(
            IosList.Tile("Classic", !glass, { c, r -> MiniKeyboard.draw(c, r, glass = false, dark = dark) }) { prefs.appearance = Prefs.Appearance.CLASSIC },
            IosList.Tile("Glass", glass, { c, r -> MiniKeyboard.draw(c, r, glass = true, dark = dark) }) { prefs.appearance = Prefs.Appearance.GLASS },
        ))
        list.section()
        list.tilesRow(listOf(
            IosList.Tile("Light", !dark, { c, r -> MiniKeyboard.draw(c, r, glass, dark = false) }) { prefs.themeMode = Prefs.ThemeMode.LIGHT },
            IosList.Tile("Dark", dark, { c, r -> MiniKeyboard.draw(c, r, glass, dark = true) }) { prefs.themeMode = Prefs.ThemeMode.DARK },
        ))
        list.switchRow("Automatic", prefs.themeMode == Prefs.ThemeMode.AUTO) { on ->
            prefs.themeMode = if (on) Prefs.ThemeMode.AUTO else if (dark) Prefs.ThemeMode.DARK else Prefs.ThemeMode.LIGHT
        }
        list.footer("Automatic follows your phone's dark mode.")
    }

    private fun layout(list: IosList) {
        val dark = KeyboardTheme.isDark(this)
        val glass = prefs.appearance == Prefs.Appearance.GLASS
        val extended = prefs.keyLayout == KeyStyle.GBOARD
        hero(list, iconView(list, SettingsIcon.LAYOUT), "Layout",
            "Standard is the iPhone's keyboard. Extended adds comma and period keys, symbols on the letters " +
                "and a toolbar, like many Android keyboards.")
        list.section()
        list.tilesRow(listOf(
            IosList.Tile("Standard", !extended, { c, r -> MiniKeyboard.draw(c, r, glass, dark) }) { setLayout(KeyStyle.IOS) },
            IosList.Tile("Extended", extended, { c, r -> MiniKeyboard.draw(c, r, glass, dark, extended = true) }) { setLayout(KeyStyle.GBOARD) },
        ))
        list.footer("You can mix the two later in Settings › Layout and Toolbar.")
    }

    /** The same as choosing a key layout in Settings: its symbol hints, toolbar and panels come along. */
    private fun setLayout(s: KeyStyle) {
        if (prefs.keyLayout == s) return
        prefs.keyLayout = s
        prefs.symbolHints = s == KeyStyle.GBOARD
        prefs.toolbar = s == KeyStyle.GBOARD
        prefs.emojiLayout = s
        prefs.clipboardLayout = s
        prefs.gifLayout = s
    }

    private fun extras(list: IosList) {
        hero(list, iconView(list, SettingsIcon.TYPING), "Make It Yours",
            "A few things people like to set first. iPurrboard starts out like the iPhone; extras are off until you turn them on.")
        list.section("Typing")
        list.switchRow("Slide to Type", prefs.slideToType) { prefs.slideToType = it }
        list.switchRow("Number Row", prefs.numberRow) { prefs.numberRow = it }
        list.section("Keyboard Feedback")
        list.switchRow("Sound", prefs.sound) { prefs.sound = it }
        list.switchRow("Haptic", prefs.haptic) { prefs.haptic = it }
        list.section("Clipboard")
        list.switchRow("Show Copied Items", prefs.showCopiedItems) { prefs.showCopiedItems = it }
        list.switchRow("Clipboard History", prefs.clipboardHistory) { prefs.clipboardHistory = it }
        list.footer("Show Copied Items offers what you just copied above the keys. Clipboard History keeps " +
            "recent copies on your phone (never passwords) so you can paste them again.")
    }

    // ---- pieces ----------------------------------------------------------------------------

    /** The Setup Assistant's header: a large icon, a bold centred title and a short explanation. */
    private fun hero(list: IosList, icon: View, title: String, body: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(list.dp(8), list.dp(8), list.dp(8), list.dp(6))
        }
        box.addView(icon, LinearLayout.LayoutParams(list.dp(84), list.dp(84)))
        box.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.01f
            setTextColor(list.label)
            gravity = Gravity.CENTER
            setPadding(0, list.dp(20), 0, list.dp(10))
        })
        box.addView(TextView(this).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(list.label)
            alpha = 0.85f
            setLineSpacing(0f, 1.12f)
            gravity = Gravity.CENTER
        })
        list.root.addView(box)
    }

    /** A Welcome feature line: a bold title with a sentence under it (as in "What's New"). */
    private fun feature(list: IosList, title: String, body: String) {
        list.root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(list.dp(12), list.dp(18), list.dp(12), 0)
            addView(TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(list.label)
            })
            addView(TextView(context).apply {
                text = body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(list.secondary)
                setPadding(0, list.dp(2), 0, 0)
            })
        })
    }

    private fun appIcon(list: IosList) = ImageView(this).apply {
        setImageResource(R.drawable.ic_launcher_art)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { cornerRadius = list.dp(19).toFloat() }
        clipToOutline = true
    }

    private fun iconView(list: IosList, icon: SettingsIcon) = View(this).apply { background = icon.drawable(context) }

    /** Continue (or Get Started) and, before the last step, a way to finish later. */
    private fun footer(list: IosList, step: Step): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(list.dp(24), list.dp(12), list.dp(24), list.dp(8))
        }
        val button = ProminentButton(this, if (step == Step.DONE) "Get Started" else "Continue")
        button.setOnClickListener { if (step == Step.DONE) done() else next(step) }
        inner.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (step != Step.DONE) {
            inner.addView(TextView(this).apply {
                text = if (step == Step.WELCOME) "Skip and Go to Settings" else "Set Up Later in Settings"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(list.blue)
                gravity = Gravity.CENTER
                setPadding(0, list.dp(14), 0, list.dp(8))
                isClickable = true
                setOnClickListener { done() }
            })
        }
        box.addView(inner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return box
    }

    // ---- back ------------------------------------------------------------------------------

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (!stack.pop()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private var backCallback: Any? = null
    private var backRegistered = false

    /** As in Settings: the system back swipe moves the step back, until the first one. */
    private fun setupBack() {
        if (Build.VERSION.SDK_INT < 33) return
        backCallback = if (Build.VERSION.SDK_INT >= 34) {
            object : android.window.OnBackAnimationCallback {
                override fun onBackStarted(e: android.window.BackEvent) = stack.beginBack()
                override fun onBackProgressed(e: android.window.BackEvent) = stack.updateBack(e.progress)
                override fun onBackInvoked() = stack.endBack(commit = true)
                override fun onBackCancelled() = stack.endBack(commit = false)
            }
        } else {
            android.window.OnBackInvokedCallback { stack.pop() }
        }
        updateBackCallback()
    }

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as? android.window.OnBackInvokedCallback ?: return
        val want = stack.pages.size > 1
        if (want == backRegistered) return
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        backRegistered = want
    }

    /** The last step's mark: a green disc with a white tick. */
    private class DoneMark(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C759.toInt() }
        private val tick = CheckmarkDrawable(0xFFFFFFFF.toInt(), 0, context.resources.displayMetrics.density * 6f)
        override fun onDraw(canvas: Canvas) {
            val c = width / 2f
            canvas.drawCircle(c, c, c, p)
            val s = (c * 0.5f).toInt()
            tick.setBounds((c - s).toInt(), (c - s).toInt(), (c + s).toInt(), (c + s).toInt())
            tick.draw(canvas)
        }
    }

    companion object {
        private const val STATE_STEPS = "steps"

        /**
         * On a fresh install only: an app updated from a version without the welcome is already set
         * up, so it's marked done instead.
         */
        fun shouldShow(context: Context): Boolean {
            val prefs = Prefs.get(context)
            if (prefs.onboarded) return false
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return false
            if (info.firstInstallTime != info.lastUpdateTime) {
                prefs.onboarded = true
                return false
            }
            return true
        }
    }
}
