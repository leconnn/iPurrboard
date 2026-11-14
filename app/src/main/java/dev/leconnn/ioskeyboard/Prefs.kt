package dev.leconnn.ioskeyboard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * All keyboard settings. Keys match the iOS KeyboardSettings ids where one exists, defaults
 * match iOS 27.2. Stored in device-protected storage so the keyboard works before first unlock.
 */
class Prefs private constructor(val raw: SharedPreferences) {
    // A new install starts exactly where Reset to Defaults ([resetToIos]) goes: iOS's keyboard
    // settings with the extras (clipboard history, chips, GIFs, Emoji Kitchen) off.

    enum class OneHanded { OFF, LEFT, RIGHT }

    var autoCapitalization by bool("KeyboardAutocapitalization", true)
    var autoCorrection by bool("KeyboardAutocorrection", true)
    var predictiveText by bool("KeyboardPrediction", true)
    var inlinePredictions by bool("KeyboardInlineCompletion", true)
    var mathResults by bool("KeyboardMathExpressionCompletion", true)
    var checkSpelling by bool("KeyboardCheckSpelling", true)
    var capsLock by bool("KeyboardCapsLock", true)
    var characterPreview by bool("KeyboardAllowPaddle", true)
    var periodShortcut by bool("KeyboardPeriodShortcut", true)
    var smartPunctuation by bool("SmartTyping", true)
    var slideToType by bool("KeyboardContinuousPathEnabled", true)
    var deleteSlideByWord by bool("KeyboardContinuousPathDeleteWholeWord", true)
    var sound by bool("keyboardSound", true)
    var haptic by bool("keyboardHaptic", false)
    /** Offer a matching emoji in the suggestion bar ("pizza" -> 🍕), like iOS. */
    var emojiSuggestions by bool("emojiSuggestions", true)
    /** Emoji Kitchen: mash-up stickers for the emoji just typed (Google's artwork, loaded online). */
    var emojiKitchen by bool("emojiKitchen", false)
    /** Offer recently copied text (and details picked out of it) as chips in the bar. */
    var showCopiedItems by bool("showCopiedItems", true)
    /** Keep a history of copied text for the clipboard panel. */
    var clipboardHistory by bool("clipboardHistory", false)
    /** The text-editing panel (arrows, select, copy / paste) in the emoji-key menu and toolbar. */
    var textEditing by bool("textEditing", false)

    /** How long unpinned clipboard items are kept. */
    enum class Retention(val ms: Long, val label: String) {
        HOUR(60 * 60 * 1000L, "1 Hour"), DAY(24 * 60 * 60 * 1000L, "1 Day"), WEEK(7 * 24 * 60 * 60 * 1000L, "1 Week"),
    }

    /** Voice typing: the mic in the toolbar and Dictation in the emoji-key menu (Settings > Toolbar). */
    var voiceTyping by bool("voiceTyping", true)

    /** GIFs: the GIF panel in the toolbar and the emoji-key menu (Settings > Toolbar). */
    var gifs by bool("gifs", false)

    /** Save Recent Screenshots: screenshots join the clipboard history (needs photo access). */
    var clipboardScreenshots by bool("clipboardScreenshots", false)

    /** Clipboard › Show First: pinned clips above recent ones (off: recent first). */
    var clipboardPinnedFirst by bool("clipboardPinnedFirst", false)

    /** Settings > Keyboards: the enabled keyboard languages, in the user's order (never empty). */
    var keyboards: List<dev.leconnn.ioskeyboard.text.Language>
        get() = raw.getString(KEY_KEYBOARDS, null).orEmpty().split(',')
            .mapNotNull { n -> dev.leconnn.ioskeyboard.text.Language.entries.firstOrNull { it.name == n } }.distinct()
            .ifEmpty { listOf(dev.leconnn.ioskeyboard.text.Language.ENGLISH) }
        set(v) = raw.edit().putString(KEY_KEYBOARDS, v.distinct().joinToString(",") { it.name }).apply()

    /** The keyboard in use (one of [keyboards]); switched from the emoji-key menu. */
    var keyboard: dev.leconnn.ioskeyboard.text.Language
        get() {
            val enabled = keyboards
            val saved = enabled.firstOrNull { it.name == raw.getString(KEY_KEYBOARD, null) }
            return saved ?: enabled.first()
        }
        set(v) = raw.edit().putString(KEY_KEYBOARD, v.name).apply()

    var clipboardRetention: Retention
        get() = runCatching { Retention.valueOf(raw.getString(KEY_RETENTION, null) ?: "") }.getOrDefault(Retention.HOUR)
        set(v) = raw.edit().putString(KEY_RETENTION, v.name).apply()

    /** Toolbar in the suggestion bar; on by default with the Gboard layout, off with iOS's. */
    var toolbar: Boolean
        get() = raw.getBoolean(KEY_TOOLBAR, keyLayout == KeyStyle.GBOARD)
        set(v) = raw.edit().putBoolean(KEY_TOOLBAR, v).apply()

    /** All toolbar buttons in the user's order (new buttons from later versions go last). */
    var toolbarOrder: List<ToolbarItem>
        get() {
            val saved = raw.getString(KEY_TOOLBAR_ORDER, null).orEmpty().split(',')
                .mapNotNull { n -> ToolbarItem.entries.firstOrNull { it.name == n } }
            return (saved + ToolbarItem.entries).distinct()
        }
        set(v) = raw.edit().putString(KEY_TOOLBAR_ORDER, v.joinToString(",") { it.name }).apply()

    /** Toolbar buttons the user hid (still reachable from the emoji-key menu). */
    var toolbarHidden: Set<ToolbarItem>
        get() = raw.getString(KEY_TOOLBAR_HIDDEN, null).orEmpty().split(',')
            .mapNotNull { n -> ToolbarItem.entries.firstOrNull { it.name == n } }.toSet()
        set(v) = raw.edit().putString(KEY_TOOLBAR_HIDDEN, v.joinToString(",") { it.name }).apply()

    /** Keep offensive words out of suggestions, corrections and slide to type (Gboard's default). */
    var blockOffensive by bool("blockOffensive", true)
    /** A row of digits above the letters (Gboard's "Number row"). */
    var numberRow by bool("numberRow", false)
    /** Show the number row in password fields even when [numberRow] is off. */
    var numberRowPasswords by bool("numberRowPasswords", false)
    /** Draw the emoji panel with the build's emoji image pack, when it has one. */
    var emojiArt by bool("emojiArt", true)

    enum class Appearance { CLASSIC, GLASS }

    var appearance: Appearance
        get() = runCatching { Appearance.valueOf(raw.getString(KEY_APPEARANCE, null) ?: "") }.getOrDefault(Appearance.GLASS)
        set(v) = raw.edit().putString(KEY_APPEARANCE, v.name).apply()

    /**
     * Liquid Glass Tint Amount, as in iOS 27's Settings > Appearance > Liquid Glass: 0 = More Clear,
     * 50 = Default, 100 = More Tinted (more opaque, more contrast).
     */
    var glassTint: Int
        get() = raw.getInt(KEY_GLASS_TINT, 50).coerceIn(0, 100)
        set(v) = raw.edit().putInt(KEY_GLASS_TINT, v.coerceIn(0, 100)).apply()

    /** Settings › Software Update › Automatic Checks: look for a newer version once a day. */
    var autoUpdateCheck by bool("autoUpdateCheck", true)

    /** The version whose "update available" chip was already shown in the bar (it shows once). */
    var updateNoticeSeen: String
        get() = raw.getString("updateNoticeSeen", "").orEmpty()
        set(v) = raw.edit().putString("updateNoticeSeen", v).apply()

    /** The welcome (first-run setup) was finished or skipped; see WelcomeActivity. */
    var onboarded by bool("onboarded", false)

    /** Liquid Glass Show Borders: outline the keys and popups for more separation. */
    var glassBorders by bool(KEY_GLASS_BORDERS, false)

    /** Hold time before accents, the trackpad and the emoji-key menu open. */
    enum class LongPress(val ms: Long, val label: String) { SHORT(250, "Short"), DEFAULT(400, "Default"), LONG(600, "Long") }

    var longPressDelay: LongPress
        get() = runCatching { LongPress.valueOf(raw.getString(KEY_LONG_PRESS, null) ?: "") }.getOrDefault(LongPress.DEFAULT)
        set(v) = raw.edit().putString(KEY_LONG_PRESS, v.name).apply()

    /** Key click volume, 0..100 % of the normal level. */
    var soundVolume: Int
        get() = raw.getInt(KEY_SOUND_VOLUME, 100).coerceIn(0, 100)
        set(v) = raw.edit().putInt(KEY_SOUND_VOLUME, v.coerceIn(0, 100)).apply()

    /** Key-press vibration strength. */
    enum class Vibration(val label: String) { LIGHT("Light"), MEDIUM("Medium"), STRONG("Strong") }

    var vibration: Vibration
        get() = runCatching { Vibration.valueOf(raw.getString(KEY_VIBRATION, null) ?: "") }.getOrDefault(Vibration.MEDIUM)
        set(v) = raw.edit().putString(KEY_VIBRATION, v.name).apply()

    /** Vertical size of the keys (Settings > Keyboard Height). */
    enum class Height(val scale: Float, val label: String) {
        SHORT(0.88f, "Short"), DEFAULT(1f, "Default"), TALL(1.12f, "Tall"), EXTRA_TALL(1.24f, "Extra Tall"),
    }

    /** Size of the letter preview that pops up over a pressed key (Settings > Typing). */
    enum class PreviewSize(val scale: Float, val label: String) {
        SMALL(0.85f, "Small"), DEFAULT(1f, "Default"), LARGE(1.18f, "Large"),
    }

    var previewSize: PreviewSize
        get() = runCatching { PreviewSize.valueOf(raw.getString(KEY_PREVIEW_SIZE, null) ?: "") }.getOrDefault(PreviewSize.DEFAULT)
        set(v) = raw.edit().putString(KEY_PREVIEW_SIZE, v.name).apply()

    /** How a recently copied item shows above the keys: a banner across the bar (iPhone) or chips (Gboard). */
    var copiedStyle: KeyStyle
        get() = runCatching { KeyStyle.valueOf(raw.getString(KEY_COPIED_STYLE, null) ?: "") }.getOrDefault(KeyStyle.IOS)
        set(v) = raw.edit().putString(KEY_COPIED_STYLE, v.name).apply()

    var keyboardHeight: Height
        get() = runCatching { Height.valueOf(raw.getString(KEY_HEIGHT, null) ?: "") }.getOrDefault(Height.DEFAULT)
        set(v) = raw.edit().putString(KEY_HEIGHT, v.name).apply()

    /** Key arrangement: iOS's or Gboard's (both drawn in the iOS style). */
    var keyLayout: KeyStyle
        get() = runCatching { KeyStyle.valueOf(raw.getString(KEY_LAYOUT, null) ?: "") }.getOrDefault(KeyStyle.IOS)
        set(v) = raw.edit().putString(KEY_LAYOUT, v.name).apply()

    /** The keyboard's layout for a field ([password] fields may add the number row). */
    fun spec(password: Boolean) = KeyboardSpec(
        keyLayout, numberRow || (numberRowPasswords && password), keyboardHeight.scale,
        hints = symbolHints, emojiKey = !(toolbar && emojiInToolbar),
    )

    /** Emoji panel: iOS (scrolls sideways in sections) or Gboard (scrolls up and down). */
    var emojiLayout: KeyStyle
        get() = style(KEY_EMOJI_LAYOUT)
        set(v) = raw.edit().putString(KEY_EMOJI_LAYOUT, v.name).apply()

    /** GIF panel: iOS (two rows scrolling sideways, like Messages' #images) or Gboard (two columns). */
    var gifLayout: KeyStyle
        get() = runCatching { KeyStyle.valueOf(raw.getString(KEY_GIF_LAYOUT, null) ?: "") }.getOrDefault(keyLayout)
        set(v) = raw.edit().putString(KEY_GIF_LAYOUT, v.name).apply()

    /** Clipboard panel: iOS (an inset list of rows) or Gboard (a grid of cards). */
    var clipboardLayout: KeyStyle
        get() = style(KEY_CLIPBOARD_LAYOUT)
        set(v) = raw.edit().putString(KEY_CLIPBOARD_LAYOUT, v.name).apply()

    /** A panel style: its own setting, else the earlier shared one ("panelLayout"), else the key layout. */
    private fun style(key: String): KeyStyle {
        for (k in listOf(key, KEY_PANEL_LAYOUT)) {
            raw.getString(k, null)?.let { v -> runCatching { return KeyStyle.valueOf(v) } }
        }
        return keyLayout
    }

    /** Small long-press symbols in the corners of letter keys (Gboard layout). */
    var symbolHints: Boolean
        get() = raw.getBoolean(KEY_SYMBOL_HINTS, keyLayout == KeyStyle.GBOARD)
        set(v) = raw.edit().putBoolean(KEY_SYMBOL_HINTS, v).apply()

    /** Emoji button in the toolbar dropdown instead of on the keyboard; never both. */
    var emojiInToolbar by bool("emojiInToolbar", false)

    /** Light or dark keyboard (and settings screen): follow the system, or force one. */
    enum class ThemeMode { AUTO, LIGHT, DARK }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(raw.getString(KEY_THEME_MODE, null) ?: "") }.getOrDefault(ThemeMode.AUTO)
        set(v) = raw.edit().putString(KEY_THEME_MODE, v.name).apply()

    var oneHanded: OneHanded
        get() = runCatching { OneHanded.valueOf(raw.getString(KEY_ONE_HANDED, null) ?: "") }.getOrDefault(OneHanded.OFF)
        set(v) = raw.edit().putString(KEY_ONE_HANDED, v.name).apply()

    /** Text Replacement shortcuts, in the order the user added them. */
    var textReplacements: List<Pair<String, String>>
        get() {
            val json = raw.getString(KEY_REPLACEMENTS, null) ?: return DEFAULT_REPLACEMENTS
            return runCatching {
                val a = JSONArray(json)
                List(a.length()) { i -> a.getJSONObject(i).let { it.getString("shortcut") to it.getString("phrase") } }
            }.getOrDefault(DEFAULT_REPLACEMENTS)
        }
        set(v) {
            val a = JSONArray()
            v.forEach { (s, p) -> a.put(JSONObject().put("shortcut", s).put("phrase", p)) }
            raw.edit().putString(KEY_REPLACEMENTS, a.toString()).apply()
        }

    /** Emoji picked most recently first ("Frequently Used"), at most [MAX_RECENTS]. */
    var emojiRecents: List<String>
        get() = runCatching { JSONArray(raw.getString(KEY_RECENTS, null) ?: "[]").let { a -> List(a.length()) { a.getString(it) } } }
            .getOrDefault(emptyList())
        set(v) = raw.edit().putString(KEY_RECENTS, JSONArray(v.take(MAX_RECENTS)).toString()).apply()

    fun addEmojiRecent(emoji: String) {
        emojiRecents = listOf(emoji) + emojiRecents.filter { it != emoji }
    }

    /** Skin tone last chosen per base emoji. */
    fun emojiTone(base: String): String? = runCatching { JSONObject(raw.getString(KEY_TONES, null) ?: "{}").optString(base) }
        .getOrNull()?.takeIf { it.isNotEmpty() }

    fun setEmojiTone(base: String, toned: String) {
        val o = runCatching { JSONObject(raw.getString(KEY_TONES, null) ?: "{}") }.getOrDefault(JSONObject())
        raw.edit().putString(KEY_TONES, o.put(base, toned).toString()).apply()
    }

    /**
     * Settings > Reset to iOS Defaults: every setting as iOS has it. Features iOS has go to Apple's
     * defaults (haptics off, as in iOS); Gboard-only extras (toolbar, number row, symbol hints,
     * copied-item chips, clipboard history, text editing, GIFs, Emoji Kitchen, screenshots) go off;
     * layouts and panels go to the iOS style. Kept: text replacements, emoji recents and tones
     * (and, outside these settings, learned words and pinned clips).
     */
    fun resetToIos() {
        raw.edit().apply {
            // iOS Settings > General > Keyboard defaults.
            putBoolean("KeyboardAutocapitalization", true)
            putBoolean("KeyboardAutocorrection", true)
            putBoolean("KeyboardPrediction", true)
            putBoolean("KeyboardInlineCompletion", true)
            putBoolean("KeyboardMathExpressionCompletion", true)
            putBoolean("KeyboardCheckSpelling", true)
            putBoolean("KeyboardCapsLock", true)
            putBoolean("KeyboardAllowPaddle", true)
            putBoolean("KeyboardPeriodShortcut", true)
            putBoolean("SmartTyping", true)
            putBoolean("KeyboardContinuousPathEnabled", true)
            putBoolean("KeyboardContinuousPathDeleteWholeWord", true)
            // Sounds & Haptics > Keyboard Feedback: sound on, haptic off.
            putBoolean("keyboardSound", true)
            putBoolean("keyboardHaptic", false)
            putBoolean("emojiSuggestions", true)
            // iOS 27 offers what you just copied above the keys.
            putBoolean("showCopiedItems", true)
            putBoolean("voiceTyping", true)
            putBoolean("emojiArt", true)
            putBoolean("blockOffensive", true)
            // Not on iOS.
            putBoolean("emojiKitchen", false)
            putBoolean("clipboardHistory", false)
            putBoolean("textEditing", false)
            putBoolean("gifs", false)
            putBoolean("clipboardScreenshots", false)
            putBoolean("numberRow", false)
            putBoolean("numberRowPasswords", false)
            putBoolean("emojiInToolbar", false)
            putBoolean(KEY_TOOLBAR, false)
            putBoolean(KEY_SYMBOL_HINTS, false)
            remove(KEY_TOOLBAR_ORDER)
            remove(KEY_TOOLBAR_HIDDEN)
            // Look and layout.
            putString(KEY_LAYOUT, KeyStyle.IOS.name)
            putString(KEY_EMOJI_LAYOUT, KeyStyle.IOS.name)
            putString(KEY_CLIPBOARD_LAYOUT, KeyStyle.IOS.name)
            putString(KEY_GIF_LAYOUT, KeyStyle.IOS.name)
            remove(KEY_PANEL_LAYOUT)
            putString(KEY_APPEARANCE, Appearance.GLASS.name)
            putInt(KEY_GLASS_TINT, 50)
            putBoolean(KEY_GLASS_BORDERS, false)
            putString(KEY_THEME_MODE, ThemeMode.AUTO.name)
            putString(KEY_HEIGHT, Height.DEFAULT.name)
            putString(KEY_PREVIEW_SIZE, PreviewSize.DEFAULT.name)
            putString(KEY_COPIED_STYLE, KeyStyle.IOS.name)
            putString(KEY_ONE_HANDED, OneHanded.OFF.name)
            putString(KEY_LONG_PRESS, LongPress.DEFAULT.name)
            putString(KEY_VIBRATION, Vibration.MEDIUM.name)
            putInt(KEY_SOUND_VOLUME, 100)
            remove(KEY_RETENTION)
        }.apply()
    }

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Prefs, Boolean> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = raw.getBoolean(key, default)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Boolean) {
            raw.edit().putBoolean(key, value).apply()
        }
    }

    companion object {
        private const val KEY_ONE_HANDED = "oneHanded"
        private const val KEY_APPEARANCE = "appearance"
        private const val KEY_GLASS_TINT = "glassTint"
        private const val KEY_GLASS_BORDERS = "glassBorders"
        const val KEY_THEME_MODE = "themeMode"
        private const val KEY_LONG_PRESS = "longPressDelay"
        private const val KEY_HEIGHT = "keyboardHeight"
        private const val KEY_PREVIEW_SIZE = "previewSize"
        private const val KEY_COPIED_STYLE = "copiedStyle"
        private const val KEY_SYMBOL_HINTS = "symbolHints"
        private const val KEY_RETENTION = "clipboardRetention"
        const val KEY_KEYBOARDS = "keyboards"
        const val KEY_KEYBOARD = "keyboard"
        private const val KEY_TOOLBAR = "toolbar"
        private const val KEY_TOOLBAR_ORDER = "toolbarOrder"
        private const val KEY_TOOLBAR_HIDDEN = "toolbarHidden"
        const val KEY_LAYOUT = "keyLayout"
        /** The earlier single emoji-and-clipboard setting, read as the default for both. */
        private const val KEY_PANEL_LAYOUT = "panelLayout"
        private const val KEY_EMOJI_LAYOUT = "emojiLayout"
        private const val KEY_CLIPBOARD_LAYOUT = "clipboardLayout"
        private const val KEY_GIF_LAYOUT = "gifLayout"
        private const val KEY_SOUND_VOLUME = "soundVolume"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_RECENTS = "emojiRecents"
        private const val KEY_TONES = "emojiTones"
        const val MAX_RECENTS = 32
        private const val KEY_REPLACEMENTS = "textReplacements"
        private val DEFAULT_REPLACEMENTS = listOf("omw" to "On my way!")

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                context.applicationContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences("settings", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
