package dev.leconnn.ioskeyboard

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.SystemClock
import dev.leconnn.ioskeyboard.text.ClipEntities
import dev.leconnn.ioskeyboard.text.ClipHistory
import dev.leconnn.ioskeyboard.text.FieldTraits
import dev.leconnn.ioskeyboard.text.SmartPunctuation
import dev.leconnn.ioskeyboard.text.WordComposer

class IosKeyboardService : InputMethodService(), KeyboardView.Listener {

    private lateinit var view: KeyboardView
    private lateinit var overlay: PopupOverlay
    private lateinit var sounds: KeySounds
    private lateinit var haptics: KeyHaptics
    private lateinit var prefs: Prefs
    private var lastSpaceTime = 0L
    private var traits = FieldTraits(null)
    /** Last smart-punctuation rewrite; an immediate backspace restores what was typed. */
    private var lastSmart: SmartUndo? = null
    private lateinit var composer: WordComposer
    /** [currentInputConnection] behind a cache of the text before the cursor (see CachingInputConnection). */
    private var cachedConnection: CachingInputConnection? = null
    private val ic: CachingInputConnection?
        get() {
            val c = currentInputConnection ?: return null
            cachedConnection?.let { if (it.target === c) return it }
            return CachingInputConnection(c).also { cachedConnection = it }
        }
    private lateinit var clipHistory: ClipHistory
    /** The chevron was tapped in an empty field: show the starting words instead of the buttons. */
    private var emptyFieldWords = false
    /** The latest copy, offered as a chip until used, typed over or [CHIP_MS] old. */
    /** [source]: the app the text was copied in, when known (for "paste from <App>"). */
    private class RecentCopy(val text: String, val at: Long, val source: String? = null) { var done = false }
    private var recentCopy: RecentCopy? = null
    /** What each chip in the bar pastes (the chips show shortened labels). */
    /** What tapping each chip in the bar does (paste its text or image). */
    private var chipActions: List<() -> Unit> = emptyList()

    /** The latest screenshot (Save Recent Screenshots), offered as a chip like a copy. */
    private class RecentShot(val uri: String, val at: Long) { var done = false; var thumb: android.graphics.Bitmap? = null }
    private var recentShot: RecentShot? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        // Copied just now: in the app that has the input focus (Android tells the keyboard which
        // app's window is focused even when it has no text field).
        readClipboard(source = currentInputEditorInfo?.packageName, newCopy = true)
        if (::view.isInitialized) view.refreshPaste()
    }
    private val chipExpired = Runnable { showSuggestions() }

    /** True while the app reports a non-empty selection. */
    private var hasSelection = false
    /** Ends the pause on suggestion refreshes once delete stops repeating. */
    private val deleteSettled = Runnable { composer.deferRefresh = false }

    private class SmartUndo(val insertedLength: Int, val original: String)
    /** Set by the user's own shift tap so auto-capitalisation doesn't immediately undo it. */
    private var manualShift = false
    // Kept as a field: SharedPreferences holds listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Emoji recents and tones change on every pick and don't affect the keyboard's settings.
        if (key != "emojiRecents" && key != "emojiTones") applyPrefs()
    }

    // Blur turns off in battery saver and on some devices; re-style when that changes.
    private val blurListener = java.util.function.Consumer<Boolean> { applyAppearance() }

    override fun onCreate() {
        super.onCreate()
        sounds = KeySounds(this)
        haptics = KeyHaptics(this)
        clipHistory = ClipHistory.get(this)
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
        prefs = Prefs.get(this)
        composer = WordComposer(this) { ic }
        composer.onSlotsChanged = { showSuggestions() }
        prefs.raw.registerOnSharedPreferenceChangeListener(prefsListener)
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(WindowManager::class.java).addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
        }
    }

    override fun onCreateInputView(): View {
        view = KeyboardView(this, this)
        // Popups go to their own window so this one can end at the keys (see PopupOverlay).
        view.headroomPt = 0f
        overlay = PopupOverlay(view)
        view.setOnApplyWindowInsetsListener { v, insets ->
            (v as KeyboardView).bottomInsetPx = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            insets
        }
        applyPrefs()
        return view
    }

    private fun applyPrefs() {
        sounds.enabled = prefs.sound
        sounds.volume = prefs.soundVolume / 100f
        clipHistory.retentionMs = prefs.clipboardRetention.ms
        if (!prefs.clipboardHistory) clipHistory.clearUnpinned()
        haptics.strength = prefs.vibration
        composer.setLanguages(prefs.keyboard, prefs.keyboards)
        if (!::view.isInitialized) return
        applyAppearance()
        view.spec = prefs.spec(password = traits.isPassword)
        view.characterPreview = prefs.characterPreview
        view.previewScale = prefs.previewSize.scale
        view.longPressMs = prefs.longPressDelay.ms
        view.capsLockEnabled = prefs.capsLock
        view.oneHanded = prefs.oneHanded
        view.emojiArt = prefs.emojiArt
        view.emojiStyle = prefs.emojiLayout
        view.clipboardStyle = prefs.clipboardLayout
        view.gifStyle = prefs.gifLayout
        view.slideToType = prefs.slideToType && traits.isText && !traits.isPassword
        if (!prefs.capsLock && view.shift == Glyphs.ShiftState.LOCKED) view.setShiftState(Glyphs.ShiftState.OFF)
        view.toolbarItems = if (prefs.toolbar) toolbarItems() else emptyList()
        view.barPt = if ((prefs.predictiveText && traits.suggestionsAllowed) || view.toolbarItems.isNotEmpty()) BAR_HEIGHT_PT else 0f
        composer.options = WordComposer.Options(
            enabled = (prefs.predictiveText || prefs.autoCorrection || prefs.checkSpelling) && traits.suggestionsAllowed,
            autocorrect = prefs.autoCorrection && traits.smartAllowed,
            checkSpelling = prefs.checkSpelling,
            curlyApostrophe = prefs.smartPunctuation && traits.smartAllowed,
            mathResults = prefs.mathResults,
            blockOffensive = prefs.blockOffensive,
            emojiSuggestions = prefs.emojiSuggestions,
            emptyFieldPredictions = !prefs.toolbar || emptyFieldWords,
            replacements = prefs.textReplacements.toMap(),
            inline = prefs.inlinePredictions && prefs.predictiveText,
            learn = traits.learningAllowed,
        )
        updateAutoShift()
        updateSuggestions()
    }

    /** Suggestion bar: word predictions and corrections from the composer, or a math result. */
    private fun updateSuggestions() {
        if (!::view.isInitialized || view.barPt == 0f) return
        composer.refresh() // results arrive through onSlotsChanged
    }

    private fun showSuggestions() {
        if (::view.isInitialized) updateStickers()
        if (!::view.isInitialized || view.barPt == 0f) return
        // The bar can be up for the toolbar alone; suggestions only show with Predictive Text on.
        val s = if (prefs.predictiveText && traits.suggestionsAllowed) {
            composer.displaySlots().map { it?.let { (text, bold) -> KeyboardView.Suggestion(text, text, bold) } }
        } else {
            listOf(null, null, null)
        }
        view.setSuggestions(s[0], s[1], s[2])
        view.setChips(copiedChips())
    }

    // ---- copied text: chips and clipboard history ----------------------------------------

    /**
     * Reads the clipboard after a copy (or when the keyboard opens) into the chip and history.
     * [source] is the app it was copied in, if known (not when the copy predates the keyboard).
     * [newCopy]: a copy just happened, so it counts even if it's the same text as last time.
     */
    private fun readClipboard(source: String? = null, newCopy: Boolean = false) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        // Password managers mark copied secrets as sensitive: never show or store them.
        if (clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        val text = runCatching { clip.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (text.isNullOrBlank() || (!newCopy && text == recentCopy?.text)) return
        // Age the chip from when the text was really copied (the clip's own timestamp), so
        // restarting the keyboard doesn't make an old copy look new. Accept either clock the
        // timestamp may use: wall-clock time, or time since boot.
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val ts = clip.description?.timestamp ?: 0L
        val copiedAt = when {
            ts in (wall - DAY_MS)..wall -> now - (wall - ts)
            ts in 1..now -> ts
            else -> now
        }
        recentCopy = RecentCopy(text, copiedAt, source?.takeIf { it != packageName })
        // Not kept from password or incognito fields.
        if (prefs.clipboardHistory && traits.learningAllowed) clipHistory.add(text)
        if (::view.isInitialized) view.refreshClipboard()
        showSuggestions()
    }

    /** The copied-text chips for the bar: the whole clip, then up to two details picked out of it. */
    /**
     * Chips for the newest copied text (plus details picked out of it) or the newest screenshot,
     * like Gboard's "recently copied text and images" in the suggestion bar. Each lasts [CHIP_MS]
     * after it was copied or taken, until used, or until typing starts.
     */
    private fun copiedChips(): List<Chip> {
        chipActions = emptyList()
        if (!prefs.showCopiedItems || composer.composing || !traits.suggestionsAllowed || traits.isPassword) return emptyList()
        val now = SystemClock.elapsedRealtime()
        val copy = recentCopy?.takeIf { !it.done && now - it.at < CHIP_MS }
        val shot = recentShot?.takeIf { !it.done && now - it.at < CHIP_MS && prefs.clipboardScreenshots && acceptsImages() }
        if (shot != null && (copy == null || shot.at >= copy.at)) {
            view.removeCallbacks(chipExpired)
            view.postDelayed(chipExpired, CHIP_MS - (now - shot.at))
            chipActions = listOf({ shot.done = true; pasteImage(android.net.Uri.parse(shot.uri)) })
            if (iosPasteStyle) return listOf(Chip("Screenshot", ChipIcon.NONE, shot.thumb, subtitle = "paste from Screenshots"))
            return listOf(Chip("Screenshot", ChipIcon.NONE, shot.thumb))
        }
        if (copy == null) return emptyList()
        view.removeCallbacks(chipExpired)
        view.postDelayed(chipExpired, CHIP_MS - (now - copy.at))
        if (iosPasteStyle) {
            // iOS 27: just the copied text, with where it came from.
            chipActions = listOf({ commitChipText(copy.text) })
            val from = copy.source?.let(::appLabel)
            return listOf(Chip(oneLine(copy.text), ChipIcon.NONE, subtitle = if (from != null) "paste from $from" else "paste"))
        }
        val details = ClipEntities.find(copy.text)
        val texts = listOf(copy.text) + details.map { it.text }
        chipActions = texts.map { t -> { commitChipText(t) } }
        // Labels show the text on one line with single spaces (line breaks, tabs and runs of
        // spaces collapse); tapping inserts the text exactly as copied.
        return listOf(Chip(oneLine(copy.text), ChipIcon.CLIPBOARD)) +
            details.map { Chip(oneLine(it.text), chipIcon(it.kind)) }
    }

    /** Settings > Clipboard > Copied Items Style: iOS 27's paste banner, or Gboard's chips. */
    private val iosPasteStyle get() = prefs.copiedStyle == KeyStyle.IOS

    /** An app's name ("Threads"), or null if it can't be looked up. */
    private fun appLabel(pkg: String): String? {
        val pm = packageManager
        // Through the app's launcher entry: visible to this app via the manifest's <queries>.
        val viaLauncher = runCatching {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0,
            ).firstOrNull()?.loadLabel(pm)?.toString()
        }.getOrNull()
        return (viaLauncher ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull())
            ?.takeIf { it.isNotBlank() }
    }

    private fun commitChipText(text: String) {
        ic?.commitText(text, 1)
        recentCopy?.done = true
    }

    /** True if the field takes pasted images (so a screenshot chip is worth showing). */
    private fun acceptsImages() =
        currentInputEditorInfo?.contentMimeTypes?.any { android.content.ClipDescription.compareMimeTypes("image/png", it) } == true

    private fun chipIcon(kind: ClipEntities.Kind) = when (kind) {
        ClipEntities.Kind.CODE -> ChipIcon.CODE
        ClipEntities.Kind.LINK -> ChipIcon.LINK
        ClipEntities.Kind.EMAIL -> ChipIcon.EMAIL
        ClipEntities.Kind.PHONE -> ChipIcon.PHONE
        ClipEntities.Kind.ADDRESS -> ChipIcon.ADDRESS
    }

    /** A chip label: one line, single spaces, and no longer than a bar could ever show. */
    private fun oneLine(s: String) = (if (s.length > 600) s.substring(0, 600) else s).replace(WHITESPACE_RUN, " ").trim()


    override fun onEmptyBarChevron() = setEmptyFieldWords(true)

    /** Starting words in an empty field (toolbar on) are shown until typing or a new field. */
    private fun setEmptyFieldWords(on: Boolean) {
        if (emptyFieldWords == on) return
        emptyFieldWords = on
        composer.options = composer.options.copy(emptyFieldPredictions = !prefs.toolbar || on)
        updateSuggestions()
    }

    override fun onChip(index: Int) {
        val action = chipActions.getOrNull(index) ?: return
        action()
        showSuggestions()
        updateAutoShift()
    }

    override fun clips(): List<ClipHistory.Clip> = clipHistory.items()

    override fun onClipPaste(clip: ClipHistory.Clip) {
        val image = clip.image
        if (image != null) pasteImage(android.net.Uri.parse(image)) else ic?.commitText(clip.text, 1)
    }

    /** Background work for screenshots (scanning the photo library, copying an image to paste). */
    private val imageWorker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /** Adds screenshots taken within the history's time to the clipboard (Save Recent Screenshots). */
    private fun scanScreenshots() {
        if (!prefs.clipboardScreenshots || !prefs.clipboardHistory || traits.isPassword) return
        if (!Screenshots.granted(this)) return
        val since = System.currentTimeMillis() - prefs.clipboardRetention.ms
        imageWorker.execute {
            var added = false
            val found = Screenshots.recent(this, since)
            for ((uri, at) in found) added = clipHistory.addImage(uri, at) || added
            if (added) view.post { view.refreshClipboard() }
            // The newest screenshot, if just taken, becomes the bar's chip (Gboard's "most
            // recently captured screenshot"), with its thumbnail.
            val (uri, wall) = found.firstOrNull() ?: return@execute
            val age = System.currentTimeMillis() - wall
            if (age >= CHIP_MS || recentShot?.uri == uri) return@execute
            val shot = RecentShot(uri, SystemClock.elapsedRealtime() - age.coerceAtLeast(0))
            shot.thumb = Screenshots.thumbnail(this, uri, (CHIP_THUMB_DP * resources.displayMetrics.density).toInt())
            view.post {
                recentShot = shot
                showSuggestions()
            }
        }
    }

    /** Watches the photo library while the keyboard is open, so a new screenshot shows up at once. */
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            view.removeCallbacks(rescan)
            view.postDelayed(rescan, MEDIA_SETTLE_MS)
        }
    }
    private val rescan = Runnable { scanScreenshots() }
    private var watchingMedia = false

    private fun watchScreenshots(on: Boolean) {
        val want = on && prefs.clipboardScreenshots && Screenshots.granted(this)
        if (want == watchingMedia) return
        watchingMedia = want
        if (want) {
            runCatching {
                contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
            }
        } else {
            runCatching { contentResolver.unregisterContentObserver(mediaObserver) }
        }
    }

    /**
     * Pastes a screenshot into the field, as apps receive images from Gboard: a copy is offered
     * through [ClipImageProvider] with a one-time read grant. Fields that don't take images get a
     * short message instead.
     */
    private fun pasteImage(source: android.net.Uri) {
        val info = currentInputEditorInfo ?: return
        val mime = runCatching { contentResolver.getType(source) }.getOrNull() ?: "image/png"
        val accepted = info.contentMimeTypes?.any { android.content.ClipDescription.compareMimeTypes(mime, it) } == true
        if (!accepted) {
            notice("Images can't be pasted here")
            return
        }
        imageWorker.execute {
            val shared = ClipImageProvider.share(this, source, mime)
            view.post {
                val ic = ic ?: return@post
                if (shared == null) {
                    notice("The screenshot is no longer available")
                    return@post
                }
                val content = android.view.inputmethod.InputContentInfo(shared, android.content.ClipDescription("Screenshot", arrayOf(mime)))
                ic.commitContent(content, android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
            }
        }
    }

    override fun onClipPinned(clip: ClipHistory.Clip, pinned: Boolean) = clipHistory.setPinned(clip.id, pinned)
    override fun onClipDelete(clip: ClipHistory.Clip) = clipHistory.delete(clip.id)
    override fun onClipClear() = clipHistory.clearUnpinned()

    // ---- toolbar and panels --------------------------------------------------------------

    /**
     * Toolbar buttons for this field, in the user's order, without hidden or unavailable ones.
     * Emoji and Text Editing are switched on and off by their own settings, so only the
     * clipboard button can be hidden.
     */
    private fun toolbarItems(): List<ToolbarItem> {
        if (!traits.isText) return emptyList()
        val hidden = prefs.toolbarHidden
        return prefs.toolbarOrder.filter { available(it) && !(it == ToolbarItem.CLIPBOARD && it in hidden) }
    }

    /** Emoji Button: in the toolbar instead of on the keyboard (needs the toolbar). */
    private val emojiInToolbar get() = prefs.toolbar && prefs.emojiInToolbar

    private fun available(item: ToolbarItem) = when (item) {
        ToolbarItem.EMOJI -> emojiInToolbar
        ToolbarItem.CLIPBOARD -> prefs.clipboardHistory && !traits.isPassword
        ToolbarItem.TEXT_EDITING -> prefs.textEditing && !traits.isPassword
        ToolbarItem.GIF -> BuildConfig.GIFS && prefs.gifs && !traits.isPassword
        ToolbarItem.VOICE -> prefs.voiceTyping && !traits.isPassword
    }

    override fun onToolbar(item: ToolbarItem) = openPanel(item)

    override fun onToolbarOrder(order: List<ToolbarItem>) {
        prefs.toolbarOrder = order + prefs.toolbarOrder.filter { it !in order }
    }

    private fun openPanel(item: ToolbarItem) {
        // Finish any word being typed before a panel takes over the keys.
        ic?.finishComposingText()
        composer.reset()
        if (item == ToolbarItem.CLIPBOARD) scanScreenshots()
        view.panel = when (item) {
            ToolbarItem.EMOJI -> KeyboardView.Panel.EMOJI
            ToolbarItem.CLIPBOARD -> KeyboardView.Panel.CLIPBOARD
            ToolbarItem.TEXT_EDITING -> KeyboardView.Panel.EDITING
            ToolbarItem.GIF -> KeyboardView.Panel.GIF
            ToolbarItem.VOICE -> KeyboardView.Panel.VOICE
        }
        // Dictation starts listening as soon as its panel opens, as on iOS.
        if (item == ToolbarItem.VOICE) startDictation()
    }

    override fun menuItems(): List<KeyMenuItem> = buildList {
        if (available(ToolbarItem.CLIPBOARD)) add(KeyMenuItem.CLIPBOARD)
        if (available(ToolbarItem.TEXT_EDITING)) add(KeyMenuItem.TEXT_EDITING)
        if (available(ToolbarItem.GIF)) add(KeyMenuItem.GIFS)
        if (available(ToolbarItem.VOICE)) add(KeyMenuItem.DICTATION)
        add(KeyMenuItem.SETTINGS)
        // With more than one keyboard language, they're listed here as in iOS's globe-key menu.
        val keyboards = prefs.keyboards
        if (keyboards.size > 1) {
            val current = prefs.keyboard
            keyboards.forEach { add(KeyMenuItem.Keyboard(it, it == current)) }
        }
        add(KeyMenuItem.SWITCH_KEYBOARD)
    }

    override fun canCopy() = !traits.isPassword && hasSelection

    override fun canPaste() =
        runCatching { getSystemService(ClipboardManager::class.java)?.hasPrimaryClip() == true }.getOrDefault(false)

    override fun onEditAction(action: EditingPanel.Action, selecting: Boolean) {
        val ic = ic ?: return
        val shift = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        fun key(code: Int, meta: Int = shift) {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
        when (action) {
            // Arrows stop at the ends of the text instead of jumping to another field.
            EditingPanel.Action.LEFT -> moveCursor(-1, 0, shift)
            EditingPanel.Action.RIGHT -> moveCursor(1, 0, shift)
            EditingPanel.Action.UP -> moveCursor(0, -1, shift)
            EditingPanel.Action.DOWN -> moveCursor(0, 1, shift)
            EditingPanel.Action.HOME -> key(KeyEvent.KEYCODE_MOVE_HOME)
            EditingPanel.Action.END -> key(KeyEvent.KEYCODE_MOVE_END)
            EditingPanel.Action.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            EditingPanel.Action.COPY -> ic.performContextMenuAction(android.R.id.copy)
            EditingPanel.Action.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            EditingPanel.Action.DELETE -> onDelete(0)
            EditingPanel.Action.SELECT -> Unit
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::view.isInitialized) {
            applyAppearance()
            view.barOnly = hardKeyboardAttached()
        }
    }

    // ---- physical keyboards ----

    /** True when Android reports a physical keyboard in use (e.g. Bluetooth on a phone). */
    private fun hardKeyboardAttached(): Boolean {
        val c = resources.configuration
        return c.keyboard == Configuration.KEYBOARD_QWERTY && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    }

    /** With a physical keyboard, still show the suggestion bar (Android would hide the keyboard). */
    override fun onEvaluateInputViewShown(): Boolean = super.onEvaluateInputViewShown() || hardKeyboardAttached()

    /** Key codes handled here on key down, so their key up isn't passed to the app either. */
    private val hardwareHandled = HashSet<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleHardwareKey(event)) {
            hardwareHandled.add(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareHandled.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Typing on a physical keyboard goes through the same path as the on-screen keys, so
     * predictions, autocorrection, smart punctuation and searches work. Shortcuts (Ctrl, Alt,
     * Meta), arrows and other keys go to the app as usual.
     */
    private fun handleHardwareKey(e: KeyEvent): Boolean {
        val device = e.device ?: return false
        if (device.isVirtual || device.keyboardType != android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        if (!::view.isInitialized || ic == null || !traits.isText) return false
        // The language key (and Ctrl+Space) switches between the enabled keyboard languages.
        if (e.keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH || (e.keyCode == KeyEvent.KEYCODE_SPACE && e.isCtrlPressed)) {
            return nextKeyboard()
        }
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed || e.isFunctionPressed) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> { onDelete(e.repeatCount); return true }
            KeyEvent.KEYCODE_SPACE -> { onSpace(); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Shift+Enter is a plain new line in many apps; leave it to them.
                if (e.isShiftPressed) return false
                onReturn()
                return true
            }
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                // The app moves the cursor or focus: finish the word being typed first.
                ic?.finishComposingText()
                composer.reset()
                return false
            }
        }
        val ch = e.getUnicodeChar(e.metaState)
        if (ch == 0 || ch and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) return false
        onText(String(Character.toChars(ch)))
        return true
    }

    private val blurAvailable
        get() = Build.VERSION.SDK_INT >= 31 && getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled

    /**
     * Classic: the view paints the flat panel. Glass: the IME window background is the tinted panel
     * with rounded top corners, and the screen behind it is blurred (the blur follows the
     * background's shape). Without blur the tint is more opaque.
     */
    private fun applyAppearance() {
        if (!::view.isInitialized) return
        val glass = prefs.appearance == Prefs.Appearance.GLASS
        val blur = glass && blurAvailable
        val theme = KeyboardTheme.forContext(this, glass, blur)
        view.theme = theme
        view.paintsPanel = !glass
        val w = window.window ?: return
        if (glass) {
            val r = cornerPx().toFloat()
            w.setBackgroundDrawable(GradientDrawable().apply {
                setColor(theme.panelTint)
                // Rounded top corners, like iOS 26. As on iOS the app ends at the keyboard's top
                // edge and the corners show whatever the app draws behind the keyboard (its
                // window background in apps that resize). Extending the app under the corners
                // would also put its bottom bar under the keyboard (Android insets are a full-width
                // strip), so it isn't done.
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            })
        } else {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(if (blur) blurRadiusPx() else 0)
    }

    /** Liquid Glass top-corner radius in pixels (0 for Classic, whose top edge is square like iOS's). */
    private fun cornerPx(): Int =
        if (prefs.appearance == Prefs.Appearance.GLASS) (CORNER_PT * resources.displayMetrics.widthPixels / Geometry.WIDTH).toInt() else 0

    private fun blurRadiusPx() = (BLUR_RADIUS_DP * resources.displayMetrics.density).toInt()

    override fun onWindowShown() {
        super.onWindowShown()
        overlay.show()
        // Once the keyboard is up and idle, load the emoji panel's data so its first open is instant.
        view.postDelayed({ view.prewarmEmoji() }, EMOJI_PREWARM_DELAY_MS)
    }

    override fun onWindowHidden() {
        overlay.dismiss()
        super.onWindowHidden()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        traits = FieldTraits(info)
        lastSmart = null
        ic?.let {
            it.invalidate()
            it.onSelectionUpdate(info.initialSelStart, info.initialSelEnd)
        }
        hasSelection = info.initialSelStart != info.initialSelEnd
        readClipboard()
        composer.reset()
        applyAppearance()
        scanScreenshots()
        watchScreenshots(true)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        view.setPlane(if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) Plane.NUMBERS else Plane.LETTERS)
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val (label, blue) = if (noEnterAction) "return" to false else when (action) {
            EditorInfo.IME_ACTION_GO -> "go" to true
            EditorInfo.IME_ACTION_SEARCH -> "search" to true
            EditorInfo.IME_ACTION_SEND -> "send" to true
            EditorInfo.IME_ACTION_DONE -> "done" to true
            EditorInfo.IME_ACTION_NEXT -> "next" to false
            else -> "return" to false
        }
        view.returnLabel = label
        view.returnHighlighted = blue
        // Like iOS, caps lock and a manual shift end when the keyboard opens in a new field, and
        // when the app empties the field it's in: replacing its text restarts input, which is
        // how most chat apps clear the box after Send.
        if (!restarting || (SystemClock.uptimeMillis() - lastDeleteAt > APP_EDIT_GAP_MS && fieldEmpty())) resetShift()
        emptyFieldWords = false
        view.barOnly = hardKeyboardAttached()
        applyPrefs()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Caps lock ends when the keyboard is dismissed, so it doesn't reappear next time.
        if (::view.isInitialized) {
            resetShift()
            view.closeMenu()
            endSearch()
            watchScreenshots(false)
            stopDictation()
        }
        super.onFinishInputView(finishingInput)
    }

    private fun resetShift() {
        manualShift = false
        view.setShiftState(Glyphs.ShiftState.OFF)
    }

    /**
     * Text or cursor changed, possibly by the app (e.g. a chat box cleared after sending) or a tap
     * elsewhere in the field: re-evaluate auto-capitalisation like iOS does.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        ic?.onSelectionUpdate(newSelStart, newSelEnd)
        hasSelection = newSelStart != newSelEnd
        // The editing panel greys Copy without a selection.
        if (::view.isInitialized) view.invalidate()
        composer.onSelectionChanged(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::view.isInitialized) {
            // The app emptied the field (a chat app's Send button clears it without restarting
            // input): like sending with the return key, that ends caps lock and a manual shift.
            // Emptying it with the delete key doesn't. Only deleting counts against it: a Send
            // tapped right after typing the last letter must still end caps lock.
            if (newSelStart == 0 && newSelEnd == 0 && SystemClock.uptimeMillis() - lastDeleteAt > APP_EDIT_GAP_MS &&
                fieldEmpty()
            ) resetShift()
            updateAutoShift()
            updateSuggestions()
        }
    }

    // ---- KeyboardView.Listener -------------------------------------------------------------

    /** Where the letter about to be typed was tapped (see [onCharTap]). */
    private var pendingTap: Pair<Float, Float>? = null

    override fun onCharTap(dx: Float, dy: Float) {
        pendingTap = dx to dy
    }

    // ---- GIF search: while it is typed, keys go into the search instead of the app ----

    /** The search being typed (GIF or emoji), or null; while it is, keys type into it. */
    private var searchQuery: StringBuilder? = null
    private var savedReturn: Pair<String, Boolean>? = null

    override fun onGifSearch() = startSearch(KeyboardView.SearchKind.GIF)

    /** iOS's Search Emoji: letters type the search, matches show in a row above the keys. */
    override fun onEmojiSearch() = startSearch(KeyboardView.SearchKind.EMOJI)

    private fun startSearch(kind: KeyboardView.SearchKind) {
        searchQuery = StringBuilder()
        savedReturn = view.returnLabel to view.returnHighlighted
        view.returnLabel = if (kind == KeyboardView.SearchKind.GIF) "search" else "done"
        view.returnHighlighted = true
        view.panel = KeyboardView.Panel.KEYS
        view.setPlane(Plane.LETTERS)
        view.searchKind = kind
        view.searchText = ""
        manualShift = false
        view.setShiftState(Glyphs.ShiftState.OFF)
        if (kind == KeyboardView.SearchKind.EMOJI) updateEmojiResults()
    }

    override fun onSearchCancel() {
        val kind = view.searchKind
        endSearch()
        backToPanel(kind)
    }

    private fun backToPanel(kind: KeyboardView.SearchKind) {
        if (kind == KeyboardView.SearchKind.GIF) view.openGifs(null) else view.emojiMode = true
    }

    /** Leaves search typing: the bar and return key go back to normal. */
    private fun endSearch() {
        if (searchQuery == null) return
        searchQuery = null
        view.searchText = null
        view.searchResults = emptyList()
        savedReturn?.let { (label, blue) -> view.returnLabel = label; view.returnHighlighted = blue }
        savedReturn = null
    }

    /** A key typed into the search; returns true if it was (so the app doesn't get it). */
    private fun searchKey(text: String?, delete: Boolean = false, submit: Boolean = false): Boolean {
        val q = searchQuery ?: return false
        val kind = view.searchKind
        when {
            submit -> {
                val query = q.toString().trim()
                endSearch()
                if (kind == KeyboardView.SearchKind.GIF) view.openGifs(query.ifEmpty { null }) else backToPanel(kind)
                return true
            }
            delete -> if (q.isNotEmpty()) q.deleteCharAt(q.length - 1)
            text != null -> q.append(text)
        }
        view.searchText = q.toString()
        if (kind == KeyboardView.SearchKind.EMOJI) updateEmojiResults()
        // A one-shot shift ends after one letter, as when typing.
        if (view.shift == Glyphs.ShiftState.ON) view.setShiftState(Glyphs.ShiftState.OFF)
        return true
    }

    /**
     * The emoji search index (assets/emoji_search.tsv, plus emoji_search_tl.tsv with the Filipino
     * keyboard on), loaded on first use; [emojiIndexFor] is the keyboard languages it covers.
     */
    @Volatile private var emojiIndex: dev.leconnn.ioskeyboard.text.EmojiSearch? = null
    @Volatile private var emojiIndexFor: List<dev.leconnn.ioskeyboard.text.Language> = emptyList()

    private fun updateEmojiResults() {
        val q = searchQuery?.toString()?.trim() ?: return
        if (q.isEmpty()) { view.searchResults = prefs.emojiRecents; return }
        // English names always work (iOS searches in English too); the current language's come first.
        val languages = (listOf(prefs.keyboard) + prefs.keyboards + dev.leconnn.ioskeyboard.text.Language.ENGLISH).distinct()
        val index = emojiIndex?.takeIf { emojiIndexFor == languages }
        if (index == null) {
            imageWorker.execute {
                emojiIndex = dev.leconnn.ioskeyboard.text.EmojiSearch.combine(languages.map { l ->
                    assets.open(l.emojiSearchAsset).bufferedReader(Charsets.UTF_8)
                        .useLines { dev.leconnn.ioskeyboard.text.EmojiSearch.parse(it) }
                })
                emojiIndexFor = languages
                view.post { updateEmojiResults() }
            }
            return
        }
        // The skin tone last picked for an emoji applies to its search result too.
        view.searchResults = index.search(q).map { prefs.emojiTone(it)?.takeIf { t -> t.isNotEmpty() } ?: it }
    }

    override fun onEmojiSearchPick(emoji: String) {
        ic?.commitText(emoji, 1)
        prefs.addEmojiRecent(emoji)
    }

    override fun onGifPicked(gif: Giphy.Gif) = sendGif(gif)

    /** A short message shown inside the keyboard (iOS has no toasts). */
    private fun notice(text: String) { if (::view.isInitialized) view.showNotice(text) }

    // ---- Emoji Kitchen: mash-up stickers for the emoji just typed ----

    @Volatile private var kitchen: dev.leconnn.ioskeyboard.text.EmojiKitchen? = null
    private var kitchenLoading = false
    /** Sticker pictures shown in the bar, by address (a few dozen at most). */
    private val stickerImages = object : android.util.LruCache<String, android.graphics.Bitmap>(STICKER_CACHE) {}
    private val stickerLoading = HashSet<String>()
    private val stickerWorker by lazy { java.util.concurrent.Executors.newFixedThreadPool(3) }

    /**
     * After an emoji (the last thing before the cursor), the bar offers its Emoji Kitchen stickers:
     * mixed with the emoji before it first, then with others. Only in fields that take images.
     */
    private fun updateStickers() {
        val k = kitchen
        if (!prefs.emojiKitchen || traits.isPassword || composer.composing || searchQuery != null || !acceptsImages()) {
            view.stickers = emptyList()
            return
        }
        if (k == null) {
            if (!kitchenLoading) {
                kitchenLoading = true
                imageWorker.execute {
                    kitchen = runCatching { assets.open("emoji_kitchen.dat").use { dev.leconnn.ioskeyboard.text.EmojiKitchen.read(it) } }.getOrNull()
                    view.post { updateStickers() }
                }
            }
            return
        }
        val before = ic?.getTextBeforeCursor(24, 0)?.toString().orEmpty()
        val it = android.icu.text.BreakIterator.getCharacterInstance().apply { setText(before) }
        val end = before.length
        val start = it.preceding(end).takeIf { p -> p != android.icu.text.BreakIterator.DONE } ?: -1
        val last = if (start >= 0) before.substring(start, end) else ""
        if (last.isEmpty() || !k.has(last)) { view.stickers = emptyList(); return }
        val prevStart = it.preceding(start).takeIf { p -> p != android.icu.text.BreakIterator.DONE } ?: -1
        val prev = if (prevStart >= 0) before.substring(prevStart, start) else null
        val list = k.stickers(last, prev?.takeIf { p -> k.has(p) })
        view.stickers = list.map { s -> KeyboardView.StickerChip(s, stickerImages.get(s.url)) }
        for (s in list) loadSticker(s.url)
    }

    private fun loadSticker(url: String) {
        if (stickerImages.get(url) != null || !stickerLoading.add(url)) return
        stickerWorker.execute {
            val bytes = stickerBytes(url)
            val px = (STICKER_THUMB_DP * resources.displayMetrics.density).toInt()
            val bmp = bytes?.let { b ->
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, opts)
                var sample = 1
                while (opts.outWidth / (sample * 2) >= px) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
            view.post {
                stickerLoading.remove(url)
                if (bmp == null) return@post
                stickerImages.put(url, bmp)
                view.stickers = view.stickers.map { c -> if (c.sticker.url == url) c.copy(image = bmp) else c }
            }
        }
    }

    /** The sticker's PNG: from the disk cache, or downloaded (and cached). Off the main thread. */
    private fun stickerBytes(url: String): ByteArray? {
        val dir = java.io.File(cacheDir, "kitchen").apply { mkdirs() }
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = java.io.File(dir, "$name.png")
        if (f.isFile) return runCatching { f.readBytes() }.getOrNull()
        val bytes = Giphy.download(url) ?: return null
        runCatching { f.writeBytes(bytes) }
        // Keep the newest few hundred pictures.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(STICKER_DISK_FILES)?.forEach { it.delete() }
        return bytes
    }

    override fun onSticker(sticker: dev.leconnn.ioskeyboard.text.EmojiKitchen.Sticker) {
        if (!acceptsImages()) {
            notice("Stickers can't be sent here")
            return
        }
        imageWorker.execute {
            val shared = stickerBytes(sticker.url)?.let { ClipImageProvider.shareBytes(this, it, "image/png", "sticker") }
            view.post {
                val ic = ic ?: return@post
                if (shared == null) {
                    notice("Couldn't load the sticker")
                    return@post
                }
                val content = android.view.inputmethod.InputContentInfo(shared,
                    android.content.ClipDescription("${sticker.left}${sticker.right} sticker", arrayOf("image/png")))
                ic.commitContent(content, android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
            }
        }
    }

    // ---- dictation: the phone's speech recogniser, words going into the field as they come ----

    private var recognizer: android.speech.SpeechRecognizer? = null
    private var dictating = false
    /** A space put before dictated words when the cursor follows a word. */
    private var dictationPrefix = ""
    /** Silences in a row; listening stops after a few, like iOS when nothing is said. */
    private var quietRestarts = 0

    override fun onVoiceToggle() = if (dictating) stopDictation() else startDictation()

    override fun onVoiceMessageAction() {
        if (view.voiceState == VoicePanel.State.NO_PERMISSION) {
            // A keyboard can't ask for a permission itself; Settings asks for it.
            startActivity(android.content.Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_REQUEST_MIC, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            startDictation()
        }
    }

    override fun onVoiceClosed() = stopDictation()

    private fun startDictation() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            view.voiceState = VoicePanel.State.NO_PERMISSION
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            view.voiceState = VoicePanel.State.UNAVAILABLE
            return
        }
        val r = recognizer ?: android.speech.SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(dictationListener)
            recognizer = it
        }
        ic?.finishComposingText()
        composer.reset()
        quietRestarts = 0
        dictating = true
        view.voiceLevel = 0f
        view.voiceState = VoicePanel.State.LISTENING
        listen(r)
    }

    private fun listen(r: android.speech.SpeechRecognizer) {
        val before = ic?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        dictationPrefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        r.startListening(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // The keyboard's language (Filipino dictation for the Filipino keyboard).
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, prefs.keyboard.speechTag)
        })
    }

    private fun stopDictation() {
        if (!dictating) return
        dictating = false
        runCatching { recognizer?.cancel() }
        ic?.finishComposingText()
        if (::view.isInitialized) {
            view.voiceLevel = 0f
            if (view.voiceState == VoicePanel.State.LISTENING) view.voiceState = VoicePanel.State.IDLE
        }
    }

    /** Capitalises dictated text at the start of a sentence (most recognisers already do). */
    private fun dictated(text: String): String {
        val before = ic?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val sentenceStart = before.isBlank() || before.trimEnd(' ').lastOrNull() in setOf('.', '!', '?', '\n')
        return dictationPrefix + if (sentenceStart && prefs.autoCapitalization) text.replaceFirstChar { it.uppercaseChar() } else text
    }

    private val dictationListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() { quietRestarts = 0 }
        override fun onRmsChanged(rmsdB: Float) { view.voiceLevel = (rmsdB + 2f) / 12f }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (!dictating) return
            val text = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) ic?.setComposingText(dictated(text), 1)
        }

        override fun onResults(results: android.os.Bundle?) {
            val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) ic?.commitText(dictated(text), 1) else ic?.finishComposingText()
            // Keep listening until stopped, as iOS dictation does.
            if (dictating) recognizer?.let(::listen)
        }

        override fun onError(error: Int) {
            if (!dictating) return
            ic?.finishComposingText()
            when (error) {
                android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (++quietRestarts < MAX_QUIET) { recognizer?.let(::listen); return }
                    dictating = false
                    view.voiceState = VoicePanel.State.IDLE
                }
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    dictating = false
                    view.voiceState = VoicePanel.State.NO_PERMISSION
                }
                else -> {
                    dictating = false
                    view.voiceError = when (error) {
                        android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "No connection. Tap to try again"
                        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Tap to try again"
                        else -> "Couldn't hear you. Tap to try again"
                    }
                    view.voiceState = VoicePanel.State.ERROR
                }
            }
            view.voiceLevel = 0f
        }
    }

    /**
     * Sends a GIF the way Gboard does: downloaded, then offered to the app as an image/gif through
     * [ClipImageProvider]. Fields that don't take GIFs get a short message.
     */
    private fun sendGif(gif: Giphy.Gif) {
        val info = currentInputEditorInfo ?: return
        val accepted = info.contentMimeTypes?.any { android.content.ClipDescription.compareMimeTypes("image/gif", it) } == true
        if (!accepted) {
            notice("GIFs can't be sent here")
            return
        }
        imageWorker.execute {
            val bytes = Giphy.download(gif.sendUrl)
            val shared = bytes?.let { ClipImageProvider.shareBytes(this, it, "image/gif", "gif") }
            view.post {
                val ic = ic ?: return@post
                if (shared == null) {
                    notice("Couldn't send the GIF")
                    return@post
                }
                val content = android.view.inputmethod.InputContentInfo(shared,
                    android.content.ClipDescription(gif.title.ifEmpty { "GIF" }, arrayOf("image/gif")))
                ic.commitContent(content, android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
            }
        }
    }

    override fun onText(text: String, literal: Boolean) {
        if (searchKey(text)) { pendingTap = null; return }
        val ic = ic ?: return
        val tap = pendingTap
        pendingTap = null
        lastSmart = null
        // Typing ends "starting words" mode, so an emptied field shows the buttons again.
        setEmptyFieldWords(false)
        if (composer.onChar(text, tap)) {
            // Starting a word dismisses the copied-text chip.
            recentCopy?.done = true
            afterCharacter(text)
            return
        }
        composer.finishWord(text)
        val edit = if (!literal && prefs.smartPunctuation && traits.smartAllowed) {
            SmartPunctuation.transform(ic.getTextBeforeCursor(2, 0) ?: "", text)
        } else null
        if (edit != null) {
            val replaced = ic.getTextBeforeCursor(edit.deleteBefore, 0)?.toString().orEmpty()
            if (edit.deleteBefore > 0) ic.deleteSurroundingText(edit.deleteBefore, 0)
            ic.commitText(edit.insert, 1)
            lastSmart = SmartUndo(edit.insert.length, replaced + text)
        } else {
            ic.commitText(text, 1)
        }
        composer.afterSeparator(edit?.insert ?: text)
        afterCharacter(text)
    }

    private fun afterCharacter(text: String) {
        // A one-shot shift (manual or automatic) ends after one character.
        if (view.shift == Glyphs.ShiftState.ON) manualShift = false
        // An apostrophe on the 123 plane returns to letters (contractions like "don't").
        if (view.plane != Plane.LETTERS && text == "'") view.setPlane(Plane.LETTERS)
        updateAutoShift()
    }

    override fun onDelete(repeat: Int) {
        if (searchKey(null, delete = true)) return
        lastDeleteAt = SystemClock.uptimeMillis()
        val ic = ic ?: return
        val smart = lastSmart
        lastSmart = null
        if (repeat == 0 && smart != null) {
            // Backspace right after a smart rewrite brings back exactly what was typed.
            ic.deleteSurroundingText(smart.insertedLength, 0)
            ic.commitText(smart.original, 1)
            return
        }
        // While delete repeats, suggestions refresh once it settles rather than on every character.
        if (repeat > 0) {
            composer.deferRefresh = true
            view.removeCallbacks(deleteSettled)
            view.postDelayed(deleteSettled, DELETE_SETTLE_MS)
        }
        if (composer.onDelete(repeat, prefs.deleteSlideByWord)) {
            updateAutoShift()
            return
        }
        val before = ic.getTextBeforeCursor(CachingInputConnection.WINDOW, 0)?.toString()
        when {
            hasSelection -> ic.commitText("", 1)
            // Apps that report no text (terminals, games) get a real key press instead.
            before.isNullOrEmpty() -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            // After a long hold iOS deletes whole words.
            repeat >= WORD_DELETE_AFTER -> {
                val trimmed = before.trimEnd()
                val wordStart = trimmed.lastIndexOfAny(charArrayOf(' ', '\n')) + 1
                ic.deleteSurroundingText((before.length - wordStart).coerceAtLeast(1), 0)
            }
            // One whole character: an emoji, flag or accented letter can be several chars long.
            else -> ic.deleteSurroundingText(lastGraphemeLength(before), 0)
        }
        updateAutoShift()
    }

    private fun lastGraphemeLength(text: String): Int {
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        val start = it.preceding(text.length)
        return if (start == android.icu.text.BreakIterator.DONE) 1 else (text.length - start).coerceAtLeast(1)
    }

    override fun onSpace() {
        if (searchKey(" ")) return
        val ic = ic ?: return
        lastSmart = null
        composer.finishWord(" ")
        val now = System.currentTimeMillis()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        // Double space after a word becomes ". ", like iOS's "." shortcut.
        if (prefs.periodShortcut && now - lastSpaceTime < DOUBLE_SPACE_MS &&
            before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()
        ) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            lastSpaceTime = 0
        } else {
            ic.commitText(" ", 1)
            composer.afterSeparator(" ")
            lastSpaceTime = now
        }
        if (view.plane != Plane.LETTERS) view.setPlane(Plane.LETTERS)
        updateAutoShift()
    }

    override fun onReturn() {
        if (searchKey(null, submit = true)) return
        val ic = ic ?: return
        lastSmart = null
        composer.finishWord("\n")
        val info = currentInputEditorInfo
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(action)
            // Sending (or go / search / done) ends caps lock; chat apps often clear the field
            // without restarting input. A plain newline keeps it, as on iOS.
            resetShift()
        } else {
            ic.commitText("\n", 1)
        }
        updateAutoShift()
    }

    override fun onSwipe(points: List<Pair<Float, Float>>) {
        if (searchQuery != null) return
        lastSmart = null
        val capitals = when (view.shift) {
            Glyphs.ShiftState.LOCKED -> 2
            Glyphs.ShiftState.ON -> 1
            Glyphs.ShiftState.OFF -> 0
        }
        composer.onSwipe(points, capitals) { inserted ->
            if (inserted && ::view.isInitialized) {
                if (view.shift == Glyphs.ShiftState.ON) manualShift = false
                updateAutoShift()
            }
        }
    }

    override fun onSuggestion(index: Int) {
        lastSmart = null
        if (composer.onSuggestion(index)) updateAutoShift()
    }

    override fun onMenu(item: KeyMenuItem) {
        when (item) {
            KeyMenuItem.SETTINGS -> startActivity(
                android.content.Intent(this, SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            KeyMenuItem.SWITCH_KEYBOARD -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            KeyMenuItem.CLIPBOARD -> openPanel(ToolbarItem.CLIPBOARD)
            KeyMenuItem.TEXT_EDITING -> openPanel(ToolbarItem.TEXT_EDITING)
            KeyMenuItem.GIFS -> openPanel(ToolbarItem.GIF)
            KeyMenuItem.DICTATION -> openPanel(ToolbarItem.VOICE)
            is KeyMenuItem.Keyboard -> switchKeyboard(item.language)
        }
    }

    /** Switches the keyboard language (its words, suggestions and dictation); the space bar shows its name. */
    private fun switchKeyboard(language: dev.leconnn.ioskeyboard.text.Language) {
        if (language == prefs.keyboard) return
        // The settings listener applies it (word list, emoji names); dictation uses it next time.
        prefs.keyboard = language
        if (::view.isInitialized) view.showKeyboardName(language.spaceLabel)
    }

    /** The next enabled keyboard language (hardware language-switch key). */
    private fun nextKeyboard(): Boolean {
        val all = prefs.keyboards
        if (all.size < 2) return false
        switchKeyboard(all[(all.indexOf(prefs.keyboard) + 1) % all.size])
        return true
    }

    override fun onCursorMove(dx: Int, dy: Int) = moveCursor(dx, dy)

    /**
     * Moves the cursor with arrow keys ([meta]: shift extends the selection), but never past the
     * ends of the text: an arrow key there moves focus to the next field instead. Vertical moves
     * only happen in multi-line fields and only towards another line.
     */
    private fun moveCursor(dx: Int, dy: Int, meta: Int = 0) {
        val ic = ic ?: return
        fun press(code: Int, times: Int) = repeat(times) {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
        if (dx != 0) {
            val n = kotlin.math.abs(dx)
            val probe = (n * 4).coerceAtMost(CURSOR_PROBE)
            val text = (if (dx > 0) ic.getTextAfterCursor(probe, 0) else ic.getTextBeforeCursor(probe, 0))?.toString()
            // Apps that report no text (terminals) get the keys as asked.
            val steps = if (text == null) n else minOf(n, graphemes(text, fromStart = dx > 0, limit = n))
            press(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, steps)
        }
        if (dy != 0) {
            val multiLine = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            if (!multiLine) return
            val side = if (dy > 0) ic.getTextAfterCursor(CURSOR_PROBE * 8, 0) else ic.getTextBeforeCursor(CURSOR_PROBE * 8, 0)
            val lines = side?.count { it == '\n' } ?: kotlin.math.abs(dy)
            press(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, minOf(kotlin.math.abs(dy), lines))
        }
    }

    /** Whole characters in [text] (up to [limit]), counted from its start or its end. */
    private fun graphemes(text: String, fromStart: Boolean, limit: Int): Int {
        if (text.isEmpty()) return 0
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        var count = 0
        var pos = if (fromStart) it.first() else it.last()
        while (count < limit) {
            pos = if (fromStart) it.next() else it.previous()
            if (pos == android.icu.text.BreakIterator.DONE) break
            count++
        }
        return count
    }

    /** When the delete key last removed text (an emptied field right after that wasn't the app). */
    private var lastDeleteAt = 0L

    /** True if the field has no text at all (asked of the app, not the cache it may have just changed). */
    private fun fieldEmpty(): Boolean {
        val raw = currentInputConnection ?: return false
        return raw.getTextBeforeCursor(1, 0).isNullOrEmpty() && raw.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    override fun onFeedback(kind: KeyKind) {
        sounds.play(kind)
        if (prefs.haptic) haptics.tap()
    }

    override fun onShiftChanged(state: Glyphs.ShiftState) {
        manualShift = state != Glyphs.ShiftState.OFF
    }

    override fun onOneHandedOff() {
        prefs.oneHanded = Prefs.OneHanded.OFF
    }

    /** Capitalise at the start of a field or sentence unless caps lock or a manual shift is on. */
    private fun updateAutoShift() {
        if (view.shift == Glyphs.ShiftState.LOCKED || manualShift) return
        val info = currentInputEditorInfo ?: return
        val caps = prefs.autoCapitalization && info.inputType != InputType.TYPE_NULL && capsMode(info.inputType)
        view.setShiftState(if (caps) Glyphs.ShiftState.ON else Glyphs.ShiftState.OFF)
    }

    /**
     * Android's caps rule, applied to the cached text before the cursor instead of asking the app
     * (one less blocking call per key). Curly quotes from Smart Punctuation are straightened first,
     * since the rule skips straight quotes but not curly ones.
     */
    private fun capsMode(inputType: Int): Boolean {
        val modes = inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        if (modes == 0) return false
        val ic = ic ?: return false
        val before = ic.getTextBeforeCursor(CachingInputConnection.WINDOW, 0)
            ?: return ic.getCursorCapsMode(inputType) != 0
        var text = before.toString().replace('“', '"').replace('‘', '\'')
        // A full window may not reach the field's start; a leading letter stops the rule there.
        if (text.length >= CachingInputConnection.WINDOW) text = "x$text"
        return TextUtils.getCapsMode(text, text.length, modes) != 0
    }

    override fun onDestroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        prefs.raw.unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        if (Build.VERSION.SDK_INT >= 31) getSystemService(WindowManager::class.java).removeCrossWindowBlurEnabledListener(blurListener)
        if (::overlay.isInitialized) overlay.dismiss()
        sounds.release()
        super.onDestroy()
    }

    private companion object {
        const val DOUBLE_SPACE_MS = 600L
        /** Repeats before holding delete switches to whole words (about 1.2 s at 60 ms). */
        const val WORD_DELETE_AFTER = 20
        const val DELETE_SETTLE_MS = 150L
        const val EMOJI_PREWARM_DELAY_MS = 1500L
        /** How long a copy is offered as a chip (Gboard's own value isn't in its resources). */
        /** Size of a screenshot chip's thumbnail. */
        const val CHIP_THUMB_DP = 64
        /** Wait after a photo-library change before looking (a screenshot is saved in steps). */
        const val MEDIA_SETTLE_MS = 600L
        /** Dictation stops after this many silences in a row. */
        const val MAX_QUIET = 3
        /** Emoji Kitchen: pictures kept in memory and on disk, and their size in the bar. */
        const val STICKER_CACHE = 48
        const val STICKER_DISK_FILES = 300
        const val STICKER_THUMB_DP = 72
        const val CHIP_MS = 60_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val BLUR_RADIUS_DP = 24f
        /** An emptied field this long after the last delete was emptied by the app, not the keyboard. */
        private const val APP_EDIT_GAP_MS = 400L
        /** Liquid Glass keyboard top-corner radius, in iOS points. */
        const val CORNER_PT = 20f
        /** Characters read on either side of the cursor to keep arrow keys inside the text. */
        const val CURSOR_PROBE = 64
        private val WHITESPACE_RUN = Regex("""\s+""")
        const val BAR_HEIGHT_PT = 44f
    }
}
