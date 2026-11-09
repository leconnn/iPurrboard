package dev.leconnn.ioskeyboard

import dev.leconnn.ioskeyboard.text.Language
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.leconnn.ioskeyboard.emoji.AppleEmojiImages
import dev.leconnn.ioskeyboard.ui.IosAlert
import dev.leconnn.ioskeyboard.ui.IosList
import dev.leconnn.ioskeyboard.ui.SettingsIcon
import dev.leconnn.ioskeyboard.ui.SwitchClock
import dev.leconnn.ioskeyboard.ui.ThemeMode

/**
 * The settings app, laid out like modern iOS Settings: a short main page (status card, setup
 * while it's incomplete, a try-it field and grouped links with icons) and one page per topic.
 * Every page is this activity with a [Page] extra, so the system back button and the "‹ Settings"
 * link both work. Choices are checkmark lists on their page, as in iOS, rather than dialogs.
 */
class SettingsActivity : Activity() {

    enum class Page(val title: String) {
        MAIN("Settings"),
        TYPING("Typing"),
        PREDICTIONS("Predictions"),
        SLIDE("Slide to Type"),
        APPEARANCE("Appearance"),
        LAYOUT("Layout"),
        CLIPBOARD("Clipboard"),
        FEEDBACK("Sound & Haptics"),
        REPLACEMENTS("Text Replacement"),
        KEYBOARDS("Languages"),
        PRIVACY("Privacy Policy"),
    }

    private lateinit var prefs: Prefs
    private lateinit var page: Page
    private lateinit var scroll: ScrollView
    private var previewKeyboard: KeyboardView? = null
    private var previewViews: List<View> = emptyList()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // A forced light/dark mode restyles the screen, so rebuild it.
        if (key == Prefs.KEY_THEME_MODE) { recreate(); return@OnSharedPreferenceChangeListener }
        // Emoji picks don't change anything shown here.
        if (key == "emojiRecents" || key == "emojiTones") return@OnSharedPreferenceChangeListener
        // Switching keyboards while typing in Try It must not rebuild the page (it would take
        // the field's focus and close the keyboard); only the Keyboards page shows it.
        if (key == Prefs.KEY_KEYBOARD && page != Page.KEYBOARDS) return@OnSharedPreferenceChangeListener
        // Let a flipped switch finish sliding before the page is rebuilt.
        scroll.removeCallbacks(rerender)
        if (SwitchClock.justFlipped()) scroll.postDelayed(rerender, SwitchClock.ANIM_MS) else rerender.run()
    }
    private val rerender = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        render()
        updatePreview()
    }

    /** Applies a forced Light or Dark mode to this screen (Automatic keeps the system's). */
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(ThemeMode.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        page = intent.getStringExtra(EXTRA_PAGE)?.let { n -> Page.entries.firstOrNull { it.name == n } } ?: Page.MAIN
        scroll = ScrollView(this)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Apps targeting Android 15+ draw edge to edge; keep content clear of the system bars.
        // The keyboard is different: as on iOS, the list keeps going behind it (padded so every row
        // can still be scrolled into view), so a Glass keyboard has the page to show through
        // instead of a plain black window background.
        scroll.clipToPadding = false
        outer.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val imeShown = ime.bottom > bars.bottom
            v.setPadding(bars.left, bars.top, bars.right, if (imeShown) 0 else bars.bottom)
            scroll.setPadding(0, 0, 0, if (imeShown) ime.bottom else 0)
            insets
        }
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (page == Page.MAIN) {
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
        }
        setContentView(outer)
        ThemeMode.styleBars(this)
        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false) && savedInstanceState == null) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    private var previewField: EditText? = null

    override fun onResume() {
        super.onResume()
        if (ThemeMode.refreshIfChanged(this)) return
        prefs.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        render()
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

    /** Rebuilds the page (cheap), keeping the scroll position. */
    private fun render() {
        val y = scroll.scrollY
        val list = IosList(this)
        scroll.setBackgroundColor(list.background)
        (scroll.parent as? View)?.setBackgroundColor(list.background)
        // The preview field moves into the new page; detach it from the old one first.
        (previewField?.parent as? android.view.ViewGroup)?.removeView(previewField)
        if (page != Page.MAIN) list.backButton(Page.MAIN.title) { finish() }
        list.largeTitle(page.title)
        when (page) {
            Page.MAIN -> main(list)
            Page.TYPING -> typing(list)
            Page.PREDICTIONS -> predictions(list)
            Page.SLIDE -> slide(list)
            Page.APPEARANCE -> appearance(list)
            Page.LAYOUT -> layout(list)
            Page.CLIPBOARD -> clipboard(list)
            Page.FEEDBACK -> feedback(list)
            Page.REPLACEMENTS -> replacements(list)
            Page.KEYBOARDS -> keyboards(list)
            Page.PRIVACY -> privacy(list)
        }
        scroll.removeAllViews()
        scroll.addView(list.root)
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun open(p: Page) = startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_PAGE, p.name))

    // ---- pages ----------------------------------------------------------------------------

    private fun main(list: IosList) {
        val enabled = keyboardEnabled
        val selected = keyboardSelected
        val status = when {
            selected -> "Ready to type ✓"
            enabled -> "Enabled – tap to select it"
            else -> "Not enabled yet – tap to set up"
        }
        list.headerCard(R.drawable.ic_launcher_art, getString(R.string.app_name), status,
            if (selected) 0xFF34C759.toInt() else list.blue) {
            if (!enabled) startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            else getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        if (!selected) {
            list.section("Get started")
            list.navRow("1.  Enable iPurrboard", if (enabled) "Done" else "", titleColor = list.blue) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            list.navRow("2.  Select iPurrboard", titleColor = list.blue) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
            list.footer("Turn on iPurrboard in Android's keyboard list, then pick it as your keyboard.")
        }

        list.section("Try it")
        val tryField = EditText(this).apply {
            hint = "Try typing here"
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        list.customRow(tryField)
        // The field also takes images (Android 12+), so GIFs, stickers and screenshots can be tried
        // here: what is sent shows below it.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val preview = android.widget.ImageView(this).apply {
                adjustViewBounds = true
                maxHeight = list.dp(140)
                visibility = View.GONE
            }
            list.customRow(preview)
            // The preview's row (and the divider above it) only shows once an image arrives.
            val row = preview.parent as View
            val card = row.parent as android.view.ViewGroup
            val divider = card.getChildAt(card.indexOfChild(row) - 1)
            fun showPreview(on: Boolean) {
                val v = if (on) View.VISIBLE else View.GONE
                row.visibility = v; divider?.visibility = v; preview.visibility = v
            }
            showPreview(false)
            tryField.setOnReceiveContentListener(arrayOf("image/*"), object : android.view.OnReceiveContentListener {
                override fun onReceiveContent(view: View, payload: android.view.ContentInfo): android.view.ContentInfo? {
                    val clip = payload.clip
                    val uri = (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri } ?: return payload
                    runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                        val d = android.graphics.ImageDecoder.decodeDrawable(
                            android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
                        preview.setImageDrawable(d)
                        (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                        showPreview(true)
                    }
                    return null
                }
            })
        }
        if (!selected) previewField?.let { list.customRow(it) }

        list.section()
        list.navRow("Languages", prefs.keyboards.joinToString(", ") { it.spaceLabel }, icon = SettingsIcon.KEYBOARDS) {
            open(Page.KEYBOARDS)
        }
        list.navRow("Typing", icon = SettingsIcon.TYPING) { open(Page.TYPING) }
        list.navRow("Predictions", icon = SettingsIcon.PREDICTIONS) { open(Page.PREDICTIONS) }
        list.navRow("Slide to Type", if (prefs.slideToType) "On" else "Off", icon = SettingsIcon.SLIDE) { open(Page.SLIDE) }

        list.section()
        list.navRow("Appearance", appearanceLabel(), icon = SettingsIcon.APPEARANCE) { open(Page.APPEARANCE) }
        list.navRow("Layout", layoutLabel(prefs.keyLayout), icon = SettingsIcon.LAYOUT) { open(Page.LAYOUT) }

        list.section()
        list.navRow("Toolbar", if (prefs.toolbar) "On" else "Off", icon = SettingsIcon.TOOLBAR) {
            startActivity(Intent(this, ToolbarSettingsActivity::class.java))
        }
        list.navRow("Clipboard", icon = SettingsIcon.CLIPBOARD) { open(Page.CLIPBOARD) }
        list.navRow("Sound & Haptics", icon = SettingsIcon.FEEDBACK) { open(Page.FEEDBACK) }

        list.section()
        list.navRow("Text Replacement", "${prefs.textReplacements.size}", icon = SettingsIcon.REPLACEMENT) { open(Page.REPLACEMENTS) }
        list.navRow("Learned Words", icon = SettingsIcon.LEARNED) { startActivity(Intent(this, LearnedWordsActivity::class.java)) }

        list.section()
        list.actionRow("Reset to Defaults", list.red) { confirmResetToIos() }
        list.footer("Sets every setting back to its default and turns off the extras (toolbar, number row, " +
            (if (BuildConfig.GIFS) "GIFs " else "clipboard history ") + "and more). Text replacements, learned words " +
            "and pinned clips are kept.")

        list.section()
        list.navRow("Privacy Policy") { open(Page.PRIVACY) }
        list.footer("iPurrboard ${versionName()}. Not affiliated with Apple or Google.")
    }

    private fun typing(list: IosList) {
        list.section()
        list.switchRow("Auto-Capitalization", prefs.autoCapitalization) { prefs.autoCapitalization = it }
        list.switchRow("Auto-Correction", prefs.autoCorrection) { prefs.autoCorrection = it }
        list.switchRow("Check Spelling", prefs.checkSpelling) { prefs.checkSpelling = it }
        list.switchRow("Enable Caps Lock", prefs.capsLock) { prefs.capsLock = it }
        list.switchRow("Smart Punctuation", prefs.smartPunctuation) { prefs.smartPunctuation = it }
        list.switchRow("Character Preview", prefs.characterPreview) { prefs.characterPreview = it }
        list.switchRow("“.” Shortcut", prefs.periodShortcut) { prefs.periodShortcut = it }
        list.footer("Double tapping the space bar will insert a period followed by a space.")

        list.section("Long-press delay")
        for (d in Prefs.LongPress.entries) list.checkRow(d.label, prefs.longPressDelay == d) { prefs.longPressDelay = d }
        list.footer("How long to hold a key for accents, the trackpad and the emoji key menu.")
    }

    private fun predictions(list: IosList) {
        list.section()
        list.switchRow("Predictive Text", prefs.predictiveText) { prefs.predictiveText = it }
        list.switchRow("Show Predictions Inline", prefs.inlinePredictions) { prefs.inlinePredictions = it }
        list.switchRow("Emoji Suggestions", prefs.emojiSuggestions) { prefs.emojiSuggestions = it }
        list.switchRow("Emoji Kitchen Stickers", prefs.emojiKitchen) { prefs.emojiKitchen = it }
        list.switchRow("Show Math Results", prefs.mathResults) { prefs.mathResults = it }
        list.switchRow("Block Offensive Words", prefs.blockOffensive) { prefs.blockOffensive = it }
        list.footer("Emoji Suggestions offers an emoji for words like “pizza”. After an emoji, Emoji Kitchen " +
            "Stickers offers stickers mixing it with others (loaded from Google, sent as images). Show Math " +
            "Results completes sums such as 12*4=.")

        list.section("Your words")
        list.navRow("Text Replacement", "${prefs.textReplacements.size}", icon = SettingsIcon.REPLACEMENT) { open(Page.REPLACEMENTS) }
        list.navRow("Learned Words", icon = SettingsIcon.LEARNED) { startActivity(Intent(this, LearnedWordsActivity::class.java)) }
    }

    private fun slide(list: IosList) {
        list.section()
        list.switchRow("Slide to Type", prefs.slideToType) { prefs.slideToType = it }
        list.switchRow("Delete Slide-to-Type by Word", prefs.deleteSlideByWord) { prefs.deleteSlideByWord = it }
        list.footer("Slide your finger from letter to letter to type a word. With Delete by Word, one backspace removes the whole slid word.")
    }

    private fun appearance(list: IosList) {
        list.section("Style")
        list.checkRow("Classic", prefs.appearance == Prefs.Appearance.CLASSIC) { prefs.appearance = Prefs.Appearance.CLASSIC }
        list.checkRow("Glass", prefs.appearance == Prefs.Appearance.GLASS) { prefs.appearance = Prefs.Appearance.GLASS }
        list.footer("Glass blurs the app behind the keyboard and rounds its corners (Android 12 or newer).")

        list.section("Light / Dark")
        list.checkRow("Automatic", prefs.themeMode == Prefs.ThemeMode.AUTO) { prefs.themeMode = Prefs.ThemeMode.AUTO }
        list.checkRow("Light", prefs.themeMode == Prefs.ThemeMode.LIGHT) { prefs.themeMode = Prefs.ThemeMode.LIGHT }
        list.checkRow("Dark", prefs.themeMode == Prefs.ThemeMode.DARK) { prefs.themeMode = Prefs.ThemeMode.DARK }

        // Only builds that include the personal-use Apple emoji asset get this switch.
        if (AppleEmojiImages.available(this)) {
            list.section("Emoji")
            list.switchRow("Apple Emoji Style", prefs.appleEmoji) { prefs.appleEmoji = it }
        }
    }

    private fun layout(list: IosList) {
        list.section("Key layout")
        for (s in KeyStyle.entries) {
            list.checkRow(layoutLabel(s), prefs.keyLayout == s) {
                if (prefs.keyLayout != s) {
                    prefs.keyLayout = s
                    // Corner symbols and the toolbar come with the Gboard layout; both can be changed after.
                    prefs.symbolHints = s == KeyStyle.GBOARD
                    prefs.toolbar = s == KeyStyle.GBOARD
                    prefs.emojiLayout = s
                    prefs.clipboardLayout = s
                    prefs.gifLayout = s
                }
            }
        }
        list.switchRow("Symbol Hints", prefs.symbolHints) { prefs.symbolHints = it }
        list.footer("Extended adds comma and period keys to the bottom row, like many Android keyboards. " +
            "Symbol Hints shows the symbol you get by holding each letter.")

        list.section("Emoji")
        for (s in KeyStyle.entries) {
            list.checkRow(scrollLabel(s), prefs.emojiLayout == s) { prefs.emojiLayout = s }
        }
        list.footer("Horizontal: emoji scroll sideways in sections. Vertical: emoji scroll up and down.")

        list.section("Clipboard")
        for (s in KeyStyle.entries) {
            list.checkRow(if (s == KeyStyle.IOS) "List" else "Grid", prefs.clipboardLayout == s) { prefs.clipboardLayout = s }
        }
        list.footer("List: copied items one under another. Grid: copied items as cards in two columns.")

        if (BuildConfig.GIFS) {
            list.section("GIFs")
            for (s in KeyStyle.entries) {
                list.checkRow(scrollLabel(s), prefs.gifLayout == s) { prefs.gifLayout = s }
            }
            list.footer("Horizontal: two rows that scroll sideways. Vertical: two columns that scroll down.")
        }

        list.section("Number row")
        list.switchRow("Number Row", prefs.numberRow) { prefs.numberRow = it }
        list.switchRow("Number Row for Passwords", prefs.numberRowPasswords) { prefs.numberRowPasswords = it }

        list.section("Keyboard height")
        for (h in Prefs.Height.entries) list.checkRow(h.label, prefs.keyboardHeight == h) { prefs.keyboardHeight = h }

        list.section("One-handed keyboard")
        for (o in Prefs.OneHanded.entries) list.checkRow(oneHandedLabel(o), prefs.oneHanded == o) { prefs.oneHanded = o }
    }

    private fun clipboard(list: IosList) {
        list.section()
        list.switchRow("Show Copied Items", prefs.showCopiedItems) { prefs.showCopiedItems = it }
        list.switchRow("Clipboard History", prefs.clipboardHistory) { prefs.clipboardHistory = it }
        list.footer("Recently copied text appears in the suggestion bar, and in the clipboard (hold the emoji key, " +
            "or use the toolbar). Text marked sensitive, such as passwords, is never kept.")

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

        list.section("Keep history for")
        for (r in Prefs.Retention.entries) list.checkRow(r.label, prefs.clipboardRetention == r) { prefs.clipboardRetention = r }
        list.footer("Pinned items are kept until you unpin them.")
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
        render()
    }

    private fun feedback(list: IosList) {
        list.section()
        list.switchRow("Sound", prefs.sound) { prefs.sound = it }
        list.customRow(volumeRow(list))
        list.switchRow("Haptic", prefs.haptic) { prefs.haptic = it }

        list.section("Vibration strength")
        for (v in Prefs.Vibration.entries) list.checkRow(v.label, prefs.vibration == v) { prefs.vibration = v }
        list.footer("Key clicks follow the ringer: they're silent when the phone is on vibrate or silent.")
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
        keyboard.appleEmoji = prefs.appleEmoji
    }

    /** A confirmation, since this changes many settings at once. */
    /**
     * Settings > Languages: a switch per language, and (with two or more on) which one is typing.
     * Every change applies at once; nothing hides behind dialogs.
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
            if (on && enabled.size == 1) {
                sw.isEnabled = false
                sw.alpha = 0.5f
                (sw.parent as View).isEnabled = false
            }
        }
        list.footer(if (enabled.size > 1) {
            "The keyboard knows the words of every language that's on, so you can mix them (Taglish) " +
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

    /** Settings > Privacy Policy: the same text as PRIVACY.md, in grouped cards. */
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

    private fun confirmResetToIos() {
        IosAlert(this)
            .title("Reset to Defaults?")
            .message("All keyboard settings will go back to their defaults. The toolbar, number row, " +
                (if (BuildConfig.GIFS) "GIFs, " else "") + "Emoji Kitchen and clipboard history will be turned off " +
                "(unpinned clips are cleared). Text replacements, learned words and pinned clips stay.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Reset", IosAlert.Style.DESTRUCTIVE) { prefs.resetToIos() }
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

    /** "Volume" with a slider, saved when the finger lifts. */
    private fun volumeRow(list: IosList): View {
        val label = TextView(this).apply {
            text = "Volume"
            textSize = 17f
            setTextColor(list.label)
        }
        val bar = android.widget.SeekBar(this).apply {
            max = 100
            progress = prefs.soundVolume
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(s: android.widget.SeekBar) = Unit
                override fun onStopTrackingTouch(s: android.widget.SeekBar) { prefs.soundVolume = s.progress }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = list.dp(48)
            addView(label)
            addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = list.dp(12) })
        }
    }

    /** Settings > Text Replacement, as on iOS: a list of shortcuts; tap one to edit it. */
    private fun replacements(list: IosList) {
        list.section()
        list.actionRow("Add Text Replacement…") { editReplacement(null) }
        val items = prefs.textReplacements
        list.section("Replacements")
        if (items.isEmpty()) list.placeholderRow("None")
        items.forEachIndexed { i, (shortcut, phrase) -> list.navRow(shortcut, phrase) { editReplacement(i) } }
        list.footer("Type a shortcut and press space to replace it with its phrase.")
    }

    /** Edit entry [index], or add a new one when [index] is null. */
    private fun editReplacement(index: Int?) {
        val items = prefs.textReplacements.toMutableList()
        val alert = IosAlert(this).title(if (index == null) "New Replacement" else "Edit Replacement")
        val phrase = alert.field("Phrase", index?.let { items[it].second }.orEmpty())
        val shortcut = alert.field("Shortcut", index?.let { items[it].first }.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        alert.action("Save", IosAlert.Style.PREFERRED) {
            val entry = shortcut.text.toString().trim() to phrase.text.toString().trim()
            if (entry.first.isNotEmpty() && entry.second.isNotEmpty()) {
                if (index == null) items.add(entry) else items[index] = entry
                prefs.textReplacements = items
            }
        }
        if (index != null) alert.action("Delete", IosAlert.Style.DESTRUCTIVE) {
            items.removeAt(index)
            prefs.textReplacements = items
        }
        alert.action("Cancel", IosAlert.Style.CANCEL).show()
    }

    companion object {
        private const val REQUEST_PHOTOS = 7
        private const val REQUEST_MIC = 8
        /** Set by the keyboard's dictation panel: ask for the microphone permission on arrival. */
        const val EXTRA_REQUEST_MIC = "requestMic"
        const val EXTRA_PAGE = "page"
    }
}
