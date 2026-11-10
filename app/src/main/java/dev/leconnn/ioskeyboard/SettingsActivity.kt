package dev.leconnn.ioskeyboard

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.leconnn.ioskeyboard.emoji.EmojiArt
import dev.leconnn.ioskeyboard.text.Language
import dev.leconnn.ioskeyboard.text.UserWords
import dev.leconnn.ioskeyboard.ui.GlassButton
import dev.leconnn.ioskeyboard.ui.IosAlert
import dev.leconnn.ioskeyboard.ui.IosList
import dev.leconnn.ioskeyboard.ui.IosPage
import dev.leconnn.ioskeyboard.ui.MiniKeyboard
import dev.leconnn.ioskeyboard.ui.NavStack
import dev.leconnn.ioskeyboard.ui.SettingsIcon
import dev.leconnn.ioskeyboard.ui.SpeakerDrawable
import dev.leconnn.ioskeyboard.ui.Spring
import dev.leconnn.ioskeyboard.ui.SwitchClock
import dev.leconnn.ioskeyboard.ui.ThemeMode

/**
 * The settings app, built like iOS Settings. One activity holds a navigation stack ([NavStack]):
 * pages push in from the right and go back with the glass back button, the system back gesture
 * or a swipe right anywhere on the page. The root page has a large title, the app's card and
 * icon rows; Keyboard mirrors iOS's Settings › General › Keyboard (same rows, order and wording,
 * taken from iOS 27.2's KeyboardSettings); settings with several options are value rows that
 * open a checkmark page, as on iOS.
 */
class SettingsActivity : Activity() {

    enum class Page(val title: String) {
        MAIN("Settings"),
        KEYBOARD("Keyboard"),
        KEYBOARDS("Languages"),
        REPLACEMENTS("Text Replacement"),
        REPLACEMENT_EDIT("Text Replacement"),
        LEARNED("Learned Words"),
        ONE_HANDED("One-Handed Keyboard"),
        PREVIEW_SIZE("Key Preview Size"),
        LONG_PRESS("Long-Press Delay"),
        APPEARANCE("Appearance"),
        LIQUID_GLASS("Liquid Glass"),
        FEEDBACK("Sounds & Haptics"),
        VIBRATION("Vibration Strength"),
        LAYOUT("Layout"),
        KEY_LAYOUT("Key Layout"),
        HEIGHT("Keyboard Height"),
        EMOJI_LAYOUT("Emoji"),
        CLIPBOARD_LAYOUT("Clipboard"),
        GIF_LAYOUT("GIFs"),
        TOOLBAR("Toolbar"),
        CLIPBOARD("Clipboard"),
        COPIED_STYLE("Copied Items Style"),
        RETENTION("Keep History For"),
        PRIVACY("Privacy Policy"),
        RESET("Reset"),
    }

    /** A page and its argument (the replacement being edited; -1 for none). */
    private data class Route(val page: Page, val arg: Int = -1)

    private lateinit var prefs: Prefs
    private lateinit var stack: NavStack
    private val routes = HashMap<IosPage, Route>()
    /** Per page: the row last followed, faded out when the page shows again. */
    private val lastTapped = HashMap<IosPage, String>()
    private var toolbarPage: ToolbarPage? = null
    private var learnedPage: LearnedWordsPage? = null
    private var previewKeyboard: KeyboardView? = null
    private var previewField: EditText? = null
    private var tryField: EditText? = null
    private var tryPreview: android.widget.ImageView? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A forced light/dark mode restyles the screen, so rebuild it (the stack is kept).
        if (key == Prefs.KEY_THEME_MODE) { recreate(); return@OnSharedPreferenceChangeListener }
        // Emoji picks don't change anything shown here.
        if (key == "emojiRecents" || key == "emojiTones") return@OnSharedPreferenceChangeListener
        // Switching keyboards while typing in Try It must not rebuild the page (it would take
        // the field's focus and close the keyboard); only the Languages page shows it.
        if (key == Prefs.KEY_KEYBOARD && stack.top?.let { routes[it]?.page } != Page.KEYBOARDS) return@OnSharedPreferenceChangeListener
        // Let a flipped switch finish moving before the page is rebuilt.
        stack.removeCallbacks(rerender)
        if (SwitchClock.justFlipped()) stack.postDelayed(rerender, SwitchClock.ANIM_MS) else rerender.run()
    }
    private val rerender = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        // Only the page on screen is rebuilt; the ones underneath are rebuilt as they're revealed.
        // A form (the text replacement editor) keeps what's being typed.
        stack.top?.takeIf { routes[it]?.page != Page.REPLACEMENT_EDIT }?.let { render(it) }
        updatePreview()
    }

    /** Applies a forced Light or Dark mode to this screen (Automatic keeps the system's). */
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(ThemeMode.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Spring.animationsOn = {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        }
        stack = NavStack(this)
        stack.onRevealing = { page -> render(page, fade = lastTapped.remove(page)) }
        stack.onPopped = { page ->
            routes.remove(page)
            lastTapped.remove(page)
            hideIme()
            updateBackCallback()
        }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.setBackgroundColor(IosList(this).background)
        outer.addView(stack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // In-app preview keyboard, for when iPurrboard isn't the active keyboard (e.g. on an emulator).
        val preview = EditText(this).apply {
            hint = "In-app preview (no keyboard setup needed)"
            background = null
            showSoftInputOnFocus = false
        }
        previewField = preview
        lateinit var keyboard: KeyboardView
        keyboard = KeyboardView(this, PreviewListener(preview) { keyboard })
        previewKeyboard = keyboard
        outer.addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // The preview keyboard sits under the pages, clear of the navigation bar.
        outer.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            keyboard.setPadding(0, 0, 0, if (keyboard.visibility == View.VISIBLE) bars.bottom else 0)
            insets
        }
        setContentView(outer)
        ThemeMode.styleBars(this)

        // The stack survives a Light / Dark change (recreate); a link from the keyboard can open a page.
        val saved = savedInstanceState?.getStringArrayList(STATE_ROUTES)?.mapNotNull { parseRoute(it) }
        val linked = intent.getStringExtra(EXTRA_PAGE)?.let { n -> Page.entries.firstOrNull { it.name == n } }
        val start = saved ?: listOfNotNull(Route(Page.MAIN), linked?.takeIf { it != Page.MAIN }?.let { Route(it) })
        for (r in start) push(r, animated = false)
        setupBack()

        // A fresh install opens on the welcome; Settings is underneath for when it's done.
        if (savedInstanceState == null && linked == null && WelcomeActivity.shouldShow(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false) && savedInstanceState == null) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_ROUTES, ArrayList(stack.pages.mapNotNull { routes[it] }.map { "${it.page.name}:${it.arg}" }))
    }

    private fun parseRoute(s: String): Route? {
        val (name, arg) = s.split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: -1) }
        return Page.entries.firstOrNull { it.name == name }?.let { Route(it, arg) }
    }

    override fun onResume() {
        super.onResume()
        if (ThemeMode.refreshIfChanged(this)) return
        prefs.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        stack.top?.let { render(it) }
        updatePreview()
    }

    override fun onPause() {
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    private val keyboardSelected
        get() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
    private val keyboardEnabled
        get() = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    // ---- navigation ------------------------------------------------------------------------

    private fun push(route: Route, animated: Boolean = true) {
        val list = IosList(this)
        val page = IosPage(this, route.page.title, largeTitle = route.page == Page.MAIN, background = list.background)
        routes[page] = route
        if (route.page != Page.MAIN) page.setBack { stack.pop() }
        when (route.page) {
            Page.TOOLBAR -> toolbarPage = ToolbarPage(this) { render(page) }
            Page.LEARNED -> learnedPage = LearnedWordsPage(this) { render(page) }
            else -> Unit
        }
        render(page)
        hideIme()
        stack.push(page, animated)
        updateBackCallback()
    }

    private fun open(p: Page, arg: Int = -1) = push(Route(p, arg))

    /** Rebuilds [page] (cheap), keeping its scroll position; [fade] is a row to fade out. */
    private fun render(page: IosPage, fade: String? = null) {
        val route = routes[page] ?: return
        val y = page.scroll.scrollY
        val list = IosList(this)
        list.fadeRow = fade
        list.onNavigate = { lastTapped[page] = it }
        // The Try It field and the preview field move into the rebuilt root; detach them first.
        if (route.page == Page.MAIN) listOfNotNull(tryField, tryPreview, previewField).forEach { v ->
            (v.parent as? android.view.ViewGroup)?.removeView(v)
        }
        page.setRight(null)
        when (route.page) {
            Page.MAIN -> {
                val title = list.largeTitle("Settings")
                title.post { page.largeTitleBottom = title.bottom }
                main(list)
            }
            Page.KEYBOARD -> keyboard(list)
            Page.KEYBOARDS -> keyboards(list)
            Page.REPLACEMENTS -> replacements(list, page)
            Page.REPLACEMENT_EDIT -> editReplacement(list, page, route.arg)
            Page.LEARNED -> learnedPage?.build(list)
            Page.ONE_HANDED -> choices(list, null, Prefs.OneHanded.entries, ::oneHandedLabel, prefs.oneHanded,
                "You can also switch it while typing: hold the emoji key.") { prefs.oneHanded = it }
            Page.PREVIEW_SIZE -> choices(list, null, Prefs.PreviewSize.entries, { it.label }, prefs.previewSize,
                "How big the letter that pops up over a key is while you type (with Character Preview on).") { prefs.previewSize = it }
            Page.LONG_PRESS -> choices(list, null, Prefs.LongPress.entries, { it.label }, prefs.longPressDelay,
                "How long to hold a key for accents, the trackpad and the emoji key menu.") { prefs.longPressDelay = it }
            Page.APPEARANCE -> appearance(list)
            Page.LIQUID_GLASS -> liquidGlass(list)
            Page.FEEDBACK -> feedback(list)
            Page.VIBRATION -> choices(list, null, Prefs.Vibration.entries, { it.label }, prefs.vibration, null) { prefs.vibration = it }
            Page.LAYOUT -> layout(list)
            Page.KEY_LAYOUT -> keyLayout(list)
            Page.HEIGHT -> choices(list, null, Prefs.Height.entries, { it.label }, prefs.keyboardHeight, null) { prefs.keyboardHeight = it }
            Page.EMOJI_LAYOUT -> choices(list, null, KeyStyle.entries, ::scrollLabel, prefs.emojiLayout,
                "Horizontal: emoji scroll sideways in sections. Vertical: emoji scroll up and down.") { prefs.emojiLayout = it }
            Page.CLIPBOARD_LAYOUT -> choices(list, null, KeyStyle.entries, ::clipLabel, prefs.clipboardLayout,
                "List: copied items one under another. Grid: copied items as cards in two columns.") { prefs.clipboardLayout = it }
            Page.GIF_LAYOUT -> choices(list, null, KeyStyle.entries, ::scrollLabel, prefs.gifLayout,
                "Horizontal: two rows that scroll sideways. Vertical: two columns that scroll down.") { prefs.gifLayout = it }
            Page.TOOLBAR -> toolbarPage?.build(list)
            Page.CLIPBOARD -> clipboard(list)
            Page.COPIED_STYLE -> choices(list, null, listOf(KeyStyle.IOS, KeyStyle.GBOARD), { if (it == KeyStyle.IOS) "Banner" else "Chips" },
                prefs.copiedStyle, "Banner: the copied text across the bar with where it came from; tap to paste. " +
                    "Chips: the copied text plus details picked out of it, such as a phone number or a link.") { prefs.copiedStyle = it }
            Page.RETENTION -> choices(list, null, Prefs.Retention.entries, { it.label }, prefs.clipboardRetention,
                "Pinned items are kept until you unpin them.") { prefs.clipboardRetention = it }
            Page.PRIVACY -> privacy(list)
            Page.RESET -> reset(list)
        }
        page.setContent(list.root)
        page.scroll.post { page.scroll.scrollTo(0, y) }
    }

    /** Back: pops a page, or leaves the app at the root. */
    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (!stack.pop()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private var backCallback: Any? = null

    /**
     * Android 13+ delivers back through a callback (and Android 16 no longer calls onBackPressed).
     * It's registered only while there's a page to go back to, so at the root the system's own
     * back-to-home animation runs. On Android 14+ the back swipe moves the page as it goes.
     */
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

    private var backRegistered = false

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = backCallback as? android.window.OnBackInvokedCallback ?: return
        val want = stack.pages.size > 1
        if (want == backRegistered) return
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        backRegistered = want
    }

    private fun hideIme() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ---- pages ----------------------------------------------------------------------------

    private fun main(list: IosList) {
        val enabled = keyboardEnabled
        val selected = keyboardSelected
        val status = when {
            selected -> "Ready to type"
            enabled -> "Enabled – tap to select it"
            else -> "Not enabled yet – tap to set up"
        }
        list.headerCard(R.drawable.ic_launcher_art, getString(R.string.app_name), status,
            if (selected) list.green else list.blue) {
            if (!enabled) startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            else getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        if (!selected) {
            list.section("Get Started")
            list.navRow("1.  Enable iPurrboard", if (enabled) "Done" else "", titleColor = list.blue) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            list.navRow("2.  Select iPurrboard", titleColor = list.blue) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
            list.footer("Turn on iPurrboard in Android's keyboard list, then pick it as your keyboard.")
        }

        list.section("Try It")
        list.customRow(tryField(list))
        tryPreview?.let { p -> list.customRow(p); showTryPreview(p.drawable != null) }
        if (!selected) previewField?.let { list.customRow(it) }

        list.section()
        list.navRow("Keyboard", icon = SettingsIcon.KEYBOARD) { open(Page.KEYBOARD) }

        list.section()
        list.navRow("Appearance", appearanceLabel(), icon = SettingsIcon.APPEARANCE) { open(Page.APPEARANCE) }
        list.navRow("Sounds & Haptics", icon = SettingsIcon.SOUNDS) { open(Page.FEEDBACK) }

        list.section()
        list.navRow("Layout", layoutLabel(prefs.keyLayout), icon = SettingsIcon.LAYOUT) { open(Page.LAYOUT) }
        list.navRow("Toolbar", if (prefs.toolbar) "On" else "Off", icon = SettingsIcon.TOOLBAR) { open(Page.TOOLBAR) }
        list.navRow("Clipboard", icon = SettingsIcon.CLIPBOARD) { open(Page.CLIPBOARD) }

        list.section()
        list.navRow("Privacy Policy", icon = SettingsIcon.PRIVACY) { open(Page.PRIVACY) }
        list.navRow("Reset", icon = SettingsIcon.RESET) { open(Page.RESET) }
        list.footer("iPurrboard ${versionName()}. Not affiliated with Apple or Google.")
    }

    /** The Try It field, kept across rebuilds so typing in it isn't interrupted. */
    private fun tryField(list: IosList): EditText {
        tryField?.let { return it }
        val field = EditText(this).apply {
            hint = "Try typing here"
            background = null
            setTextColor(list.label)
            setHintTextColor(list.secondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        tryField = field
        // The field also takes images (Android 12+), so GIFs, stickers and screenshots can be tried
        // here: what is sent shows below it.
        if (Build.VERSION.SDK_INT >= 31) {
            val preview = android.widget.ImageView(this).apply {
                adjustViewBounds = true
                maxHeight = list.dp(140)
            }
            tryPreview = preview
            field.setOnReceiveContentListener(arrayOf("image/*"), object : android.view.OnReceiveContentListener {
                override fun onReceiveContent(view: View, payload: android.view.ContentInfo): android.view.ContentInfo? {
                    val clip = payload.clip
                    val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return payload
                    runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                        val d = android.graphics.ImageDecoder.decodeDrawable(
                            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
                        preview.setImageDrawable(d)
                        (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                        showTryPreview(true)
                    }
                    return null
                }
            })
        }
        return field
    }

    /** The preview's row (and the separator above it) only shows once an image arrives. */
    private fun showTryPreview(on: Boolean) {
        val preview = tryPreview ?: return
        val row = preview.parent as? View ?: return
        val card = row.parent as? android.view.ViewGroup ?: return
        val v = if (on) View.VISIBLE else View.GONE
        row.visibility = v
        card.getChildAt(card.indexOfChild(row) - 1)?.visibility = v
    }

    /** Settings › Keyboard: iOS's General › Keyboard page, row for row, plus iPurrboard's extras. */
    private fun keyboard(list: IosList) {
        list.section()
        list.navRow("Languages", prefs.keyboards.joinToString(", ") { it.spaceLabel }) { open(Page.KEYBOARDS) }
        list.navRow("Text Replacement", "${prefs.textReplacements.size}") { open(Page.REPLACEMENTS) }
        list.navRow("Learned Words") { open(Page.LEARNED) }
        list.navRow("One-Handed Keyboard", oneHandedLabel(prefs.oneHanded)) { open(Page.ONE_HANDED) }

        // iOS 27.2 KeyboardSettings: the same switches in the same order.
        list.section("All Keyboards")
        list.switchRow("Auto-Capitalization", prefs.autoCapitalization) { prefs.autoCapitalization = it }
        list.switchRow("Auto-Correction", prefs.autoCorrection) { prefs.autoCorrection = it }
        list.switchRow("Predictive Text", prefs.predictiveText) { prefs.predictiveText = it }
        list.switchRow("Show Predictions Inline", prefs.inlinePredictions) { prefs.inlinePredictions = it }
        list.switchRow("Show Math Results", prefs.mathResults) { prefs.mathResults = it }
        list.switchRow("Check Spelling", prefs.checkSpelling) { prefs.checkSpelling = it }
        list.switchRow("Enable Caps Lock", prefs.capsLock) { prefs.capsLock = it }
        list.switchRow("Character Preview", prefs.characterPreview) { prefs.characterPreview = it }
        list.switchRow("“.” Shortcut", prefs.periodShortcut) { prefs.periodShortcut = it }
        list.switchRow("Smart Punctuation", prefs.smartPunctuation) { prefs.smartPunctuation = it }
        list.switchRow("Slide to Type", prefs.slideToType) { prefs.slideToType = it }
        list.switchRow("Delete Slide-to-Type by Word", prefs.deleteSlideByWord) { prefs.deleteSlideByWord = it }
        list.footer("Double tapping the space bar will insert a period followed by a space.")

        list.section("Emoji")
        list.switchRow("Emoji Suggestions", prefs.emojiSuggestions) { prefs.emojiSuggestions = it }
        list.switchRow("Emoji Kitchen Stickers", prefs.emojiKitchen) { prefs.emojiKitchen = it }
        list.footer("Emoji Suggestions offers an emoji for words like “pizza”. After an emoji, Emoji Kitchen " +
            "Stickers offers stickers mixing it with others (loaded from Google, sent as images).")

        list.section("Typing")
        list.switchRow("Block Offensive Words", prefs.blockOffensive) { prefs.blockOffensive = it }
        list.navRow("Key Preview Size", prefs.previewSize.label) { open(Page.PREVIEW_SIZE) }
        list.navRow("Long-Press Delay", prefs.longPressDelay.label) { open(Page.LONG_PRESS) }

        list.section("Dictation")
        list.switchRow("Enable Dictation", prefs.voiceTyping) { prefs.voiceTyping = it }
        list.footer("Dictation uses your phone's speech recognition service, which may process your voice online.")
    }

    /** A checkmark list of [items] (iOS's choice page); picking one checks it and stays here. */
    private fun <T> choices(list: IosList, header: String?, items: List<T>, label: (T) -> String, current: T, footer: String?, set: (T) -> Unit) {
        list.section(header)
        for (i in items) list.checkRow(label(i), i == current) { set(i) }
        footer?.let { list.footer(it) }
    }

    private fun appearance(list: IosList) {
        val dark = KeyboardTheme.isDark(this)
        list.section("Style")
        list.tilesRow(listOf(
            IosList.Tile("Classic", prefs.appearance == Prefs.Appearance.CLASSIC, { c, r -> MiniKeyboard.draw(c, r, glass = false, dark = dark) }) {
                prefs.appearance = Prefs.Appearance.CLASSIC
            },
            IosList.Tile("Glass", prefs.appearance == Prefs.Appearance.GLASS, { c, r -> MiniKeyboard.draw(c, r, glass = true, dark = dark) }) {
                prefs.appearance = Prefs.Appearance.GLASS
            },
        ))
        if (prefs.appearance == Prefs.Appearance.GLASS) {
            list.navRow("Liquid Glass", tintLabel()) { open(Page.LIQUID_GLASS) }
        }
        list.footer("Glass blurs the app behind the keyboard and rounds its corners (Android 12 or newer).")

        // As iOS's Light / Dark picker: two previews, and Automatic to follow the phone.
        val glass = prefs.appearance == Prefs.Appearance.GLASS
        list.section("Light & Dark")
        list.tilesRow(listOf(
            IosList.Tile("Light", !dark, { c, r -> MiniKeyboard.draw(c, r, glass, dark = false) }) { prefs.themeMode = Prefs.ThemeMode.LIGHT },
            IosList.Tile("Dark", dark, { c, r -> MiniKeyboard.draw(c, r, glass, dark = true) }) { prefs.themeMode = Prefs.ThemeMode.DARK },
        ))
        list.switchRow("Automatic", prefs.themeMode == Prefs.ThemeMode.AUTO) { on ->
            prefs.themeMode = if (on) Prefs.ThemeMode.AUTO else if (dark) Prefs.ThemeMode.DARK else Prefs.ThemeMode.LIGHT
        }
        list.footer("Automatic follows your phone's dark mode.")

        // Only builds that include an emoji image pack get this switch.
        if (EmojiArt.available(this)) {
            list.section("Emoji")
            list.switchRow(EmojiArt.name, prefs.emojiArt) { prefs.emojiArt = it }
        }
    }

    /** Settings › Appearance › Liquid Glass, as on iOS 27: Tint Amount and Show Borders. */
    private fun liquidGlass(list: IosList) {
        // A live preview over a colourful backdrop when the real keyboard isn't the preview below.
        val preview = if (keyboardSelected) glassPreview(list) else null
        list.section()
        list.sliderRow(
            value = prefs.glassTint / 100f,
            captions = listOf("More Clear", "Default", "More Tinted"),
            ticks = floatArrayOf(0.5f),
            onChange = { v ->
                val t = snapTint((v * 100).toInt())
                val theme = KeyboardTheme.forContext(this, glass = true, blur = false, tintAmount = t)
                preview?.let { applyPreviewTheme(it, theme) }
                previewKeyboard?.theme = theme
            },
        ) { v ->
            prefs.glassTint = snapTint((v * 100).toInt())
        }
        list.switchRow("Show Borders", prefs.glassBorders) { prefs.glassBorders = it }
        list.footer("Clear is more transparent and tinted increases opacity, adding contrast to the keys.")
    }

    private fun snapTint(p: Int) = if (kotlin.math.abs(p - 50) <= TINT_SNAP) 50 else p.coerceIn(0, 100)

    private fun tintLabel() = when {
        prefs.glassTint == 50 -> "Default"
        prefs.glassTint < 50 -> "Clear"
        else -> "Tinted"
    }

    /** A non-interactive keyboard over a colourful backdrop, so the glass tint shows. */
    private fun glassPreview(list: IosList): KeyboardView {
        val field = EditText(this)
        lateinit var kb: KeyboardView
        kb = KeyboardView(this, PreviewListener(field) { kb })
        kb.spec = prefs.spec(password = false)
        kb.paintsPanel = false
        kb.setOnTouchListener { _, _ -> true }
        applyPreviewTheme(kb, KeyboardTheme.forContext(this, glass = true, blur = false))
        val backdrop = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF4F8DFD.toInt(), 0xFFB26CF5.toInt(), 0xFFFF8A65.toInt()))
            setPadding(0, list.dp(56), 0, 0)
            addView(kb, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        list.section()
        list.customRow(backdrop)
        // Edge to edge inside the card.
        (backdrop.parent as View).setPadding(0, 0, 0, 0)
        return kb
    }

    private fun applyPreviewTheme(kb: KeyboardView, theme: KeyboardTheme) {
        kb.theme = theme
        val r = 20f * resources.displayMetrics.density
        kb.background = GradientDrawable().apply {
            setColor(theme.panelTint)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }


    /** Settings › Sounds & Haptics: Keyboard Feedback (iOS's Sound and Haptic), volume and strength. */
    private fun feedback(list: IosList) {
        list.section("Keyboard Feedback")
        list.switchRow("Sound", prefs.sound) { prefs.sound = it }
        list.switchRow("Haptic", prefs.haptic) { prefs.haptic = it }
        list.footer("Key clicks follow the ringer: they're silent when the phone is on vibrate or silent.")

        if (prefs.sound) {
            list.section("Click Volume")
            list.sliderRow(
                value = prefs.soundVolume / 100f,
                minIcon = SpeakerDrawable(list.secondary, 0),
                maxIcon = SpeakerDrawable(list.secondary, 2),
            ) { v -> prefs.soundVolume = (v * 100).toInt() }
        }
        if (prefs.haptic) {
            list.section()
            list.navRow("Vibration Strength", prefs.vibration.label) { open(Page.VIBRATION) }
        }
    }

    private fun layout(list: IosList) {
        list.section("Keys")
        list.navRow("Key Layout", layoutLabel(prefs.keyLayout)) { open(Page.KEY_LAYOUT) }
        list.switchRow("Symbol Hints", prefs.symbolHints) { prefs.symbolHints = it }
        list.switchRow("Number Row", prefs.numberRow) { prefs.numberRow = it }
        list.switchRow("Number Row for Passwords", prefs.numberRowPasswords) { prefs.numberRowPasswords = it }
        list.navRow("Keyboard Height", prefs.keyboardHeight.label) { open(Page.HEIGHT) }
        list.footer("Symbol Hints shows the symbol you get by holding each letter.")

        list.section("Panels")
        list.navRow("Emoji", scrollLabel(prefs.emojiLayout)) { open(Page.EMOJI_LAYOUT) }
        list.navRow("Clipboard", clipLabel(prefs.clipboardLayout)) { open(Page.CLIPBOARD_LAYOUT) }
        if (BuildConfig.GIFS) list.navRow("GIFs", scrollLabel(prefs.gifLayout)) { open(Page.GIF_LAYOUT) }
    }

    private fun keyLayout(list: IosList) {
        list.section()
        for (s in KeyStyle.entries) {
            list.checkRow(layoutLabel(s), prefs.keyLayout == s) {
                if (prefs.keyLayout != s) {
                    prefs.keyLayout = s
                    // Corner symbols and the toolbar come with the Extended layout; both can be changed after.
                    prefs.symbolHints = s == KeyStyle.GBOARD
                    prefs.toolbar = s == KeyStyle.GBOARD
                    prefs.emojiLayout = s
                    prefs.clipboardLayout = s
                    prefs.gifLayout = s
                }
            }
        }
        list.footer("Extended adds comma and period keys to the bottom row, like many Android keyboards, and " +
            "turns on symbol hints and the toolbar.")
    }

    private fun clipboard(list: IosList) {
        list.section()
        list.switchRow("Show Copied Items", prefs.showCopiedItems) { prefs.showCopiedItems = it }
        list.switchRow("Clipboard History", prefs.clipboardHistory) { prefs.clipboardHistory = it }
        list.footer("Recently copied text appears in the suggestion bar, and in the clipboard (hold the emoji key, " +
            "or use the toolbar). Text marked sensitive, such as passwords, is never kept.")

        list.section()
        list.navRow("Copied Items Style", if (prefs.copiedStyle == KeyStyle.IOS) "Banner" else "Chips") { open(Page.COPIED_STYLE) }
        list.navRow("Keep History For", prefs.clipboardRetention.label) { open(Page.RETENTION) }

        list.section()
        list.switchRow("Save Recent Screenshots", prefs.clipboardScreenshots && Screenshots.granted(this)) { on ->
            if (on && !Screenshots.granted(this)) {
                requestPermissions(arrayOf(Screenshots.permission), REQUEST_PHOTOS)
            } else {
                prefs.clipboardScreenshots = on
            }
        }
        list.footer("Screenshots you take appear in the clipboard, kept as long as copied text. " +
            "They can be pasted into apps that accept images. Needs access to your photos.")
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            val ok = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok) {
                IosAlert(this)
                    .title("Microphone Allowed")
                    .message("Go back to where you were typing and tap the microphone to dictate.")
                    .action("OK", IosAlert.Style.PREFERRED) { finish() }
                    .show()
            } else if (!shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) {
                openSettingsAlert("Allow Microphone", "To type with your voice, allow iPurrboard to use the microphone in Settings.")
            }
            return
        }
        if (requestCode != REQUEST_PHOTOS) return
        val ok = Screenshots.granted(this)
        prefs.clipboardScreenshots = ok
        if (!ok && !shouldShowRequestPermissionRationale(Screenshots.permission)) {
            // Denied for good: only the system's app settings can allow it now.
            openSettingsAlert("Allow Photo Access", "To save screenshots in the clipboard, allow iPurrboard to access photos in Settings.")
        }
        stack.top?.let { render(it) }
    }

    /**
     * Settings › Keyboard › Languages: a switch per language, and (with two or more on) which one
     * is typing. Every change applies at once; nothing hides behind dialogs.
     */
    private fun keyboards(list: IosList) {
        val enabled = prefs.keyboards
        list.section()
        for (l in Language.entries) {
            val on = l in enabled
            val sw = list.switchRow(l.label, on, subtitle = l.description) { checked ->
                val now = prefs.keyboards
                prefs.keyboards = Language.entries.filter { if (it == l) checked else it in now }
                // A newly added language is usually the one wanted next.
                if (checked) prefs.keyboard = l
            }
            // The last language on can't be turned off.
            if (on && enabled.size == 1) sw.isEnabled = false
        }
        list.footer(if (enabled.size > 1) {
            "The keyboard knows the words of every language that's on, so you can mix them " +
                "without switching. At least one stays on."
        } else {
            "Turn on another language to type in it. Both use the same keys. At least one stays on."
        })

        if (enabled.size > 1) {
            list.section("Typing In")
            val current = prefs.keyboard
            for (l in Language.entries.filter { it in enabled }) list.checkRow(l.label, l == current) { prefs.keyboard = l }
            list.footer("Its suggestions, emoji and dictation come first. To switch while typing, hold " +
                "the emoji key and pick a language; the space bar shows its name.")
        }
    }

    /** Settings › Keyboard › Text Replacement, as on iOS: + adds one; tap one to edit it. */
    private fun replacements(list: IosList, page: IosPage) {
        page.setRight(GlassButton(this, GlassButton.Kind.PLUS).apply { setOnClickListener { open(Page.REPLACEMENT_EDIT, -1) } })
        val items = prefs.textReplacements
        list.section()
        if (items.isEmpty()) list.placeholderRow("No Text Replacements")
        // As on iOS: the phrase, with its shortcut at the right.
        items.forEachIndexed { i, (shortcut, phrase) -> list.navRow(phrase, shortcut) { open(Page.REPLACEMENT_EDIT, i) } }
        list.footer("Type a shortcut and press space to replace it with its phrase.")
    }

    /** Adding or editing one text replacement: Phrase and Shortcut, saved with the bar's Save. */
    private fun editReplacement(list: IosList, page: IosPage, index: Int) {
        val items = prefs.textReplacements.toMutableList()
        val existing = items.getOrNull(index)
        list.section()
        val phrase = list.fieldRow("Phrase", existing?.second.orEmpty())
        val shortcut = list.fieldRow("Shortcut", existing?.first.orEmpty(),
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        list.footer("Create a shortcut that will automatically expand into the word or phrase as you type.")
        val save = GlassButton(this, GlassButton.Kind.TEXT, "Save")
        fun valid() = phrase.text.isNotBlank() && shortcut.text.isNotBlank() && !shortcut.text.contains(' ')
        save.textEnabled = valid()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { save.textEnabled = valid() }
        }
        phrase.addTextChangedListener(watcher)
        shortcut.addTextChangedListener(watcher)
        save.setOnClickListener {
            if (!valid()) return@setOnClickListener
            val entry = shortcut.text.toString().trim() to phrase.text.toString().trim()
            if (existing == null) items.add(entry) else items[index] = entry
            prefs.textReplacements = items
            stack.pop()
        }
        page.setRight(save)
        if (existing != null) {
            list.section()
            list.actionRow("Delete Text Replacement", list.red) {
                items.removeAt(index)
                prefs.textReplacements = items
                stack.pop()
            }
        } else {
            // A new one: start typing the phrase straight away.
            phrase.post {
                phrase.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(phrase, 0)
            }
        }
    }

    /** Settings › Reset, named as iOS's Reset page. */
    private fun reset(list: IosList) {
        list.section()
        list.actionRow("Reset All Settings") { confirmResetToIos() }
        list.actionRow("Reset Keyboard Dictionary") { confirmResetDictionary() }
        list.section()
        list.actionRow("Show Welcome Screens") { startActivity(Intent(this, WelcomeActivity::class.java)) }
        list.footer("Reset All Settings turns every setting back to its default and the extras (toolbar, number row, " +
            (if (BuildConfig.GIFS) "GIFs " else "clipboard history ") + "and more) off; text replacements, learned " +
            "words and pinned clips are kept. Reset Keyboard Dictionary deletes the words the keyboard has learned.")
    }

    /** Settings › Privacy Policy: the same text as PRIVACY.md, in grouped cards. */
    private fun privacy(list: IosList) {
        list.section()
        list.customRow(paragraph(list, PrivacyPolicy.SUMMARY, list.label))
        list.footer("Last updated ${PrivacyPolicy.UPDATED}")
        for ((title, paragraphs) in PrivacyPolicy.SECTIONS) {
            list.section(title)
            for (p in paragraphs) list.customRow(paragraph(list, p, list.label))
        }
    }

    private fun paragraph(list: IosList, text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
        setPadding(0, list.dp(8), 0, list.dp(8))
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** The preview keyboard only shows while iPurrboard isn't the active keyboard. */
    private fun updatePreview() {
        val keyboard = previewKeyboard ?: return
        keyboard.visibility = if (keyboardSelected) View.GONE else View.VISIBLE
        // No window blur here, so glass uses its more opaque tint.
        keyboard.theme = KeyboardTheme.forContext(this, prefs.appearance == Prefs.Appearance.GLASS, blur = false)
        keyboard.oneHanded = prefs.oneHanded
        keyboard.spec = prefs.spec(password = false)
        keyboard.longPressMs = prefs.longPressDelay.ms
        keyboard.emojiArt = prefs.emojiArt
        keyboard.requestApplyInsets()
    }

    /** A confirmation, since this changes many settings at once. */
    private fun confirmResetToIos() {
        IosAlert(this)
            .title("Reset All Settings?")
            .message("All keyboard settings will go back to their defaults. The toolbar, number row, " +
                (if (BuildConfig.GIFS) "GIFs, " else "") + "Emoji Kitchen and clipboard history will be turned off " +
                "(unpinned clips are cleared). Text replacements, learned words and pinned clips stay.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Reset", IosAlert.Style.DESTRUCTIVE) { prefs.resetToIos() }
            .show()
    }

    private fun confirmResetDictionary() {
        IosAlert(this)
            .title("Reset Keyboard Dictionary?")
            .message("Learned words, word pairs and undone corrections are deleted. Text replacements and settings are kept.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Reset", IosAlert.Style.DESTRUCTIVE) { UserWords.get(this).clearAll() }
            .show()
    }

    /** A permission denied for good: only Android's app settings can allow it now. */
    private fun openSettingsAlert(title: String, message: String) {
        IosAlert(this)
            .title(title)
            .message(message)
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Open Settings", IosAlert.Style.PREFERRED) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
            .show()
    }

    private fun versionName() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private fun oneHandedLabel(o: Prefs.OneHanded) = when (o) {
        Prefs.OneHanded.OFF -> "Off"
        Prefs.OneHanded.LEFT -> "Left"
        Prefs.OneHanded.RIGHT -> "Right"
    }

    private fun appearanceLabel() = when (prefs.appearance) {
        Prefs.Appearance.CLASSIC -> "Classic"
        Prefs.Appearance.GLASS -> "Glass"
    }

    private fun layoutLabel(s: KeyStyle) = when (s) {
        KeyStyle.IOS -> "Standard"
        KeyStyle.GBOARD -> "Extended"
    }

    /** Emoji and GIF panels: which way they scroll. */
    private fun scrollLabel(s: KeyStyle) = when (s) {
        KeyStyle.IOS -> "Horizontal"
        KeyStyle.GBOARD -> "Vertical"
    }

    private fun clipLabel(s: KeyStyle) = if (s == KeyStyle.IOS) "List" else "Grid"

    companion object {
        private const val REQUEST_PHOTOS = 7
        private const val REQUEST_MIC = 8
        /** Tint Amount snaps to Default within this many steps of the middle. */
        private const val TINT_SNAP = 4
        private const val STATE_ROUTES = "routes"
        /** Set by the keyboard's dictation panel: ask for the microphone permission on arrival. */
        const val EXTRA_REQUEST_MIC = "requestMic"
        const val EXTRA_PAGE = "page"
    }
}
