package dev.leconnn.ioskeyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.ViewConfiguration
import dev.leconnn.ioskeyboard.emoji.EmojiPanel
import kotlin.math.abs
import kotlin.math.floor

/**
 * Draws the keyboard and turns touches into keyboard actions. Geometry is in iOS points
 * ([Geometry]) scaled to the view width. Preview bubbles and accent panels rise above the keys:
 * either into a transparent [headroomPt] at the top of this view (in-app preview), or into a
 * separate [popupHost] window (the IME, where the keyboard window must end at the keys so the
 * glass blur covers only the keyboard).
 */
/** Items of the menu shown when the emoji key is held (like the menu of the iOS globe key). */
sealed class KeyMenuItem(val label: String) {
    data object CLIPBOARD : KeyMenuItem("Clipboard")
    data object TEXT_EDITING : KeyMenuItem("Text Editing")
    data object GIFS : KeyMenuItem("GIFs")
    data object DICTATION : KeyMenuItem("Dictation")
    data object SETTINGS : KeyMenuItem("Keyboard Settings")
    data object SWITCH_KEYBOARD : KeyMenuItem("Switch Keyboard")
    /** One of the enabled keyboard languages; [current] gets a checkmark, as in iOS's list. */
    data class Keyboard(val language: dev.leconnn.ioskeyboard.text.Language, val current: Boolean) : KeyMenuItem(language.label)
}

/** Buttons of the toolbar in the suggestion bar (Settings > Toolbar). */
enum class ToolbarItem(val label: String) {
    EMOJI("Emoji"),
    CLIPBOARD("Clipboard"),
    TEXT_EDITING("Text Editing"),
    GIF("GIFs"),
    VOICE("Voice Typing"),
}

/** The small icon at the start of a copied-item chip. */
enum class ChipIcon { CLIPBOARD, CODE, LINK, EMAIL, PHONE, ADDRESS, NONE }

/**
 * A chip in the suggestion bar for something just copied (or a screenshot just taken), like
 * Gboard's: [icon] and [label], or an [image] thumbnail for a screenshot.
 */
data class Chip(
    val label: String, val icon: ChipIcon, val image: android.graphics.Bitmap? = null,
    /** iOS 27 style ("paste from Threads"): one item across the bar, [label] above this line. */
    val subtitle: String? = null,
)

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        /** [literal] is true for long-press variants: insert exactly, no smart punctuation. */
        fun onText(text: String, literal: Boolean = false)
        /** Where the next typed letter's key was tapped: offset from its centre in key widths / heights. */
        fun onCharTap(dx: Float, dy: Float) = Unit
        /** [repeat] is 0 for the first delete of a press, then counts auto-repeats. */
        fun onDelete(repeat: Int)
        fun onSpace()
        fun onReturn()
        /** Items for the menu that opens when the emoji key is held, top to bottom. */
        fun menuItems(): List<KeyMenuItem> = listOf(KeyMenuItem.SETTINGS, KeyMenuItem.SWITCH_KEYBOARD)
        /** An item in the emoji-key menu was chosen. */
        fun onMenu(item: KeyMenuItem) = Unit
        fun onCursorMove(dx: Int, dy: Int)
        fun onFeedback(kind: KeyKind)
        fun onShiftChanged(state: Glyphs.ShiftState)
        /** The chevron in the empty strip of the one-handed keyboard was tapped. */
        fun onOneHandedOff() = Unit
        /**
         * A slide-to-type gesture ended. [points] are layout points in [dev.leconnn.ioskeyboard.text.KeyProximity]'s
         * frame (row centres at y = 0, 54, 108).
         */
        fun onSwipe(points: List<Pair<Float, Float>>) = Unit
        /** A suggestion bar slot (0 = left, 1 = centre, 2 = right) was tapped. */
        fun onSuggestion(index: Int) = Unit
        /** A copied-text chip was tapped ([index] into the list given to [setChips]). */
        fun onChip(index: Int) = Unit
        /** The toolbar chevron was tapped while the bar had nothing else to show (empty field). */
        fun onEmptyBarChevron() = Unit
        /** A toolbar button in the suggestion bar was tapped. */
        fun onToolbar(item: ToolbarItem) = Unit
        /** The toolbar buttons were rearranged in the bar: the new order of the shown buttons. */
        fun onToolbarOrder(order: List<ToolbarItem>) = Unit
        fun clips(): List<dev.leconnn.ioskeyboard.text.ClipHistory.Clip> = emptyList()
        fun onClipPaste(clip: dev.leconnn.ioskeyboard.text.ClipHistory.Clip) = Unit
        fun onClipPinned(clip: dev.leconnn.ioskeyboard.text.ClipHistory.Clip, pinned: Boolean) = Unit
        fun onClipDelete(clip: dev.leconnn.ioskeyboard.text.ClipHistory.Clip) = Unit
        fun onClipClear() = Unit
        fun onEditAction(action: EditingPanel.Action, selecting: Boolean) = Unit
        /** The GIF panel's search field was tapped: type the search with the keys. */
        fun onGifSearch() = Unit
        /** Leave search typing (the bar's back arrow): back to the GIF or emoji panel. */
        fun onSearchCancel() = Unit
        /** The emoji panel's "Search Emoji" field was tapped. */
        fun onEmojiSearch() = Unit
        /** An emoji was picked from the search results row. */
        fun onEmojiSearchPick(emoji: String) = Unit
        fun onGifPicked(gif: Giphy.Gif) = Unit
        /** An Emoji Kitchen sticker was tapped. */
        fun onSticker(sticker: dev.leconnn.ioskeyboard.text.EmojiKitchen.Sticker) = Unit
        /** Dictation: the mic button (start / stop), the message's action, the panel closing. */
        fun onVoiceToggle() = Unit
        fun onVoiceMessageAction() = Unit
        fun onVoiceClosed() = Unit
        /** True when there is a selection to copy (never in password fields). */
        fun canCopy(): Boolean = true
        /** True when the clipboard has something to paste. */
        fun canPaste(): Boolean = true
    }

    /** One suggestion bar slot. [bold] marks the centre slot when it will be applied automatically. */
    data class Suggestion(val text: String, val display: String = text, val bold: Boolean = false)

    // ---- animation state (iOS motion; progress comes from uptime, so frames allocate nothing) ----
    private var frameAnimating = false
    private val lastPressed = HashSet<Key>()
    private val pressStart = HashMap<Key, Long>()
    private val releaseStart = HashMap<Key, Long>()
    private var trackpadShown = false
    private var trackpadChangedAt = 0L
    private var prevSuggestions: List<Suggestion?> = listOf(null, null, null)
    private var suggestionsAt = 0L
    private var prevShift = Glyphs.ShiftState.OFF
    private var shiftChangedAt = 0L
    private var ohFromScale = 1f
    private var ohFromOffset = 0f
    private var ohSince = 0L
    /** A preview bubble shrinking away after its key was released. */
    private class FadingBubble(val key: Key, val label: String, val at: Long)
    private val fadingBubbles = ArrayList<FadingBubble>()
    private val bubbleKey = RectF()
    private val bubbleHead = RectF()
    private val balloon = Path()

    /** iOS "Character Preview": bubble above pressed character keys. */
    var characterPreview = true
    /** Hold time before accents, the trackpad and the emoji-key menu open (Settings). */
    var longPressMs = 400L
        set(value) { field = value; emoji.longPressMs = value; clipboard.longPressMs = value }
    /** iOS "Enable Caps Lock": double-tapping shift locks it. */
    var capsLockEnabled = true
    /** iOS "Slide to Type": sliding across letters types a whole word. */
    var slideToType = true
    var oneHanded = Prefs.OneHanded.OFF
        set(value) {
            if (field == value) return
            // The keyboard slides and shrinks from where it is now (no animation before first layout).
            ohFromScale = hScale
            ohFromOffset = hOffset
            ohSince = if (isLaidOut) SystemClock.uptimeMillis() else 0L
            field = value
            invalidate()
        }

    var theme: KeyboardTheme = KeyboardTheme.forContext(context)
        set(value) {
            field = value
            emoji.theme = value; clipboard.theme = value; editing.theme = value; gifs.theme = value; voice.theme = value
            invalidate()
        }

    private val emoji = EmojiPanel(this, object : EmojiPanel.Callbacks {
        override fun onEmoji(emoji: String) = listener.onText(emoji, literal = true)
        override fun onDelete(repeat: Int) = listener.onDelete(repeat)
        override fun onExit() { emojiMode = false }
        override fun onFeedback() = listener.onFeedback(KeyKind.CHAR)
    }).also { it.theme = theme }

    private val clipboard: ClipboardPanel = ClipboardPanel(this, object : ClipboardPanel.Callbacks {
        override fun clips() = listener.clips()
        override fun onPaste(clip: dev.leconnn.ioskeyboard.text.ClipHistory.Clip) = listener.onClipPaste(clip)
        override fun onCardMenu(clip: dev.leconnn.ioskeyboard.text.ClipHistory.Clip, anchor: RectF) {
            showMenu(listOf(if (clip.pinned) "Unpin" else "Pin", "Delete"), anchor) { i ->
                if (i == 0) listener.onClipPinned(clip, !clip.pinned) else listener.onClipDelete(clip)
                clipboard.refresh()
            }
        }
        override fun onClear() { listener.onClipClear(); clipboard.refresh() }
        override fun onFeedback() = listener.onFeedback(KeyKind.CHAR)
    }).also { it.theme = theme }

    private val gifs: GifPanel = GifPanel(this, object : GifPanel.Callbacks {
        override fun onSearch() = listener.onGifSearch()
        override fun onPick(gif: Giphy.Gif) = listener.onGifPicked(gif)
        override fun onFeedback() = listener.onFeedback(KeyKind.CHAR)
    }).also { it.theme = theme }

    private val voice: VoicePanel = VoicePanel(this, object : VoicePanel.Callbacks {
        override fun onToggle() = listener.onVoiceToggle()
        override fun onMessageAction() = listener.onVoiceMessageAction()
        override fun onFeedback() = listener.onFeedback(KeyKind.CHAR)
    }).also { it.theme = theme }

    /** Dictation panel state, level (0..1) and error text, set by the service. */
    var voiceState: VoicePanel.State
        get() = voice.state
        set(value) { voice.state = value }
    var voiceLevel: Float
        get() = voice.level
        set(value) { voice.level = value }
    var voiceError: String
        get() = voice.errorText
        set(value) { voice.errorText = value; invalidate() }

    /** An Emoji Kitchen sticker in the bar, with its picture once loaded. */
    data class StickerChip(val sticker: dev.leconnn.ioskeyboard.text.EmojiKitchen.Sticker, val image: android.graphics.Bitmap?)

    /** Stickers for the emoji just typed (Emoji Kitchen); shown in the bar in place of words. */
    var stickers: List<StickerChip> = emptyList()
        set(value) {
            if (field == value) return
            if (field.map { it.sticker } != value.map { it.sticker }) stickerScroll = 0f
            // Note when each picture arrives, to fade it in.
            val now = SystemClock.uptimeMillis()
            for (c in value) if (c.image != null && field.none { it.sticker == c.sticker && it.image != null }) {
                if (field.any { it.sticker == c.sticker }) stickerAt[c.sticker.url] = now
            }
            field = value
            invalidate()
        }
    private val stickerAt = HashMap<String, Long>()
    private val stickerBox = RectF()
    private val stickerInner = RectF()
    private val searchPill = RectF()
    private var stickerScroll = 0f

    /** Opens the GIF panel on [query]'s results, or trending GIFs. */
    fun openGifs(query: String?) {
        gifQueryOnOpen = query
        if (panel == Panel.GIF) { if (query != null) gifs.search(query) else gifs.open() } else panel = Panel.GIF
    }
    private var gifQueryOnOpen: String? = null

    /** What a search being typed is for. */
    enum class SearchKind { GIF, EMOJI }
    var searchKind = SearchKind.GIF
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    /** While a search is typed: the text so far (the bar shows it as a search field); else null. */
    var searchText: String? = null
        set(value) {
            if (field == value) return
            val resize = (field == null) != (value == null)
            field = value
            if (value == null) resultsScroll = 0f
            if (resize) requestLayout()
            invalidate()
        }

    /** Emoji search: the matches, shown in a row between the search field and the keys (iOS). */
    var searchResults: List<String> = emptyList()
        set(value) { if (field != value) { field = value; resultsScroll = 0f; invalidate() } }
    private var resultsScroll = 0f
    private val resultsRowPx get() = if (searchText != null && searchKind == SearchKind.EMOJI) RESULTS_ROW_PT * scale else 0f

    private val editing: EditingPanel = EditingPanel(this, object : EditingPanel.Callbacks {
        override fun onAction(action: EditingPanel.Action, selecting: Boolean) = listener.onEditAction(action, selecting)
        override fun onFeedback() = listener.onFeedback(KeyKind.CHAR)
        override fun canCopy() = listener.canCopy()
        override fun canPaste() = listener.canPaste()
    }).also { it.theme = theme }

    /** Use Apple's emoji art in the emoji panel when the asset is in the build. */
    var appleEmoji: Boolean
        get() = emoji.appleStyle
        set(value) { emoji.appleStyle = value; invalidate() }

    /** Emoji panel layout (Settings > Layout): iOS or Gboard. */
    var emojiStyle = KeyStyle.IOS
        set(value) {
            if (field == value) return
            field = value
            emoji.vertical = value == KeyStyle.GBOARD
            invalidate()
        }

    /** GIF panel layout (Settings > Layout): iOS (#images rows) or Gboard (columns). */
    var gifStyle = KeyStyle.GBOARD
        set(value) { if (field != value) { field = value; gifs.horizontal = value == KeyStyle.IOS; invalidate() } }

    /** Clipboard panel layout (Settings > Layout): iOS or Gboard. */
    var clipboardStyle = KeyStyle.IOS
        set(value) {
            if (field == value) return
            field = value
            clipboard.listStyle = value == KeyStyle.IOS
            invalidate()
        }

    /** Which keys to show and where: layout style, number row and height (see KeyboardSpec). */
    var spec = KeyboardSpec()
        set(value) {
            if (field.sameAs(value)) return
            field = value
            requestLayout()
            invalidate()
        }

    /** Height of the keys area in points; panels (emoji, clipboard, editing) need at least 216 pt. */
    private val keysHeightPt get() = when {
        panel != Panel.KEYS -> maxOf(spec.height, Geometry.HEIGHT)
        // A physical keyboard is typing: only the suggestion bar is shown (like Gboard's toolbar).
        barOnly && searchText == null -> 0f
        else -> spec.height
    }

    /** True while a physical keyboard is attached: the keys hide, the bar stays. */
    var barOnly = false
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    /** What fills the keys area. */
    enum class Panel { KEYS, EMOJI, CLIPBOARD, EDITING, GIF, VOICE }

    var panel = Panel.KEYS
        set(value) {
            if (field == value) return
            if (field == Panel.GIF) gifs.close()
            if (field == Panel.VOICE) listener.onVoiceClosed()
            field = value
            // Interruptible: switching back mid-transition continues from what's on screen
            // (the keys were still partly visible) instead of starting over from nothing.
            val now = SystemClock.uptimeMillis()
            val done = if (panelAt == 0L) 1f else ((now - panelAt) / PANEL_MS.toFloat()).coerceIn(0f, 1f)
            panelAt = if (!isLaidOut) 0L else now - ((1f - done) * PANEL_MS).toLong()
            if (value != Panel.KEYS) barPanel = value
            barTarget = BarTarget.NONE
            when (value) {
                Panel.EMOJI -> emoji.open()
                Panel.CLIPBOARD -> clipboard.open()
                Panel.EDITING -> editing.open()
                Panel.GIF -> gifQueryOnOpen.let { q -> gifQueryOnOpen = null; if (q != null) gifs.search(q) else gifs.open() }
                Panel.VOICE, Panel.KEYS -> Unit
            }
            closeMenu()
            toolbarExpanded = false
            requestLayout()
            invalidate()
        }

    /** Emoji keyboard shown in place of the keys (the bar stays). */
    var emojiMode: Boolean
        get() = panel == Panel.EMOJI
        set(value) { panel = if (value) Panel.EMOJI else if (panel == Panel.EMOJI) Panel.KEYS else panel }

    /** Set while the current gesture belongs to an open panel. */
    private var panelGesture = false

    /** Reloads the clipboard panel's list (e.g. after a new copy while it is open). */
    fun refreshClipboard() {
        if (panel == Panel.CLIPBOARD) clipboard.refresh()
    }

    /** The clipboard changed: the editing panel re-checks whether Paste can be used. */
    fun refreshPaste() { if (panel == Panel.EDITING) editing.refreshPaste() }

    /** When the panel last changed (0: no transition), and the last panel that was open. */
    private var panelAt = 0L
    private var barPanel = Panel.EMOJI

    /** 0..1 through the slide-and-fade between the keys and a panel (and back). */
    private fun panelProgress(): Float = if (panelAt == 0L) 1f else easeOut(progress(panelAt, PANEL_MS))

    /** A panel always gets the bar (for its back button and title), even when the keys have none. */
    private val barShownPt: Float
        get() {
            val base = if ((panel != Panel.KEYS || searchText != null) && barPt == 0f) PANEL_BAR_PT else barPt
            return if (searchText != null && searchKind == SearchKind.EMOJI) base + RESULTS_ROW_PT else base
        }

    /** The panel bar's buttons: what the current touch went down on. */
    private enum class BarTarget { NONE, BACK, CLEAR, SEARCH, STICKER }
    /** Where the emoji panel's sticker row starts (it isn't scrolled there). */
    private var panelStickersLeft = 0f
    private var barTarget = BarTarget.NONE
    private var barGesture = false

    private val backD get() = minOf(barShownPt - 10f, 32f) * scale
    private val backCx get() = maxOf(chevronCx, 6f * scale + backD / 2)
    private val panelTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Open menu (emoji key, clipboard card): labels, rect (pixels, relative to the keys' top), highlight. */
    private var menu: List<String>? = null
    private var menuAction: ((Int) -> Unit)? = null
    private val menuRect = RectF()
    private var menuSelected = -1
    private var menuAt = 0L
    /** A menu that was just closed, drawn fading out until [MENU_OUT_MS] after [menuGoneAt]. */
    private var menuGone: List<String>? = null
    private var menuGoneSelected = -1
    private var menuGoneAt = 0L
    /** Row with a checkmark (the current keyboard), or -1; for the open and the closing menu. */
    private var menuChecked = -1
    private var menuGoneChecked = -1
    private val checkPath = Path()
    private val menuLayer = RectF()

    var plane = Plane.LETTERS
        private set
    var shift = Glyphs.ShiftState.OFF
        private set
    var returnLabel = "return"
        set(value) { field = value; invalidate() }
    var returnHighlighted = false
        set(value) { field = value; invalidate() }
    var bottomInsetPx = 0
        set(value) { field = value; requestLayout() }

    /** Transparent space above the keys for popups when there is no [popupHost], in points. */
    var headroomPt = HEADROOM
        set(value) { field = value; requestLayout() }
    /** Draws popups in its own window; it calls [drawPopups] with its own keyboard top. */
    var popupHost: PopupOverlay? = null
    /** Whether the overlay showed something last frame (so it is redrawn once more to clear it). */
    private var popupsShown = false
    /** Fill the panel behind the keys. Off when the IME window background provides it (glass blur). */
    var paintsPanel = true
        set(value) { field = value; invalidate() }

    /** Height of the suggestion bar above the keys, in points (0 hides it; iOS uses 44). */
    var barPt = 0f
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    private var suggestions: List<Suggestion?> = listOf(null, null, null)

    /** Copied-text chips shown instead of the suggestion slots (empty: none). */
    private var chips: List<Chip> = emptyList()
    private val chipRects = ArrayList<RectF>()

    fun setChips(list: List<Chip>) {
        if (list == chips) return
        prevChips = chips
        chips = list
        chipsAt = if (isLaidOut) SystemClock.uptimeMillis() else 0L
        invalidate()
    }
    /** What the bar showed before the chips last changed, and when (cross-fade, like the slots). */
    private var prevChips: List<Chip> = emptyList()
    private var chipsAt = 0L

    /** Toolbar buttons in the bar, in order (empty: toolbar off). */
    var toolbarItems: List<ToolbarItem> = emptyList()
        set(value) { if (field != value) { field = value; invalidate() } }
    private var toolbarExpanded = false
    /** Toolbar transition: 1 = buttons out, 0 = folded into the chevron; animated from [tbFrom]. */
    private var tbFrom = 1f
    private var tbTarget = 1f
    private var tbAt = 0L
    /** Rearranging the toolbar buttons in the bar (after a long press); they wiggle like iOS icons. */
    private var rearranging = false
    /** Drawn x of each toolbar button, eased towards its slot so reordering glides. */
    private val toolX = HashMap<ToolbarItem, Float>()
    private val toolbarOn get() = toolbarItems.isNotEmpty()
    private val hasBarContent get() = chips.isNotEmpty() || stickers.isNotEmpty() || suggestions.any { it != null }
    /** Where the suggestion slots / chips start: after the chevron when the toolbar is on. */
    private val slotsLeft get() = if (toolbarOn) width * CHEVRON_FRACTION else 0f
    private val toolSlotW get() = width * TOOL_FRACTION
    private val chevronCx get() = width * CHEVRON_CENTER_FRACTION

    /** Toolbar button [i]: buttons 22 % of the width apart, always centred as a group. */
    private fun toolButtonRect(i: Int, out: RectF): RectF {
        val cx = width / 2f + (i - (toolbarItems.size - 1) / 2f) * toolSlotW
        return out.apply { set(cx - toolSlotW / 2, keyboardTop, cx + toolSlotW / 2, keysTop) }
    }

    /** How far the buttons are out (1) or folded into the chevron (0), animated between the two. */
    private fun toolbarShown(): Float {
        val target = if (!hasBarContent || toolbarExpanded) 1f else 0f
        if (target != tbTarget) {
            tbFrom = toolbarShownAt(SystemClock.uptimeMillis())
            tbTarget = target
            tbAt = SystemClock.uptimeMillis()
        }
        val p = progress(tbAt, TOOLBAR_MS)
        return lerp(tbFrom, tbTarget, easeInOut(p))
    }

    private fun toolbarShownAt(now: Long): Float {
        if (!animationsOn) return tbTarget
        val p = ((now - tbAt) / TOOLBAR_MS.toFloat()).coerceIn(0f, 1f)
        return lerp(tbFrom, tbTarget, easeInOut(p))
    }

    private fun toolButtonAt(x: Float): Int {
        for (i in toolbarItems.indices) {
            val r = toolButtonRect(i, tmpRect)
            if (x >= r.left && x < r.right) return i
        }
        return -1
    }

    private fun chipAt(x: Float): Int {
        for (i in chips.indices) if (i < chipRects.size && x >= chipRects[i].left && x <= chipRects[i].right) return i
        return -1
    }

    fun setSuggestions(left: Suggestion?, centre: Suggestion?, right: Suggestion?) {
        val next = listOf(left, centre, right)
        if (next == suggestions) return
        // Cross-fade from what is shown now, like the iOS bar.
        prevSuggestions = suggestions
        suggestionsAt = SystemClock.uptimeMillis()
        suggestions = next
        invalidate()
    }

    /** Top of the keyboard (suggestion bar, then keys) in view pixels. */
    val keyboardTop get() = headroomPt * scale
    val barPx get() = barShownPt * scale
    /** Top of the keys area in view pixels. */
    private val keysTop get() = keyboardTop + barPx
    /** Headroom a [popupHost] should reserve above the keys, in pixels. */
    val popupHeadroomPx get() = HEADROOM * scale

    private var scale = 1f
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Scratch objects reused while drawing, so a frame allocates nothing per key.
    private val keyRect = RectF()
    private val tmpRect = RectF()
    private val glyphRect = RectF()
    private val emojiArea = RectF()
    private val fontMetrics = Paint.FontMetrics()
    private val pressedKeys = HashSet<Key>()
    private val handler = Handler(Looper.getMainLooper())

    private enum class Mode { TAP, VARIANTS, TRACKPAD, DELETE_REPEAT, BAR, SWIPE, MENU, TOOL, CHIP, RESULTS, STICKERS }

    private inner class Touch(val id: Int, var key: Key?, val downX: Float, val downY: Float) {
        var mode = Mode.TAP
        /** When the preview bubble appeared (touch down, or sliding onto a new key). */
        var downAt = SystemClock.uptimeMillis()
        var variantsAt = 0L
        var variants: Variants? = null
        /** Accent panel head, in pixels relative to the top of the keys area. */
        var variantRect = RectF()
        var variantSelected = 0
        var variantBase = 0
        /** Finger x when the panel opened; selection moves relative to it, so holding still keeps the base. */
        var variantAnchorX = downX
        var lastX = downX
        var lastY = downY
        var deleteRepeats = 0
        var barIndex = -1
        /** Toolbar button being dragged to a new place. */
        var dragging = false
        /** Slide-to-type path: view pixels and the time each point was recorded. */
        val trail = ArrayList<Triple<Float, Float, Long>>()
        /** Plane switch started by this finger on 123 / ABC: typing on release returns to it. */
        var slideFromPlane: Plane? = null
        val longPress = Runnable { onLongPress(this) }
        val repeat = object : Runnable {
            override fun run() {
                deleteRepeats++
                listener.onDelete(deleteRepeats)
                listener.onFeedback(KeyKind.DELETE)
                handler.postDelayed(this, REPEAT_MS)
            }
        }
    }

    private val touches = LinkedHashMap<Int, Touch>()
    private var lastShiftTap = 0L

    fun setPlane(p: Plane) {
        plane = p
        panel = Panel.KEYS
        invalidate()
    }

    fun setShiftState(s: Glyphs.ShiftState) {
        if (shift == s) return
        prevShift = shift
        shiftChangedAt = if (isLaidOut) SystemClock.uptimeMillis() else 0L
        shift = s
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        scale = w / Geometry.WIDTH
        val h = ((headroomPt + barShownPt + keysHeightPt) * scale).toInt() + bottomInsetPx
        setMeasuredDimension(w, h)
    }

    // ---- drawing -------------------------------------------------------------------------

    // One-handed mode squeezes the layout horizontally towards one edge; y is unchanged.
    private val targetHScale get() = if (oneHanded == Prefs.OneHanded.OFF) 1f else ONE_HANDED_SCALE
    private val targetHOffset get() = if (oneHanded == Prefs.OneHanded.RIGHT) Geometry.WIDTH * (1 - ONE_HANDED_SCALE) else 0f
    /** Progress of the one-handed slide, eased (1 when settled). */
    private val oneHandedProgress get() = easeInOut(progress(ohSince, ONE_HANDED_MS))
    private val hScale get() = lerp(ohFromScale, targetHScale, oneHandedProgress)
    private val hOffset get() = lerp(ohFromOffset, targetHOffset, oneHandedProgress)

    // ---- animation helpers ---------------------------------------------------------------

    /** Off when the system's "Remove animations" (animator duration scale 0) is set. */
    private val animationsOn get() = ValueAnimator.areAnimatorsEnabled()

    /** 0..1 since [since] over [durationMs]; flags the frame for another redraw while below 1. */
    private fun progress(since: Long, durationMs: Long): Float {
        if (!animationsOn) return 1f
        val p = ((SystemClock.uptimeMillis() - since) / durationMs.toFloat()).coerceIn(0f, 1f)
        if (p < 1f) frameAnimating = true
        return p
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)
    private fun easeInOut(p: Float): Float {
        if (p < 0.5f) return 4f * p * p * p
        val q = -2f * p + 2f
        return 1f - q * q * q / 2f
    }

    private fun withAlpha(color: Int, a: Float) =
        ((((color ushr 24) and 0xFF) * a).toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    /** Blends colour [a] towards [b] by [t], alpha included. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        fun ch(shift: Int) = ((a ushr shift and 0xFF) + ((b ushr shift and 0xFF) - (a ushr shift and 0xFF)) * t).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Records when keys went down and came up, for the release fade and the glass press. */
    private fun trackPresses(pressed: Set<Key>) {
        val now = SystemClock.uptimeMillis()
        for (k in pressed) if (k !in lastPressed) { pressStart[k] = now; releaseStart.remove(k) }
        for (k in lastPressed) if (k !in pressed) releaseStart[k] = now
        lastPressed.clear()
        lastPressed.addAll(pressed)
    }

    /**
     * How "pressed" a key looks, 0..1: function keys fade back after release (~100 ms) as on iOS;
     * glass letter keys swell in quickly and ease back more slowly.
     */
    private fun pressLevel(key: Key, pressed: Boolean): Float {
        val glassChar = theme.glass && key.kind == KeyKind.CHAR
        if (pressed) return if (glassChar) easeOut(progress(pressStart[key] ?: 0L, GLASS_PRESS_MS)) else 1f
        val at = releaseStart[key] ?: return 0f
        val p = progress(at, if (glassChar) GLASS_RELEASE_MS else KEY_RELEASE_MS)
        if (p >= 1f) { releaseStart.remove(key); return 0f }
        return 1f - easeOut(p)
    }

    /** Point rect to pixels, with the keys area starting at [top] (this view's, or a popup host's). */
    private fun px(r: RectF, top: Float = keysTop) = pxInto(r, RectF(), top)

    private fun pxInto(r: RectF, out: RectF, top: Float = keysTop): RectF {
        out.set((hOffset + r.left * hScale) * scale, top + r.top * scale,
            (hOffset + r.right * hScale) * scale, top + r.bottom * scale)
        return out
    }

    /** The empty strip beside a one-handed keyboard, in view pixels, or null at full width. */
    private fun oneHandedStrip(): RectF? {
        val w = Geometry.WIDTH * (1 - ONE_HANDED_SCALE) * scale
        return when (oneHanded) {
            Prefs.OneHanded.OFF -> null
            Prefs.OneHanded.LEFT -> stripRect.apply { set(width - w, keysTop, width.toFloat(), keysTop + keysHeightPt * scale) }
            Prefs.OneHanded.RIGHT -> stripRect.apply { set(0f, keysTop, w, keysTop + keysHeightPt * scale) }
        }
    }
    private val stripRect = RectF()

    private fun drawOneHandedChevron(canvas: Canvas, strip: RectF) {
        val cx = strip.centerX()
        val cy = strip.centerY()
        val s = 7f * scale
        // Points towards the empty side: tapping it expands the keyboard back to full width.
        val dir = if (oneHanded == Prefs.OneHanded.LEFT) 1f else -1f
        glyphPaint.color = theme.text
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 2.2f * scale
        glyphPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - dir * s / 2, cy - s, cx + dir * s / 2, cy, glyphPaint)
        canvas.drawLine(cx + dir * s / 2, cy, cx - dir * s / 2, cy + s, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    /** Time since the last frame (for glides that must not depend on the refresh rate). */
    private var frameDt = 16f
    private var lastFrameAt = 0L

    override fun onDraw(canvas: Canvas) {
        val frameNow = SystemClock.uptimeMillis()
        frameDt = if (lastFrameAt == 0L) 16f else (frameNow - lastFrameAt).toFloat().coerceIn(1f, 50f)
        lastFrameAt = frameNow
        frameAnimating = false
        if (paintsPanel) drawPanel(canvas)
        drawBarArea(canvas)

        val pressed = pressedKeys
        pressed.clear()
        var trackpad = false
        for (t in touches.values) {
            if (t.mode == Mode.TRACKPAD) trackpad = true else t.key?.let(pressed::add)
        }
        trackPresses(pressed)
        // Trackpad mode: key labels fade out to blank keys, and back in afterwards.
        if (trackpad != trackpadShown) {
            trackpadShown = trackpad
            trackpadChangedAt = SystemClock.uptimeMillis()
        }
        val fade = progress(trackpadChangedAt, TRACKPAD_FADE_MS)
        val labelAlpha = if (trackpad) 1f - fade else fade
        // Opening or closing a panel: the new content rises a little and fades in.
        val enter = panelProgress()
        val enterLayer = if (enter < 1f) {
            canvas.saveLayerAlpha(0f, keysTop, width.toFloat(), height.toFloat(), (enter * 255).toInt()).also {
                canvas.translate(0f, (1f - enter) * PANEL_SLIDE_PT * scale)
            }
        } else -1
        if (panel != Panel.KEYS) {
            val bottom = keysTop + keysHeightPt * scale
            when (panel) {
                // The emoji panel is laid out for 216 pt; on a taller keyboard it sits at the bottom.
                Panel.EMOJI -> {
                    // iOS style keeps its 216 pt layout at the bottom; Gboard's vertical grid fills it all.
                    val top = if (emojiStyle == KeyStyle.GBOARD) keysTop else bottom - Geometry.HEIGHT * scale
                    emojiArea.set(0f, top, width.toFloat(), bottom)
                    emoji.draw(canvas, emojiArea, scale)
                }
                Panel.CLIPBOARD -> {
                    emojiArea.set(0f, keysTop, width.toFloat(), bottom)
                    clipboard.draw(canvas, emojiArea, scale)
                }
                Panel.EDITING -> {
                    emojiArea.set(0f, keysTop, width.toFloat(), bottom)
                    editing.draw(canvas, emojiArea, scale)
                }
                Panel.GIF -> {
                    emojiArea.set(0f, keysTop, width.toFloat(), bottom)
                    gifs.draw(canvas, emojiArea, scale)
                }
                Panel.VOICE -> {
                    emojiArea.set(0f, keysTop, width.toFloat(), bottom)
                    voice.draw(canvas, emojiArea, scale)
                }
                Panel.KEYS -> Unit
            }
        } else {
            for (key in spec.keys(plane)) drawKey(canvas, key, key in pressed, labelAlpha)
            if (oneHandedProgress >= 1f) oneHandedStrip()?.let { drawOneHandedChevron(canvas, it) }
        }
        if (enterLayer >= 0) canvas.restoreToCount(enterLayer)
        val trail = touches.values.firstOrNull { it.mode == Mode.SWIPE }?.trail ?: fadingTrail
        if (trail != null && drawTrail(canvas, trail)) postInvalidateOnAnimation() else if (trail === fadingTrail) fadingTrail = null

        val host = popupHost
        if (host != null) {
            // Redraw the overlay only while it shows something, or to clear what it showed.
            val visible = popupsVisible()
            if (visible || popupsShown) host.invalidate()
            popupsShown = visible
        } else if (drawPopups(canvas, keysTop)) postInvalidateOnAnimation()
        drawNotice(canvas)
        if (frameAnimating) postInvalidateOnAnimation()
    }

    // ---- notices: a short message in the keyboard (instead of an Android toast) ----

    private var notice: String? = null
    private var noticeAt = 0L
    private val noticeRect = RectF()

    /** Shows [text] briefly in a capsule at the top of the keys, in the keyboard's own style. */
    fun showNotice(text: String) {
        val now = SystemClock.uptimeMillis()
        val t = now - noticeAt
        // Already showing: keep it on screen (from its current opacity) and restart only the
        // hold, so a second message doesn't blink the capsule out and in.
        val shown = when {
            notice == null || t >= NOTICE_MS -> 0f
            t < NOTICE_FADE_IN_MS -> t / NOTICE_FADE_IN_MS.toFloat()
            t > NOTICE_MS - NOTICE_FADE_OUT_MS -> (NOTICE_MS - t) / NOTICE_FADE_OUT_MS.toFloat()
            else -> 1f
        }
        notice = text
        noticeAt = now - (shown * NOTICE_FADE_IN_MS).toLong()
        invalidate()
    }

    private fun drawNotice(canvas: Canvas) {
        val text = notice ?: return
        val t = SystemClock.uptimeMillis() - noticeAt
        if (t >= NOTICE_MS) { notice = null; return }
        val fadeIn = if (animationsOn) (t / NOTICE_FADE_IN_MS.toFloat()).coerceIn(0f, 1f) else 1f
        val fadeOut = if (animationsOn) ((NOTICE_MS - t) / NOTICE_FADE_OUT_MS.toFloat()).coerceIn(0f, 1f) else 1f
        val a = minOf(easeOut(fadeIn), fadeOut)
        val s = scale
        textPaint.textSize = 15f * s
        textPaint.typeface = REGULAR
        val w = textPaint.measureText(text) + 36f * s
        val h = 36f * s
        val cy = keysTop + 14f * s + h / 2
        noticeRect.set(width / 2f - w / 2, cy - h / 2, width / 2f + w / 2, cy + h / 2)
        canvas.save()
        // Grows in from slightly smaller, like an iOS HUD.
        val k = 0.92f + 0.08f * easeOut(fadeIn)
        canvas.scale(k, k, noticeRect.centerX(), noticeRect.centerY())
        keyPaint.color = withAlpha(theme.keyShadow, a)
        canvas.drawRoundRect(noticeRect.left, noticeRect.top + 2f * s, noticeRect.right, noticeRect.bottom + 2f * s, h / 2, h / 2, keyPaint)
        keyPaint.color = withAlpha(theme.popup, a)
        canvas.drawRoundRect(noticeRect, h / 2, h / 2, keyPaint)
        if (theme.glass) {
            // Liquid Glass: the same light rim as the keys.
            glyphPaint.style = Paint.Style.STROKE
            glyphPaint.strokeWidth = maxOf(1f, 0.75f * s)
            glyphPaint.color = withAlpha(theme.rim, a * 0.8f)
            canvas.drawRoundRect(noticeRect, h / 2, h / 2, glyphPaint)
            glyphPaint.style = Paint.Style.FILL
        }
        textPaint.color = withAlpha(theme.text, a)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, noticeRect.centerX(), noticeRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        canvas.restore()
        // Redraw every frame while fading; otherwise wake for the fade-out.
        if (fadeIn < 1f || fadeOut < 1f) frameAnimating = true
        else postInvalidateDelayed(NOTICE_MS - NOTICE_FADE_OUT_MS - t)
    }

    /**
     * How far (pixels) a preview bubble or accent panel for [key] reaches above this view's top,
     * shadow included; 0 when it fits inside the keyboard (every row below the top one).
     */
    fun headroomFor(key: Key): Float =
        ((POPUP_RISE_PT + POPUP_SHADOW_PT) * scale - (keysTop + key.rect.top * scale)).coerceAtLeast(0f)

    /** Room the popups showing now need above the keyboard, in pixels (0: none reach over the app). */
    fun popupHeadroomNeededPx(): Float {
        if (emojiMode) return if (emoji.hasPopup) popupHeadroomPx else 0f
        if (panel != Panel.KEYS) return 0f
        val now = SystemClock.uptimeMillis()
        var need = 0f
        for (b in fadingBubbles) if (now - b.at < BUBBLE_RELEASE_MS) need = maxOf(need, headroomFor(b.key))
        for (t in touches.values) {
            val key = t.key ?: continue
            if (t.mode == Mode.VARIANTS || (t.mode == Mode.TAP && key.kind == KeyKind.CHAR && characterPreview)) {
                need = maxOf(need, headroomFor(key))
            }
        }
        return need
    }

    /** True while a bubble, accent panel or tone panel is showing above the keys. */
    fun popupsVisible(): Boolean {
        if (menu != null || menuGone != null) return true
        if (emojiMode) return emoji.hasPopup
        if (panel != Panel.KEYS) return false
        val now = SystemClock.uptimeMillis()
        for (b in fadingBubbles) if (now - b.at < BUBBLE_RELEASE_MS) return true
        for (t in touches.values) {
            if (t.key == null) continue
            if (t.mode == Mode.VARIANTS) return true
            if (t.mode == Mode.TAP && t.key?.kind == KeyKind.CHAR && characterPreview) return true
        }
        return false
    }

    /**
     * Classic: flat grey. Glass without window blur (in-app preview): the tint. Square top corners:
     * the app ends at the keyboard's top edge, so rounded corners would only show black behind them.
     */
    private fun drawPanel(canvas: Canvas) {
        keyPaint.color = if (theme.glass) theme.panelTint else theme.background
        if (theme.glass) {
            // The in-app preview: Liquid Glass has rounded top corners (the IME window rounds its own).
            val r = GLASS_CORNER_PT * scale
            canvas.drawRoundRect(0f, keyboardTop, width.toFloat(), height + r, r, r, keyPaint)
        } else {
            canvas.drawRect(0f, keyboardTop, width.toFloat(), height.toFloat(), keyPaint)
        }
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** The last swipe's trail, still fading out after the finger lifted. */
    private var fadingTrail: List<Triple<Float, Float, Long>>? = null

    /** A glowing line that fades out behind the finger, like iOS. Returns true while still fading. */
    private fun drawTrail(canvas: Canvas, trail: List<Triple<Float, Float, Long>>): Boolean {
        val now = SystemClock.uptimeMillis()
        trailPaint.strokeWidth = 6f * scale
        for (i in 1 until trail.size) {
            val (x0, y0, _) = trail[i - 1]
            val (x1, y1, at) = trail[i]
            val fade = 1f - easeOut(((now - at).toFloat() / TRAIL_FADE_MS).coerceIn(0f, 1f))
            if (fade <= 0f) continue
            trailPaint.color = theme.accent and 0x00FFFFFF or ((fade * 200).toInt() shl 24)
            canvas.drawLine(x0, y0, x1, y1, trailPaint)
        }
        return trail.isNotEmpty() && now - trail.last().third < TRAIL_FADE_MS
    }

    /** View pixels to the decoder's layout frame (row 1 centre at y = 0). */
    private fun layoutPoint(xPx: Float, yPx: Float) =
        (xPx / scale - hOffset) / hScale to spec.decoderY((yPx - keysTop) / scale)


    /**
     * The bar above the keys: iOS's three suggestion slots (or copied-text chips), plus the toolbar:
     * its buttons when there is nothing to suggest, otherwise a dropdown chevron at the left that
     * slides the buttons in over the suggestions.
     */
    private fun drawBar(canvas: Canvas, chevron: Boolean = true) {
        searchText?.let { drawSearchField(canvas, it); return }
        if (!toolbarOn) {
            drawBarContent(canvas)
            return
        }
        // One transition: the buttons fold into the chevron while the words fade in, and back.
        val shown = toolbarShown()
        if (shown < 1f && hasBarContent) {
            val count = canvas.saveLayerAlpha(slotsLeft, keyboardTop, width.toFloat(), keysTop, ((1f - shown) * 255).toInt())
            canvas.translate(shown * 16f * scale, 0f)
            drawBarContent(canvas)
            canvas.restoreToCount(count)
        }
        if (chevron) drawChevron(canvas, shown)
        if (shown > 0f) drawToolButtons(canvas, shown)
    }

    /**
     * The bar, cross-fading between the keys' bar (suggestions / toolbar) and a panel's bar (back
     * button, title) while a panel opens or closes.
     */
    private fun drawBarArea(canvas: Canvas) {
        if (barShownPt <= 0f) return
        val p = panelProgress()
        // How much of the panel bar shows: 1 while a panel is open.
        val a = if (panel != Panel.KEYS) p else 1f - p
        if (a < 1f && barPt > 0f) {
            if (a > 0f) {
                // The chevron isn't drawn here: the panel bar's arrow is the chevron mid-morph.
                val c = canvas.saveLayerAlpha(0f, keyboardTop, width.toFloat(), keysTop, ((1f - a) * 255).toInt())
                drawBar(canvas, chevron = !toolbarOn)
                canvas.restoreToCount(c)
            } else {
                drawBar(canvas)
            }
        }
        if (a > 0f) drawPanelBar(canvas, a)
    }

    /**
     * A panel's bar: a round back button where the toolbar's chevron is (the chevron grows a shaft
     * and becomes the arrow), the panel's name, and Clear for the clipboard.
     */
    private fun drawPanelBar(canvas: Canvas, a: Float) {
        val s = scale
        val cy = (keyboardTop + keysTop) / 2
        val cx = backCx
        val d = backD
        // With the toolbar on, the chevron was already there, so the arrow stays solid and morphs.
        val glyphAlpha = if (toolbarOn) 1f else a
        keyPaint.color = withAlpha(if (barTarget == BarTarget.BACK) theme.pressedFunctionKey else theme.functionKey, a)
        canvas.drawCircle(cx, cy, d / 2 * (0.6f + 0.4f * a), keyPaint)
        glyphPaint.color = withAlpha(theme.text, glyphAlpha)
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 2f * s
        glyphPaint.strokeCap = Paint.Cap.ROUND
        // One glyph morphs between the toolbar chevron (its size, stroke and direction: › when the
        // buttons are folded, ‹ when out) and the back arrow, so the two never overlap.
        canvas.save()
        if (toolbarOn) {
            canvas.rotate((1f - a) * 180f * (1f - toolbarShown()), cx, cy)
            glyphPaint.strokeWidth = lerp(CHEVRON_STROKE_PT, 2f, a) * s
        }
        val arm = lerp(if (toolbarOn) 5f else 6f, 6f, a) * s
        val tip = cx - lerp(arm / s / 2f, 7f, a) * s
        canvas.drawLine(tip + arm, cy - arm, tip, cy, glyphPaint)
        canvas.drawLine(tip, cy, tip + arm, cy + arm, glyphPaint)
        if (a > 0f) canvas.drawLine(tip, cy, tip + 14f * s * a, cy, glyphPaint)
        canvas.restore()
        glyphPaint.style = Paint.Style.FILL
        panelTitlePaint.textSize = 17f * s
        panelTitlePaint.color = withAlpha(theme.text, a)
        panelTitlePaint.textAlign = Paint.Align.LEFT
        val ty = cy - (panelTitlePaint.descent() + panelTitlePaint.ascent()) / 2
        val title = when (barPanel) {
            Panel.EMOJI -> "Emoji"
            Panel.CLIPBOARD -> "Clipboard"
            Panel.EDITING -> "Text editing"
            Panel.GIF -> "GIFs"
            Panel.VOICE -> "Dictation"
            Panel.KEYS -> ""
        }
        if (barPanel == Panel.EMOJI && stickers.isNotEmpty()) {
            // After an emoji: its Emoji Kitchen stickers, and a magnifier that still opens search.
            val left = cx + d / 2 + 6f * s
            val rowTop = keyboardTop
            drawStickers(canvas, left, width - SEARCH_BUTTON_PT * s, rowTop, keysTop)
            panelStickersLeft = left
            glyphPaint.color = withAlpha(theme.text, 0.75f * a)
            glyphPaint.style = Paint.Style.STROKE
            glyphPaint.strokeWidth = 2f * s
            val mx = width - SEARCH_BUTTON_PT * s / 2 - 2f * s
            canvas.drawCircle(mx, cy - 1.5f * s, 6.5f * s, glyphPaint)
            canvas.drawLine(mx + 4.6f * s, cy + 3.1f * s, mx + 9f * s, cy + 7.5f * s, glyphPaint)
            glyphPaint.style = Paint.Style.FILL
        } else if (barPanel == Panel.EMOJI) {
            // iOS's emoji keyboard has a Search Emoji field at the top.
            val left = cx + d / 2 + (10f + 12f * (1f - a)) * s
            val f = searchPill.apply { set(left, cy - 16f * s, width - 8f * s, cy + 16f * s) }
            keyPaint.color = withAlpha(if (barTarget == BarTarget.SEARCH) theme.pressedLetterKey else theme.letterKey, a)
            canvas.drawRoundRect(f, f.height() / 2, f.height() / 2, keyPaint)
            glyphPaint.color = withAlpha(theme.text, 0.6f * a)
            glyphPaint.style = Paint.Style.STROKE
            glyphPaint.strokeWidth = 1.6f * s
            val mx = f.left + 16f * s
            canvas.drawCircle(mx, cy - 1f * s, 5f * s, glyphPaint)
            canvas.drawLine(mx + 3.6f * s, cy + 2.6f * s, mx + 7f * s, cy + 6f * s, glyphPaint)
            glyphPaint.style = Paint.Style.FILL
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 16f * s
            textPaint.color = withAlpha(theme.text, 0.5f * a)
            canvas.drawText("Search Emoji", f.left + 30f * s, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        } else {
            canvas.drawText(title, cx + d / 2 + (12f + 12f * (1f - a)) * s, ty, panelTitlePaint)
        }
        if (barPanel == Panel.CLIPBOARD && clipboard.canClear) {
            panelTitlePaint.textAlign = Paint.Align.RIGHT
            panelTitlePaint.color = withAlpha(if (barTarget == BarTarget.CLEAR) theme.text else theme.accent, a)
            canvas.drawText("Clear", width - 16f * s, ty, panelTitlePaint)
            panelTitlePaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun barTargetAt(x: Float): BarTarget = when {
        x < backCx + backD / 2 + 8f * scale -> BarTarget.BACK
        panel == Panel.CLIPBOARD && clipboard.canClear && x > width - 90f * scale -> BarTarget.CLEAR
        panel == Panel.EMOJI && stickers.isNotEmpty() && x < width - SEARCH_BUTTON_PT * scale -> BarTarget.STICKER
        panel == Panel.EMOJI -> BarTarget.SEARCH
        else -> BarTarget.NONE
    }

    /** The chevron at the left: points right (›) when the buttons are folded, left when they are out. */
    private fun drawChevron(canvas: Canvas, shown: Float) {
        val cy = (keyboardTop + keysTop) / 2
        glyphPaint.color = theme.text
        glyphRect.set(chevronCx - 10f * scale, cy - 10f * scale, chevronCx + 10f * scale, cy + 10f * scale)
        Glyphs.chevron(canvas, glyphRect, glyphPaint, 180f * shown)
    }

    private fun drawToolButtons(canvas: Canvas, shown: Float) {
        val count = canvas.save()
        val drag = touches.values.firstOrNull { it.mode == Mode.TOOL && it.dragging }
        val pressedTool = touches.values.firstOrNull { it.mode == Mode.TOOL && !it.dragging }?.barIndex
        val now = SystemClock.uptimeMillis()
        for ((i, item) in toolbarItems.withIndex()) {
            val r = toolButtonRect(i, tmpRect)
            val slotX = if (drag != null && drag.barIndex == i) drag.lastX else r.centerX()
            // Other buttons glide to their new place when one is dragged past them (and the
            // dragged one glides home when let go).
            val prevX = toolX[item]
            val x = if (prevX == null || (drag != null && drag.barIndex == i) || !animationsOn) slotX
            else prevX + (slotX - prevX) * (1f - kotlin.math.exp(-frameDt / GLIDE_TAU_MS))
            if (kotlin.math.abs(slotX - x) > 0.5f) frameAnimating = true
            toolX[item] = x
            // Folding: each button slides into the chevron, shrinking and fading.
            val cx = lerp(chevronCx, x, shown)
            val cy = r.centerY()
            val k = 0.4f + 0.6f * shown
            if (i == pressedTool || (drag != null && drag.barIndex == i)) {
                keyPaint.color = theme.functionKey
                canvas.drawRoundRect(cx - 18f * scale, cy - 16f * scale, cx + 18f * scale, cy + 16f * scale,
                    theme.keyRadiusPt * scale, theme.keyRadiusPt * scale, keyPaint)
            }
            canvas.save()
            canvas.scale(k, k, cx, cy)
            if (rearranging) {
                // iOS-style wiggle while rearranging.
                canvas.rotate(3f * kotlin.math.sin((now / 70.0 + i * 1.7)).toFloat(), cx, cy)
                frameAnimating = true
            }
            glyphPaint.color = withAlpha(theme.text, shown)
            glyphRect.set(cx - 12f * scale, cy - 12f * scale, cx + 12f * scale, cy + 12f * scale)
            when (item) {
                ToolbarItem.EMOJI -> Glyphs.emoji(canvas, glyphRect, glyphPaint)
                ToolbarItem.CLIPBOARD -> Glyphs.clipboard(canvas, glyphRect, glyphPaint)
                ToolbarItem.TEXT_EDITING -> Glyphs.textEditing(canvas, glyphRect, glyphPaint)
                ToolbarItem.GIF -> Glyphs.gif(canvas, glyphRect, glyphPaint)
                ToolbarItem.VOICE -> Glyphs.mic(canvas, glyphRect, glyphPaint)
            }
            canvas.restore()
        }
        canvas.restoreToCount(count)
    }

    /** The chips or the slots, cross-fading for [BAR_FADE_MS] when the chips come or go. */
    private fun drawBarContent(canvas: Canvas) {
        val p = if (chipsAt == 0L) 1f else progress(chipsAt, BAR_FADE_MS)
        if (p >= 1f) {
            if (chips.isNotEmpty()) drawChips(canvas, chips) else drawSlotsOrStickers(canvas)
            return
        }
        // The outgoing content first, so the incoming chips' rects are the ones left for taps.
        var c = canvas.saveLayerAlpha(0f, keyboardTop, width.toFloat(), keysTop, ((1f - p) * 255).toInt())
        if (prevChips.isNotEmpty()) drawChips(canvas, prevChips) else drawSlotsOrStickers(canvas)
        canvas.restoreToCount(c)
        c = canvas.saveLayerAlpha(0f, keyboardTop, width.toFloat(), keysTop, (p * 255).toInt())
        if (chips.isNotEmpty()) drawChips(canvas, chips) else drawSlotsOrStickers(canvas)
        canvas.restoreToCount(c)
    }

    private fun drawSlotsOrStickers(canvas: Canvas) {
        if (stickers.isNotEmpty()) drawStickers(canvas, stickersLeft, width.toFloat(), keyboardTop, keyboardTop + barPx - resultsRowPx)
        else drawSlots(canvas)
    }

    private val stickersLeft get() = if (toolbarOn) slotsLeft + 2f * scale else 6f * scale
    private val stickerCell get() = (barPx - resultsRowPx - 6f * scale).coerceAtMost(STICKER_PT * scale) + 6f * scale

    /** Emoji Kitchen stickers: a row of pictures between [left] and [right], scrolled sideways. */
    private fun drawStickers(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        val cell = stickerCell
        val size = cell - 6f * scale
        val cy = (top + bottom) / 2
        val pressedIndex = touches.values.firstOrNull { it.mode == Mode.STICKERS && !it.dragging }?.let { stickerAt(it.lastX) } ?: -1
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for ((i, s) in stickers.withIndex()) {
            val x = left + i * cell - stickerScroll
            if (x + cell < left || x > right) continue
            val box = stickerBox.apply { set(x + 3f * scale, cy - size / 2, x + 3f * scale + size, cy + size / 2) }
            if (i == pressedIndex) {
                keyPaint.color = theme.functionKey
                canvas.drawRoundRect(box, 8f * scale, 8f * scale, keyPaint)
            }
            val img = s.image
            val at = stickerAt[s.sticker.url]
            val p = if (img == null) 0f else if (at == null || !animationsOn) 1f
            else ((SystemClock.uptimeMillis() - at) / IMAGE_FADE_MS.toFloat()).coerceIn(0f, 1f)
            if (p < 1f) {
                // Placeholder until the picture arrives, then it fades in over it.
                keyPaint.color = withAlpha(theme.text, 0.08f * (1f - p))
                stickerInner.set(box); stickerInner.inset(4f * scale, 4f * scale)
                canvas.drawRoundRect(stickerInner, 8f * scale, 8f * scale, keyPaint)
                if (img != null) frameAnimating = true
            } else if (at != null) {
                stickerAt.remove(s.sticker.url)
            }
            if (img != null) {
                stickerInner.set(box); stickerInner.inset(2f * scale, 2f * scale)
                thumbPaint.alpha = (p * 255).toInt()
                canvas.drawBitmap(img, null, stickerInner, thumbPaint)
                thumbPaint.alpha = 255
            }
        }
        canvas.restore()
    }

    private val maxStickerScroll get() = (stickers.size * stickerCell - (width - stickersLeft)).coerceAtLeast(0f)

    private fun stickerAt(x: Float): Int {
        val i = ((x - stickersLeft + stickerScroll) / stickerCell).toInt()
        return if (x >= stickersLeft && i in stickers.indices) i else -1
    }

    /** Copied-text chips: the whole clip (with a clipboard icon), then details picked out of it. */
    /**
     * iOS 27's paste suggestion: one item filling the bar, the copied text on top (too long, its
     * start fades out so the end stays readable) and "paste from <App>" in small grey under it.
     * A screenshot gets a small thumbnail before the two lines.
     */
    private fun drawPasteSuggestion(canvas: Canvas, c: Chip) {
        val s = scale
        val r = chipRects.also { while (it.isEmpty()) it.add(RectF()) }[0]
        r.set(slotsLeft, keyboardTop, width.toFloat(), keysTop)
        val pressed = touches.values.any { it.mode == Mode.CHIP }
        val alpha = if (pressed) 0.45f else 1f
        // Appearing: grows in from 88 % on a strong ease-out while the bar cross-fades to it,
        // the quick, settled way iOS brings in new content.
        val popCount = canvas.save()
        val age = AnimationUtils.currentAnimationTimeMillis() - chipsAt
        if (animationsOn && chipsAt != 0L && age < PASTE_POP_MS) {
            val k = 0.88f + 0.12f * STRONG_EASE_OUT.getInterpolation(age / PASTE_POP_MS.toFloat())
            canvas.scale(k, k, width / 2f, r.centerY())
            frameAnimating = true
        }
        val titleSize = 15.5f * s
        val subSize = 11.5f * s
        val img = c.image
        val thumb = if (img != null) r.height() - 12f * s else 0f
        val margin = 14f * s
        textPaint.textSize = titleSize
        val titleW = textPaint.measureText(c.label)
        textPaint.textSize = subSize
        val subW = textPaint.measureText(c.subtitle.orEmpty())
        // Centred on the keyboard (not on the space after the toolbar chevron), with the same
        // room on both sides so it never runs into the chevron.
        val centre = width / 2f
        val half = minOf(centre - slotsLeft, width - centre) - margin
        val room = 2 * half - if (img != null) thumb + 8f * s else 0f
        val textW = minOf(room, maxOf(titleW, subW))
        val groupW = textW + if (img != null) thumb + 8f * s else 0f
        var left = centre - groupW / 2
        if (img != null) {
            val box = chipThumb.apply { set(left, r.centerY() - thumb / 2, left + thumb, r.centerY() + thumb / 2) }
            thumbPaint.alpha = (alpha * 255).toInt()
            drawThumbnail(canvas, img, box, 5f * s)
            thumbPaint.alpha = 255
            left += thumb + 8f * s
        }
        val cx = left + textW / 2
        val titleBase = r.centerY() - 1f * s
        textPaint.textSize = titleSize
        val color = withAlpha(theme.text, alpha)
        // The title may use the whole bar: after the toolbar chevron (and thumbnail) to the right edge.
        val runLeft = maxOf(slotsLeft, margin) + if (img != null) thumb + 8f * s else 0f
        val runW = width - margin - runLeft
        if (titleW <= runW + 0.5f) {
            // Fits: still, centred on the keyboard as far as the chevron allows.
            textPaint.color = color
            textPaint.textAlign = Paint.Align.CENTER
            val x = (if (img != null) cx else width / 2f).coerceIn(runLeft + titleW / 2, runLeft + runW - titleW / 2)
            canvas.drawText(c.label, x, titleBase, textPaint)
        } else {
            // Too long: it scrolls like a song title in Apple Music, in a seamless loop: rests at
            // the start, glides left at an even, slow pace, and the text comes round again after a
            // blank gap. Edges fade where more text is hidden. With animations off it stays put.
            // It runs across the whole bar, so the next round slides in from the keyboard's edge.
            val loop = titleW + MARQUEE_GAP_PT * s
            val travelled = if (animationsOn) marquee() else 0f
            val offset = travelled % loop
            val fade = 24f * s
            // The left fade eases in as the text first starts moving, then stays (the text keeps
            // coming round, so its start never rests at the edge again).
            val c0 = withAlpha(color, 1f - (travelled / fade).coerceIn(0f, 1f))
            textPaint.shader = marqueeShader(runLeft, runW, fade, color, c0)
            textPaint.alpha = Color.alpha(color)
            textPaint.textAlign = Paint.Align.LEFT
            // Fractional pixel positions, so the glide doesn't step a whole pixel at a time.
            textPaint.isSubpixelText = true
            canvas.save()
            canvas.clipRect(runLeft, r.top, runLeft + runW, r.bottom)
            canvas.drawText(c.label, runLeft - offset, titleBase, textPaint)
            // The next round, following after the gap.
            if (offset > 0f) canvas.drawText(c.label, runLeft - offset + loop, titleBase, textPaint)
            canvas.restore()
            textPaint.isSubpixelText = false
            textPaint.shader = null
        }
        c.subtitle?.let { sub ->
            textPaint.textSize = subSize
            textPaint.color = withAlpha(theme.text, alpha * 0.6f)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(ellipsize(sub, textW, 11.5f), cx, titleBase + 15f * s, textPaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        canvas.restoreToCount(popCount)
    }

    /**
     * How far (pixels) a long paste suggestion has scrolled: it rests at the start once, then
     * glides at an even pace forever. Timed by the frame clock, so every frame moves the same.
     */
    private fun marquee(): Float {
        val t = AnimationUtils.currentAnimationTimeMillis() - chipsAt
        if (t < MARQUEE_HOLD_MS) {
            // Resting: no redraws until the glide starts.
            postInvalidateDelayed(MARQUEE_HOLD_MS - t)
            return 0f
        }
        frameAnimating = true
        return (t - MARQUEE_HOLD_MS) * MARQUEE_PT_PER_S * scale / 1000f
    }

    /** The marquee's edge fades, rebuilt only when its place, colour or left fade changes. */
    private fun marqueeShader(left: Float, w: Float, fade: Float, color: Int, leftColor: Int): Shader {
        val key = floatArrayOf(left, w, color.toFloat(), leftColor.toFloat())
        marqueeShader?.let { if (key.contentEquals(marqueeShaderKey)) return it }
        return LinearGradient(left, 0f, left + w, 0f, intArrayOf(leftColor, color, color, color and 0x00FFFFFF),
            floatArrayOf(0f, fade / w, 1f - fade / w, 1f), Shader.TileMode.CLAMP).also {
            marqueeShader = it
            marqueeShaderKey = key
        }
    }
    private var marqueeShader: Shader? = null
    private var marqueeShaderKey = FloatArray(0)

    private fun drawChips(canvas: Canvas, chips: List<Chip>) {
        if (chips.size == 1 && chips[0].subtitle != null) { drawPasteSuggestion(canvas, chips[0]); return }
        // Gboard-style chips: as wide as their content (the copied text capped at 60 % of the
        // bar, details at 50 %), left-aligned, gently rounded, with a small icon or thumbnail.
        // When they don't all fit, the copied-text chip shrinks first (details are what people
        // usually want), then trailing details are left out.
        val s = scale
        val x0 = if (toolbarOn) slotsLeft + 2f * s else 8f * s
        val avail = width - x0 - 8f * s
        val h = minOf(CHIP_H_PT * s, barPx - 10f * s)
        val top = (keyboardTop + keysTop) / 2 - h / 2
        val pad = 10f * s
        val gap = 6f * s
        val iconW = 16f * s
        val iconGap = 5f * s
        val thumb = h - 8f * s
        textPaint.textSize = CHIP_TEXT_PT * s
        while (chipRects.size < chips.size) chipRects.add(RectF())
        val widths = FloatArray(chips.size) { i ->
            val c = chips[i]
            val lead = when {
                c.image != null -> thumb + 8f * s
                c.icon != ChipIcon.NONE -> iconW + iconGap
                else -> 0f
            }
            val natural = pad + lead + textPaint.measureText(c.label) + pad
            minOf(natural, avail * if (i == 0) 0.6f else 0.5f)
        }
        // Too many to fit: the first chip shrinks first, then the last details are left out.
        val total = widths.sum() + gap * (chips.size - 1)
        // Down to just its icon (it still pastes the whole text).
        val iconOnly = pad + iconW + pad
        if (total > avail && chips.size > 1) widths[0] = maxOf(iconOnly, widths[0] - (total - avail))
        val pressedChip = touches.values.firstOrNull { it.mode == Mode.CHIP }?.barIndex
        val radius = CHIP_RADIUS_PT * s
        var x = x0
        for ((i, c) in chips.withIndex()) {
            val r = chipRects[i]
            if (x + widths[i] > x0 + avail + 0.5f) { r.setEmpty(); continue }
            r.set(x, top, x + widths[i], top + h)
            keyPaint.color = if (i == pressedChip) theme.pressedLetterKey else theme.letterKey
            canvas.drawRoundRect(r, radius, radius, keyPaint)
            var textLeft = r.left + pad
            val img = c.image
            if (img != null) {
                val box = chipThumb.apply { set(r.left + 4f * s, r.top + 4f * s, r.left + 4f * s + thumb, r.bottom - 4f * s) }
                drawThumbnail(canvas, img, box, 5f * s)
                textLeft = box.right + 8f * s
            } else if (c.icon != ChipIcon.NONE) {
                glyphPaint.color = theme.text
                val ix = if (r.width() <= iconOnly + 1f) r.centerX() - iconW / 2 else textLeft - 2f * s
                glyphRect.set(ix, r.centerY() - iconW / 2, ix + iconW, r.centerY() + iconW / 2)
                when (c.icon) {
                    ChipIcon.CLIPBOARD -> Glyphs.clipboard(canvas, glyphRect, glyphPaint)
                    ChipIcon.CODE -> Glyphs.hash(canvas, glyphRect, glyphPaint)
                    ChipIcon.LINK -> Glyphs.link(canvas, glyphRect, glyphPaint)
                    ChipIcon.EMAIL -> Glyphs.email(canvas, glyphRect, glyphPaint)
                    ChipIcon.PHONE -> Glyphs.phone(canvas, glyphRect, glyphPaint)
                    ChipIcon.ADDRESS -> Glyphs.pin(canvas, glyphRect, glyphPaint)
                    ChipIcon.NONE -> Unit
                }
                textLeft += iconW + iconGap
            }
            textPaint.color = theme.text
            textPaint.textAlign = Paint.Align.LEFT
            // Labels arrive on one line (the service collapses line breaks and extra spaces).
            val room = r.right - pad - textLeft
            // Too narrow for a few letters: the icon alone, centred.
            if (room < 3 * CHIP_TEXT_PT * s && c.icon != ChipIcon.NONE && c.image == null) {
                if (room < 0f || r.width() <= iconOnly + 1f) {
                    textPaint.textAlign = Paint.Align.CENTER
                    x += widths[i] + gap
                    continue
                }
            }
            val label = ellipsize(c.label, room, CHIP_TEXT_PT)
            val fm = fontMetrics.also(textPaint::getFontMetrics)
            canvas.drawText(label, textLeft, r.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
            x += widths[i] + gap
        }
    }

    /** A screenshot thumbnail, centre-cropped into [box] with rounded corners. */
    private fun drawThumbnail(canvas: Canvas, bmp: android.graphics.Bitmap, box: RectF, radius: Float) {
        val k = maxOf(box.width() / bmp.width, box.height() / bmp.height)
        val w = (box.width() / k).toInt()
        val h = (box.height() / k).toInt()
        val l = (bmp.width - w) / 2
        val t = (bmp.height - h) / 2
        canvas.save()
        thumbPath.reset()
        thumbPath.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.clipPath(thumbPath)
        thumbSrc.set(l, t, l + w, t + h)
        canvas.drawBitmap(bmp, thumbSrc, box, thumbPaint)
        canvas.restore()
    }
    private val thumbPath = Path()
    private val thumbSrc = android.graphics.Rect()
    private val chipThumb = RectF()
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** iOS predictive bar: three slots with thin dividers, the literal word in quotes on the left. */
    private fun drawSlots(canvas: Canvas) {
        // Three equal slots between equal margins: with the toolbar the chevron's width is kept
        // clear on both sides, so the centre word stays centred and the left word never crowds it.
        val x0 = slotsMargin
        val slot = slotWidth
        val pressedSlot = touches.values.firstOrNull { it.mode == Mode.BAR }?.barIndex
        for (i in 0..2) {
            val r = tmpRect.apply { set(x0 + i * slot, keyboardTop, x0 + (i + 1) * slot, keysTop) }
            val s = suggestions[i]
            if (s != null && i == pressedSlot) {
                keyPaint.color = theme.functionKey
                canvas.drawRoundRect(r.left + 4f * scale, r.top + 5f * scale, r.right - 4f * scale, r.bottom - 5f * scale,
                    theme.keyRadiusPt * scale, theme.keyRadiusPt * scale, keyPaint)
            }
            val prev = prevSuggestions[i]
            val p = if (prev == s) 1f else progress(suggestionsAt, BAR_FADE_MS)
            if (prev != null && prev != s && p < 1f) drawSlot(canvas, prev, r, 1f - p)
            if (s != null) drawSlot(canvas, s, r, p)
        }
        keyPaint.color = theme.text and 0x00FFFFFF or 0x40000000
        for (i in 1..2) {
            // A divider only separates two words; an empty bar shows none.
            if (suggestions[i - 1] == null || suggestions[i] == null) continue
            val x = x0 + i * slot
            canvas.drawRect(x, keyboardTop + 12f * scale, x + maxOf(1f, 0.5f * scale), keysTop - 12f * scale, keyPaint)
        }
    }

    /** Side margin of the suggestion slots: the chevron's width when the toolbar is on. */
    private val slotsMargin get() = if (toolbarOn) width * SLOT_MARGIN_FRACTION else 0f
    private val slotWidth get() = (width - 2 * slotsMargin) / 3f

    /** The slot under [x]; the margins count as the nearest slot. */
    private fun slotIndex(x: Float) = ((x - slotsMargin) / slotWidth).toInt().coerceIn(0, 2)

    private fun drawSlot(canvas: Canvas, s: Suggestion, r: RectF, alpha: Float) {
        textPaint.typeface = if (s.bold) BOLD else REGULAR
        // Like iOS, a long word first shrinks (down to 13 pt) to fit its slot, then is shortened
        // in the middle ("anti…ment"), so both its start and its end stay readable.
        val room = r.width() - 2 * SLOT_PAD_PT * scale
        textPaint.textSize = 17f * scale
        val natural = textPaint.measureText(s.display)
        val size = if (natural <= room) 17f else (17f * room / natural).coerceAtLeast(13f)
        drawLabel(canvas, ellipsizeMiddle(s.display, room, size), r, size, withAlpha(theme.text, alpha))
        textPaint.typeface = REGULAR
    }

    /**
     * [s] shortened in the middle ("start…end") to fit [maxPx]: the most characters that fit,
     * split evenly between the start and the end, never splitting a character pair (emoji).
     */
    private fun ellipsizeMiddle(s: String, maxPx: Float, sizePt: Float): String {
        textPaint.textSize = sizePt * scale
        if (s.length > LABEL_MAX_CHARS || textPaint.measureText(s) > maxPx) {
            val room = maxPx - textPaint.measureText("…")
            fun cut(keep: Int): Pair<Int, Int> {
                var head = (keep + 1) / 2
                var tail = s.length - keep / 2
                if (head in 1 until s.length && Character.isHighSurrogate(s[head - 1])) head--
                if (tail in 1 until s.length && Character.isLowSurrogate(s[tail])) tail++
                return head to tail
            }
            fun width(keep: Int) = cut(keep).let { (h, t) -> textPaint.measureText(s, 0, h) + textPaint.measureText(s, t, s.length) }
            var lo = 0
            var hi = minOf(s.length - 1, LABEL_MAX_CHARS)
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (width(mid) <= room) lo = mid else hi = mid - 1
            }
            val (h, t) = cut(lo)
            return s.substring(0, h) + "…" + s.substring(t)
        }
        return s
    }

    /**
     * [s] shortened with "…" to fit [maxPx]. Binary search over the cut point: a long text (a
     * copied 20,000-character document) took 9.5 s when shortened one character at a time.
     */
    private fun ellipsize(s: String, maxPx: Float, sizePt: Float): String {
        textPaint.textSize = sizePt * scale
        // Nothing past this many characters can fit in a bar-sized label anyway.
        val t = if (s.length > LABEL_MAX_CHARS) s.substring(0, LABEL_MAX_CHARS) else s
        if (t.length == s.length && textPaint.measureText(t) <= maxPx) return t
        val room = maxPx - textPaint.measureText("…")
        var lo = 1
        var hi = t.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (textPaint.measureText(t, 0, mid) <= room) lo = mid else hi = mid - 1
        }
        return t.substring(0, lo) + "…"
    }

    /**
     * Draws preview bubbles and accent panels with the keys area starting at [top].
     * Returns true while a popup is still animating in.
     */
    fun drawPopups(canvas: Canvas, top: Float): Boolean {
        if (emojiMode) emoji.drawPopups(canvas, top)
        var animating = drawMenu(canvas, top)
        if (panel != Panel.KEYS) return animating
        for (t in touches.values) {
            val key = t.key ?: continue
            when {
                t.mode == Mode.VARIANTS -> animating = drawVariants(canvas, t, top) or animating
                t.mode == Mode.TAP && key.kind == KeyKind.CHAR && characterPreview ->
                    animating = drawBubble(canvas, key, display(key), top, t.downAt, 0f) or animating
            }
        }
        // Released bubbles shrink and fade away instead of vanishing.
        if (fadingBubbles.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            val it = fadingBubbles.iterator()
            while (it.hasNext()) {
                val b = it.next()
                val p = ((now - b.at) / BUBBLE_RELEASE_MS.toFloat()).coerceIn(0f, 1f)
                if (p >= 1f) { it.remove(); continue }
                drawBubble(canvas, b.key, b.label, top, 0L, easeOut(p))
                animating = true
            }
        }
        return animating
    }

    private fun drawKey(canvas: Canvas, key: Key, pressed: Boolean, labelAlpha: Float) {
        val r = pxInto(key.rect, keyRect)
        val radius = theme.keyRadiusPt * scale
        val function = key.isFunction && !(key.kind == KeyKind.RETURN && returnHighlighted)
        val level = pressLevel(key, pressed)
        // Liquid Glass: a pressed letter key swells slightly and brightens, then eases back.
        val swell = theme.glass && key.kind == KeyKind.CHAR && level > 0f
        if (swell) {
            canvas.save()
            val s = 1f + GLASS_SWELL * level
            canvas.scale(s, s, r.centerX(), r.centerY())
        }
        keyPaint.color = theme.keyShadow
        canvas.drawRoundRect(r.left, r.top + scale, r.right, r.bottom + scale, radius, radius, keyPaint)
        keyPaint.color = when {
            key.kind == KeyKind.RETURN && returnHighlighted -> mix(theme.accent, theme.functionKey, level)
            key.kind == KeyKind.SHIFT && shift != Glyphs.ShiftState.OFF -> theme.letterKey
            // iOS letter keys keep their colour when pressed; the preview bubble shows the press.
            // Glass keys brighten instead, since the bubble alone reads weakly on translucent keys.
            key.kind == KeyKind.CHAR -> if (theme.glass) mix(theme.letterKey, theme.pressedLetterKey, level) else theme.letterKey
            function -> mix(theme.functionKey, theme.pressedFunctionKey, level)
            else -> mix(theme.letterKey, theme.pressedLetterKey, level)
        }
        canvas.drawRoundRect(r, radius, radius, keyPaint)
        if (theme.glass) drawRim(canvas, r, radius)
        if (labelAlpha > 0f) drawKeyLabel(canvas, key, r, pressed, labelAlpha)
        if (swell) canvas.restore()
    }

    /** The keyboard's name on the space bar right after switching (see [showKeyboardName]). */
    private var spaceName: String? = null
    private var spaceNameAt = 0L

    /** Shows [name] on the space bar for a moment, then fades back to "space" (iOS). */
    fun showKeyboardName(name: String) {
        spaceName = name
        spaceNameAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun drawSpaceLabel(canvas: Canvas, r: RectF, fg: Int) {
        val name = spaceName
        val t = SystemClock.uptimeMillis() - spaceNameAt
        if (name == null || t >= SPACE_NAME_MS + SPACE_NAME_FADE_MS) {
            spaceName = null
            drawLabel(canvas, "space", r, 16f, fg)
            return
        }
        if (t < SPACE_NAME_MS || !animationsOn) {
            drawLabel(canvas, name, r, 16f, fg)
            // Wake up for the fade (no redraws while the name just sits there).
            postInvalidateDelayed((SPACE_NAME_MS - t).coerceAtLeast(1L) + if (animationsOn) 0L else SPACE_NAME_FADE_MS)
            return
        }
        // Cross-fade back to "space".
        val p = easeOut(((t - SPACE_NAME_MS) / SPACE_NAME_FADE_MS.toFloat()).coerceIn(0f, 1f))
        drawLabel(canvas, name, r, 16f, withAlpha(fg, 1f - p))
        drawLabel(canvas, "space", r, 16f, withAlpha(fg, p))
        frameAnimating = true
    }

    private fun drawKeyLabel(canvas: Canvas, key: Key, r: RectF, pressed: Boolean, alpha: Float) {
        val fg = withAlpha(if (key.kind == KeyKind.RETURN && returnHighlighted) theme.accentText else theme.text, alpha)
        glyphPaint.color = fg
        val glyphBox = glyphRect.apply {
            set(r.centerX() - 11f * scale, r.centerY() - 11f * scale, r.centerX() + 11f * scale, r.centerY() + 11f * scale)
        }
        when (key.kind) {
            KeyKind.SHIFT -> drawShiftGlyph(canvas, glyphBox, fg)
            KeyKind.DELETE -> Glyphs.delete(canvas, glyphBox, glyphPaint, pressed, keyPaint.color)
            KeyKind.GLOBE -> Glyphs.globe(canvas, glyphBox, glyphPaint)
            KeyKind.EMOJI -> Glyphs.emoji(canvas, glyphBox, glyphPaint)
            KeyKind.SPACE -> drawSpaceLabel(canvas, r, fg)
            KeyKind.RETURN -> drawLabel(canvas, returnLabel, r, 16f, fg)
            KeyKind.CHAR -> {
                drawLabel(canvas, display(key), r, if (key.isLetter) 22.5f else 21f, fg)
                key.hint?.let { drawHint(canvas, it, r, alpha) }
            }
            else -> drawLabel(canvas, key.text, r, 16f, fg)
        }
    }

    /** Gboard-style symbol hint in the keycap's top-right corner. */
    private fun drawHint(canvas: Canvas, hint: String, r: RectF, alpha: Float) {
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 11f * scale
        textPaint.color = withAlpha(theme.text, 0.55f * alpha)
        canvas.drawText(hint, r.right - 4f * scale, r.top + 12f * scale, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** The shift arrow cross-fades between outline, filled and caps-lock forms. */
    private fun drawShiftGlyph(canvas: Canvas, box: RectF, fg: Int) {
        val p = progress(shiftChangedAt, SHIFT_FADE_MS)
        if (p < 1f) {
            glyphPaint.color = withAlpha(fg, 1f - p)
            Glyphs.shift(canvas, box, prevShift, glyphPaint)
        }
        glyphPaint.color = withAlpha(fg, p)
        Glyphs.shift(canvas, box, shift, glyphPaint)
        glyphPaint.color = fg
    }

    // Glass rim: a 1 px highlight that fades out down the key. One shader, positioned per key.
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var rimShader: LinearGradient? = null
    private var rimShaderColor = 0
    private val rimMatrix = Matrix()

    private fun drawRim(canvas: Canvas, r: RectF, radius: Float) {
        if (rimShader == null || rimShaderColor != theme.rim) {
            rimShader = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(theme.rim, theme.rim and 0x00FFFFFF),
                floatArrayOf(0f, 0.45f), Shader.TileMode.CLAMP)
            rimShaderColor = theme.rim
        }
        rimMatrix.setScale(1f, r.height())
        rimMatrix.postTranslate(0f, r.top)
        rimShader!!.setLocalMatrix(rimMatrix)
        rimPaint.shader = rimShader
        rimPaint.strokeWidth = maxOf(1f, 0.6f * scale)
        val inset = rimPaint.strokeWidth / 2
        canvas.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, radius, radius, rimPaint)
    }

    private fun drawLabel(canvas: Canvas, s: String, r: RectF, sizePt: Float, color: Int) {
        textPaint.color = color
        textPaint.textSize = sizePt * scale
        val fm = fontMetrics.also(textPaint::getFontMetrics)
        canvas.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
    }

    private fun display(key: Key) = if (key.isLetter) output(key) else key.label

    private fun output(key: Key) = if (key.isLetter && shift != Glyphs.ShiftState.OFF) key.text.uppercase() else key.text

    /** Balloon above a pressed character key: a wide head joined to the keycap by a neck. */
    private fun balloonPath(key: RectF, head: RectF): Path {
        val rad = 8f * scale
        val neck = 9f * scale
        return balloon.apply {
            reset()
            moveTo(head.left + rad, head.top)
            lineTo(head.right - rad, head.top)
            quadTo(head.right, head.top, head.right, head.top + rad)
            lineTo(head.right, head.bottom - rad)
            cubicTo(head.right, head.bottom, key.right, key.top - neck / 2, key.right, key.top + neck)
            lineTo(key.right, key.bottom - 5f * scale)
            quadTo(key.right, key.bottom, key.right - 5f * scale, key.bottom)
            lineTo(key.left + 5f * scale, key.bottom)
            quadTo(key.left, key.bottom, key.left, key.bottom - 5f * scale)
            lineTo(key.left, key.top + neck)
            cubicTo(key.left, key.top - neck / 2, head.left, head.bottom, head.left, head.bottom - rad)
            lineTo(head.left, head.top + rad)
            quadTo(head.left, head.top, head.left + rad, head.top)
            close()
        }
    }

    /**
     * Grows a menu in from its anchor ([pivotX], [pivotY]): 95 % to 100 % on a strong ease-out.
     * Returns true while still animating.
     */
    private fun springIn(canvas: Canvas, since: Long, pivotX: Float, pivotY: Float): Boolean {
        val p = if (!animationsOn) 1f else ((SystemClock.uptimeMillis() - since) / POPUP_SPRING_MS.toFloat()).coerceIn(0f, 1f)
        // From 95 % on a strong ease-out, no overshoot: a menu that just appears shouldn't bounce.
        val s = 0.95f + 0.05f * STRONG_EASE_OUT.getInterpolation(p)
        canvas.scale(s, s, pivotX, pivotY)
        return p < 1f
    }

    private fun drawBalloon(canvas: Canvas, path: Path, alpha: Float = 1f) {
        keyPaint.color = withAlpha(theme.keyShadow, alpha)
        canvas.save(); canvas.translate(0f, if (theme.glass) 2f * scale else scale); canvas.drawPath(path, keyPaint); canvas.restore()
        keyPaint.color = withAlpha(theme.popup, alpha)
        canvas.drawPath(path, keyPaint)
    }

    /**
     * The preview bubble for [key] showing [label]. [release] > 0 draws it shrinking and fading
     * after the key was let go (0 = fully shown); otherwise glass bubbles spring in from [since].
     */
    private fun drawBubble(canvas: Canvas, key: Key, label: String, top: Float, since: Long, release: Float): Boolean {
        val r = pxInto(key.rect, bubbleKey, top)
        val w = r.width() * 1.55f
        var left = r.centerX() - w / 2
        left = left.coerceIn(2f * scale, width - 2f * scale - w)
        val head = bubbleHead.apply { set(left, r.top - 56f * scale, left + w, r.top - 6f * scale) }
        // Let go: a quick fade so the preview doesn't blink off. The colours fade instead of an
        // offscreen layer, which would be allocated on every key release.
        val alpha = 1f - release
        drawBalloon(canvas, balloonPath(r, head), alpha)
        drawLabel(canvas, label, head, 34f, withAlpha(theme.text, alpha))
        return false
    }

    private val variantKey = RectF()
    private val variantHead = RectF()
    private val variantCell = RectF()

    private fun drawVariants(canvas: Canvas, t: Touch, top: Float): Boolean {
        val v = t.variants ?: return false
        val r = pxInto(t.key!!.rect, variantKey, top)
        // Stored relative to the keys' top so it can be drawn in this view or a popup host.
        val head = variantHead.apply { set(t.variantRect); offset(0f, top) }
        canvas.save()
        // Appears at once, as on iOS.
        val animating = false
        drawBalloon(canvas, balloonPath(r, head))
        val cw = head.width() / v.items.size
        val items = ordered(v)
        for ((i, s) in items.withIndex()) {
            val cell = variantCell.apply { set(head.left + i * cw, head.top, head.left + (i + 1) * cw, head.bottom) }
            var color = theme.text
            if (i == t.variantSelected) {
                keyPaint.color = theme.accent
                canvas.drawRoundRect(cell.left + 2f * scale, cell.top + 4f * scale, cell.right - 2f * scale, cell.bottom - 4f * scale,
                    5f * scale, 5f * scale, keyPaint)
                color = theme.accentText
            }
            drawLabel(canvas, s, cell, 24f, color)
        }
        canvas.restore()
        return animating
    }

    /**
     * The emoji-key menu: an iOS-style list above the key, like the menu of the iOS globe key. It
     * springs in from its anchor while fading in, and after closing shrinks a little and fades out.
     */
    private fun drawMenu(canvas: Canvas, top: Float): Boolean {
        val open = menu
        val items = open ?: menuGone ?: return false
        val selected = if (open != null) menuSelected else menuGoneSelected
        val m = tmpRect.apply { set(menuRect); offset(0f, top) }
        val radius = 12f * scale
        val pivotX = m.left + 20f * scale
        // Fades use a layer only as big as the menu (plus room for its spring overshoot and
        // shadow): a full-window layer every frame made the menu stutter on phones.
        val lb = menuLayer.apply {
            set(m)
            inset(-(m.width() * 0.15f + 6f * scale), -(m.height() * 0.15f + 6f * scale))
        }
        val alpha: Float
        var animating: Boolean
        val layer: Int
        if (open != null) {
            alpha = if (!animationsOn) 1f else ((SystemClock.uptimeMillis() - menuAt) / MENU_FADE_MS.toFloat()).coerceIn(0f, 1f)
            layer = if (alpha < 1f) canvas.saveLayerAlpha(lb, (alpha * 255).toInt()) else canvas.save()
            animating = springIn(canvas, menuAt, pivotX, m.bottom) || alpha < 1f
        } else {
            val p = if (!animationsOn) 1f else ((SystemClock.uptimeMillis() - menuGoneAt) / MENU_OUT_MS.toFloat()).coerceIn(0f, 1f)
            if (p >= 1f) { menuGone = null; return false }
            alpha = 1f - easeOut(p)
            layer = canvas.saveLayerAlpha(lb, (alpha * 255).toInt())
            val s = 1f - 0.08f * easeOut(p)
            canvas.scale(s, s, pivotX, m.bottom)
            animating = true
        }
        keyPaint.color = theme.keyShadow
        canvas.drawRoundRect(m.left, m.top + 2f * scale, m.right, m.bottom + 2f * scale, radius, radius, keyPaint)
        keyPaint.color = theme.popup
        canvas.drawRoundRect(m, radius, radius, keyPaint)
        if (theme.glass) {
            // Liquid Glass: the light rim the keys and notices have.
            glyphPaint.style = Paint.Style.STROKE
            glyphPaint.strokeWidth = maxOf(1f, 0.75f * scale)
            glyphPaint.color = withAlpha(theme.rim, 0.8f)
            canvas.drawRoundRect(m, radius, radius, glyphPaint)
            glyphPaint.style = Paint.Style.FILL
        }
        val rowH = m.height() / items.size
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 16f * scale
        val fm = fontMetrics.also(textPaint::getFontMetrics)
        for ((i, item) in items.withIndex()) {
            val rowTop = m.top + i * rowH
            if (i == selected) {
                keyPaint.color = theme.accent
                canvas.drawRoundRect(m.left + 4f * scale, rowTop + 3f * scale, m.right - 4f * scale, rowTop + rowH - 3f * scale,
                    8f * scale, 8f * scale, keyPaint)
            } else if (i > 0) {
                keyPaint.color = theme.text and 0x00FFFFFF or 0x26000000
                canvas.drawRect(m.left + 14f * scale, rowTop, m.right - 14f * scale, rowTop + maxOf(1f, 0.5f * scale), keyPaint)
            }
            textPaint.color = if (i == selected) theme.accentText else theme.text
            canvas.drawText(item, m.left + 16f * scale, rowTop + rowH / 2 - (fm.ascent + fm.descent) / 2, textPaint)
            if (i == (if (open != null) menuChecked else menuGoneChecked)) {
                // The current keyboard: a checkmark at the row's end.
                glyphPaint.style = Paint.Style.STROKE
                glyphPaint.strokeWidth = 2f * scale
                glyphPaint.strokeCap = Paint.Cap.ROUND
                glyphPaint.strokeJoin = Paint.Join.ROUND
                glyphPaint.color = if (i == selected) theme.accentText else theme.accent
                val cx = m.right - 22f * scale
                val cy = rowTop + rowH / 2
                checkPath.rewind()
                checkPath.moveTo(cx - 6f * scale, cy)
                checkPath.lineTo(cx - 2f * scale, cy + 4.5f * scale)
                checkPath.lineTo(cx + 6f * scale, cy - 5f * scale)
                canvas.drawPath(checkPath, glyphPaint)
                glyphPaint.style = Paint.Style.FILL
            }
        }
        textPaint.textAlign = Paint.Align.CENTER
        canvas.restoreToCount(layer)
        return animating
    }

    private fun openMenu(key: Key) {
        val items = listener.menuItems().takeIf { it.isNotEmpty() } ?: return
        val checked = items.indexOfFirst { it is KeyMenuItem.Keyboard && it.current }
        showMenu(items.map { it.label }, px(key.rect, top = 0f), checked) { listener.onMenu(items[it]) }
        listener.onFeedback(KeyKind.EMOJI)
    }

    /**
     * Opens an iOS-style menu of [labels] above [anchor] (pixels, relative to the keys' top), or
     * below it when there is no room above; [onPick] gets the chosen index.
     */
    fun showMenu(labels: List<String>, anchor: RectF, checked: Int = -1, onPick: (Int) -> Unit) {
        val w = MENU_WIDTH_PT * scale
        val n = labels.size
        val full = MENU_ROW_PT * scale * n
        // Height that fits [room]: full rows, else rows squeezed down to MENU_ROW_MIN_PT, else none.
        fun fit(room: Float) = when {
            full <= room -> full
            MENU_ROW_MIN_PT * scale * n <= room -> room
            else -> -1f
        }
        // The menu stays inside the keyboard window (only it takes touches) and off the anchor,
        // so the finger that opened it isn't on a row: above it, else below it, else beside it.
        val above = fit(anchor.top - 6f * scale + barPx - 4f * scale)
        val below = fit(keysHeightPt * scale - 4f * scale - (anchor.bottom + 6f * scale))
        var left = anchor.left.coerceIn(4f * scale, width - 4f * scale - w)
        val h: Float
        val top: Float
        when {
            above > 0f -> { h = above; top = anchor.top - 6f * scale - h }
            below > 0f -> { h = below; top = anchor.bottom + 6f * scale }
            else -> {
                // Every panel plus several keyboards: the whole keyboard's height, next to the key.
                h = minOf(full, barPx + keysHeightPt * scale - 8f * scale)
                top = keysHeightPt * scale - 4f * scale - h
                left = if (anchor.right + 6f * scale + w <= width - 4f * scale) anchor.right + 6f * scale
                    else (anchor.left - 6f * scale - w).coerceAtLeast(4f * scale)
            }
        }
        menuRect.set(left, top, left + w, top + h)
        menu = labels
        menuChecked = checked
        menuGone = null
        menuAction = onPick
        menuSelected = -1
        menuAt = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Load the emoji panel's data in the background before it is first opened. */
    fun prewarmEmoji() = emoji.prewarm()

    fun closeMenu() {
        if (menu == null) return
        menuGone = menu
        menuGoneSelected = menuSelected
        menuGoneChecked = menuChecked
        menuGoneAt = SystemClock.uptimeMillis()
        menu = null
        menuAction = null
        invalidate()
    }

    /** Menu row under a view point, or -1. */
    private fun menuRowAt(x: Float, y: Float): Int {
        val items = menu ?: return -1
        val ry = y - keysTop
        if (x < menuRect.left || x > menuRect.right || ry < menuRect.top || ry >= menuRect.bottom) return -1
        return ((ry - menuRect.top) / (menuRect.height() / items.size)).toInt().coerceIn(0, items.size - 1)
    }

    /** Long-press choices: the key's hint symbol first (Gboard layout), then its accents. */
    private fun variantsFor(key: Key): Variants? {
        val accents = Accents.forOutput(output(key))
        val hint = key.hint ?: return accents
        return Variants((listOf(hint) + (accents?.items ?: listOf(output(key)))).distinct(), false)
    }

    /** Left-opening panels show the list right-to-left so the base character sits over the key. */
    private fun ordered(v: Variants) = if (v.opensLeft) v.items.reversed() else v.items

    // ---- touch ---------------------------------------------------------------------------

    private fun keyAt(xPx: Float, yPx: Float): Key? {
        val x = (xPx / scale - hOffset) / hScale
        val y = (yPx - keysTop) / scale
        if (y < -8f) return null
        val keys = spec.keys(plane)
        val centers = spec.rowCenters(plane)
        // Rows split halfway between row centres; inside a row the nearest key wins.
        val row = centers.indices.minByOrNull { abs(centers[it] - y) } ?: return null
        return keys.filter { it.row == row }.minByOrNull { k ->
            when {
                x < k.rect.left -> k.rect.left - x
                x > k.rect.right -> x - k.rect.right
                else -> 0f
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            // A panel's bar: back (and Clear for the clipboard).
            barGesture = panel != Panel.KEYS && menu == null && e.y >= keyboardTop && e.y < keysTop
            if (barGesture) {
                barTarget = barTargetAt(e.x)
                if (barTarget != BarTarget.NONE) listener.onFeedback(KeyKind.CHAR)
            }
            panelGesture = panel != Panel.KEYS && menu == null && e.y >= keysTop && e.y < keysTop + keysHeightPt * scale
        }
        if (barGesture) {
            when (e.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    val target = barTarget
                    barTarget = BarTarget.NONE
                    barGesture = false
                    if (target != BarTarget.NONE && barTargetAt(e.x) == target && e.y < keysTop + 12f * scale) when (target) {
                        BarTarget.BACK -> panel = Panel.KEYS
                        BarTarget.CLEAR -> clipboard.clear()
                        BarTarget.SEARCH -> listener.onEmojiSearch()
                        BarTarget.STICKER -> {
                            val i = ((e.x - panelStickersLeft) / stickerCell).toInt()
                            stickers.getOrNull(i)?.let { listener.onSticker(it.sticker) }
                        }
                        BarTarget.NONE -> Unit
                    }
                }
                MotionEvent.ACTION_CANCEL -> { barTarget = BarTarget.NONE; barGesture = false }
            }
            invalidate()
            return true
        }
        if (panelGesture) {
            when (panel) {
                Panel.EMOJI -> emoji.onTouch(e)
                Panel.CLIPBOARD -> clipboard.onTouch(e)
                Panel.EDITING -> editing.onTouch(e)
                Panel.GIF -> gifs.onTouch(e)
                Panel.VOICE -> voice.onTouch(e)
                Panel.KEYS -> Unit
            }
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) panelGesture = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Let the overlay grow now (only as far as this key's popup will reach above the
                // keyboard, if at all), and drop any bubble still fading from the last key (iOS
                // shows one preview at a time, instantly).
                if (panel == Panel.KEYS && !emojiMode) {
                    keyAt(e.getX(i), e.getY(i))?.let { k -> headroomFor(k).let { h -> if (h > 0f) popupHost?.hold(h) } }
                }
                // Emoji: the skin-tone panel opens only after a long press; the overlay makes room
                // when it is drawn, not on every tap.
                fadingBubbles.clear()
                // Rollover typing: a new finger commits any character still held.
                for (t in touches.values.toList()) if (t.mode == Mode.TAP && t.key?.kind == KeyKind.CHAR) finish(t, commit = true)
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (p in 0 until e.pointerCount) touches[e.getPointerId(p)]?.let { move(it, e.getX(p), e.getY(p)) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> touches[e.getPointerId(i)]?.let { finish(it, commit = true) }
            MotionEvent.ACTION_CANCEL -> for (t in touches.values.toList()) finish(t, commit = false)
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (oneHandedStrip()?.contains(x, y) == true) {
            listener.onFeedback(KeyKind.TO_LETTERS)
            listener.onOneHandedOff()
            return
        }
        if (barPt > 0f && y >= keyboardTop && y < keysTop && panel == Panel.KEYS && menu == null) {
            barDown(id, x, y)
            return
        }
        if (menu != null) {
            // A menu left open after a plain hold: tap an item to choose it, anywhere else to close.
            val row = menuRowAt(x, y)
            if (row < 0) { closeMenu(); return }
            menuSelected = row
            touches[id] = Touch(id, null, x, y).apply { mode = Mode.MENU }
            return
        }
        if (panel != Panel.KEYS) return
        // Typing closes the toolbar dropdown and ends rearranging.
        toolbarExpanded = false
        rearranging = false
        val key = keyAt(x, y) ?: return
        val t = Touch(id, key, x, y)
        touches[id] = t
        listener.onFeedback(key.kind)
        when (key.kind) {
            KeyKind.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                val next = when {
                    !capsLockEnabled -> if (shift == Glyphs.ShiftState.OFF) Glyphs.ShiftState.ON else Glyphs.ShiftState.OFF
                    shift == Glyphs.ShiftState.OFF && now - lastShiftTap < DOUBLE_TAP_MS -> Glyphs.ShiftState.LOCKED
                    shift == Glyphs.ShiftState.OFF -> Glyphs.ShiftState.ON
                    shift == Glyphs.ShiftState.ON && now - lastShiftTap < DOUBLE_TAP_MS -> Glyphs.ShiftState.LOCKED
                    else -> Glyphs.ShiftState.OFF
                }
                lastShiftTap = now
                setShiftState(next)
                listener.onShiftChanged(next)
            }
            KeyKind.DELETE -> {
                t.mode = Mode.DELETE_REPEAT
                listener.onDelete(0)
                handler.postDelayed(t.repeat, REPEAT_DELAY_MS)
            }
            KeyKind.TO_NUMBERS, KeyKind.TO_SYMBOLS, KeyKind.TO_LETTERS -> {
                // iOS switches planes on touch down, so a finger can slide onto the new keys.
                t.slideFromPlane = plane
                setPlane(targetPlane(key.kind))
            }
            KeyKind.SPACE -> handler.postDelayed(t.longPress, longPressMs + TRACKPAD_EXTRA_MS)
            KeyKind.EMOJI -> handler.postDelayed(t.longPress, longPressMs)
            KeyKind.CHAR -> if (variantsFor(key) != null) handler.postDelayed(t.longPress, longPressMs)
            KeyKind.RETURN, KeyKind.GLOBE -> Unit
        }
    }

    /**
     * GIF search being typed: a back arrow (to the GIF panel) and a rounded field with a
     * magnifier, the text and a caret, in place of the suggestions.
     */
    private fun drawSearchField(canvas: Canvas, q: String) {
        val s = scale
        val rowBottom = keysTop - resultsRowPx
        val cy = (keyboardTop + rowBottom) / 2
        glyphPaint.color = theme.text
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 2f * s
        glyphPaint.strokeCap = Paint.Cap.ROUND
        val ax = chevronCx
        canvas.drawLine(ax - 7f * s, cy, ax + 7f * s, cy, glyphPaint)
        canvas.drawLine(ax - 7f * s, cy, ax - 1f * s, cy - 6f * s, glyphPaint)
        canvas.drawLine(ax - 7f * s, cy, ax - 1f * s, cy + 6f * s, glyphPaint)
        val field = tmpRect.apply { set(width * CHEVRON_FRACTION, keyboardTop + 6f * s, width - 8f * s, rowBottom - 6f * s) }
        keyPaint.color = theme.letterKey
        canvas.drawRoundRect(field, field.height() / 2, field.height() / 2, keyPaint)
        glyphPaint.strokeWidth = 1.6f * s
        glyphPaint.color = withAlpha(theme.text, 0.6f)
        val mx = field.left + 16f * s
        canvas.drawCircle(mx, cy - 1f * s, 5f * s, glyphPaint)
        canvas.drawLine(mx + 3.6f * s, cy + 2.6f * s, mx + 7f * s, cy + 6f * s, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 16f * s
        val fm = fontMetrics.also(textPaint::getFontMetrics)
        val tx = field.left + 30f * s
        val baseline = cy - (fm.ascent + fm.descent) / 2
        val placeholder = if (searchKind == SearchKind.EMOJI) "Search Emoji" else "Search GIPHY"
        val shown = if (q.isEmpty()) placeholder else ellipsizeStart(q, field.right - 12f * s - tx)
        textPaint.color = if (q.isEmpty()) withAlpha(theme.text, 0.5f) else theme.text
        canvas.drawText(shown, tx, baseline, textPaint)
        // A blinking caret after the text (or at the start of the placeholder).
        if ((SystemClock.uptimeMillis() / CARET_BLINK_MS) % 2 == 0L) {
            val cx = if (q.isEmpty()) tx else tx + textPaint.measureText(shown)
            keyPaint.color = theme.accent
            canvas.drawRect(cx + 1f * s, cy - 9f * s, cx + 3f * s, cy + 9f * s, keyPaint)
        }
        postInvalidateDelayed(CARET_BLINK_MS)
        textPaint.textAlign = Paint.Align.CENTER
        if (resultsRowPx > 0f) drawSearchResults(canvas, rowBottom, keysTop)
    }

    /** Emoji search results: a row that scrolls sideways; with nothing typed, recent emoji. */
    private fun drawSearchResults(canvas: Canvas, top: Float, bottom: Float) {
        val s = scale
        val cell = RESULT_CELL_PT * s
        val cy = (top + bottom) / 2
        val pressedIndex = touches.values.firstOrNull { it.mode == Mode.RESULTS && !it.dragging }?.let { resultAt(it.lastX) } ?: -1
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        if (searchResults.isEmpty() && !searchText.isNullOrEmpty()) {
            textPaint.textSize = 15f * s
            textPaint.color = withAlpha(theme.text, 0.5f)
            canvas.drawText("No Emoji Found", width / 2f, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
        for ((i, e) in searchResults.withIndex()) {
            val cx = RESULTS_PAD_PT * s + i * cell + cell / 2 - resultsScroll
            if (cx < -cell || cx > width + cell) continue
            if (i == pressedIndex) {
                keyPaint.color = theme.functionKey
                canvas.drawRoundRect(cx - cell / 2 + 2f * s, top + 3f * s, cx + cell / 2 - 2f * s, bottom - 3f * s, 8f * s, 8f * s, keyPaint)
            }
            emoji.drawEmojiAt(canvas, e, cx, cy, 26f * s)
        }
        canvas.restore()
    }

    private val maxResultsScroll get() = (RESULTS_PAD_PT * 2 * scale + searchResults.size * RESULT_CELL_PT * scale - width).coerceAtLeast(0f)

    private fun resultAt(x: Float): Int {
        val i = ((x + resultsScroll - RESULTS_PAD_PT * scale) / (RESULT_CELL_PT * scale)).toInt()
        return if (x + resultsScroll >= RESULTS_PAD_PT * scale && i in searchResults.indices) i else -1
    }

    /** Keeps the end of a long search visible ("…long search"). */
    private fun ellipsizeStart(s: String, maxPx: Float): String {
        if (textPaint.measureText(s) <= maxPx) return s
        // Binary search for the first character to keep (see ellipsize).
        val room = maxPx - textPaint.measureText("…")
        var lo = 0
        var hi = s.length - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (textPaint.measureText(s, mid, s.length) <= room) hi = mid else lo = mid + 1
        }
        return "…" + s.substring(lo)
    }

    private fun barDown(id: Int, x: Float, y: Float) {
        if (searchText != null) {
            // The results row (emoji search): tap one, or drag the row sideways.
            if (resultsRowPx > 0f && y >= keysTop - resultsRowPx) {
                touches[id] = Touch(id, null, x, y).apply { mode = Mode.RESULTS }
                return
            }
            // Otherwise only the back arrow does anything while a search is typed.
            if (x < width * CHEVRON_FRACTION) {
                listener.onFeedback(KeyKind.TO_LETTERS)
                listener.onSearchCancel()
            }
            return
        }
        if (toolbarOn && x < slotsLeft) {
            rearranging = false
            listener.onFeedback(KeyKind.TO_LETTERS)
            if (hasBarContent) toolbarExpanded = !toolbarExpanded else listener.onEmptyBarChevron()
            return
        }
        if (toolbarOn && tbTarget == 1f) {
            val i = toolButtonAt(x)
            if (i >= 0) {
                val t = Touch(id, null, x, y).apply { mode = Mode.TOOL; barIndex = i }
                touches[id] = t
                listener.onFeedback(KeyKind.EMOJI)
                if (rearranging) t.dragging = true else handler.postDelayed(t.longPress, longPressMs)
                return
            }
            if (rearranging) { rearranging = false; return }
            if (toolbarExpanded) { toolbarExpanded = false; return }
            return
        }
        rearranging = false
        if (chips.isEmpty() && stickers.isNotEmpty()) {
            if (x >= stickersLeft) touches[id] = Touch(id, null, x, y).apply { mode = Mode.STICKERS }
            return
        }
        if (chips.isNotEmpty()) {
            val i = chipAt(x)
            if (i >= 0) {
                touches[id] = Touch(id, null, x, y).apply { mode = Mode.CHIP; barIndex = i }
                listener.onFeedback(KeyKind.CHAR)
            }
            return
        }
        val i = slotIndex(x)
        if (suggestions[i] != null) {
            touches[id] = Touch(id, null, x, y).apply { mode = Mode.BAR; barIndex = i }
            listener.onFeedback(KeyKind.CHAR)
        }
    }

    private fun targetPlane(kind: KeyKind) = when (kind) {
        KeyKind.TO_NUMBERS -> Plane.NUMBERS
        KeyKind.TO_SYMBOLS -> Plane.SYMBOLS
        else -> Plane.LETTERS
    }

    private fun move(t: Touch, x: Float, y: Float) {
        when (t.mode) {
            Mode.TRACKPAD -> {
                val step = 9f * scale
                val dx = ((x - t.lastX) / step).toInt()
                val dy = ((y - t.lastY) / (step * 2.4f)).toInt()
                if (dx != 0 || dy != 0) {
                    listener.onCursorMove(dx, dy)
                    if (dx != 0) t.lastX += dx * step
                    if (dy != 0) t.lastY += dy * step * 2.4f
                }
            }
            Mode.VARIANTS -> {
                val v = t.variants ?: return
                val cw = t.variantRect.width() / v.items.size
                val steps = floor((x - t.variantAnchorX) / cw + 0.5f).toInt()
                t.variantSelected = (t.variantBase + steps).coerceIn(0, v.items.size - 1)
            }
            Mode.DELETE_REPEAT -> Unit
            Mode.MENU -> {
                t.lastX = x
                t.lastY = y
                menuSelected = menuRowAt(x, y)
            }
            Mode.BAR, Mode.CHIP -> t.lastX = x
            Mode.RESULTS -> {
                if (!t.dragging && kotlin.math.abs(x - t.downX) > ViewConfiguration.get(context).scaledTouchSlop) t.dragging = true
                if (t.dragging) resultsScroll = (resultsScroll - (x - t.lastX)).coerceIn(0f, maxResultsScroll)
                t.lastX = x
            }
            Mode.STICKERS -> {
                if (!t.dragging && kotlin.math.abs(x - t.downX) > ViewConfiguration.get(context).scaledTouchSlop) t.dragging = true
                if (t.dragging) stickerScroll = (stickerScroll - (x - t.lastX)).coerceIn(0f, maxStickerScroll)
                t.lastX = x
            }
            Mode.TOOL -> {
                t.lastX = x
                if (t.dragging && t.barIndex in toolbarItems.indices) {
                    // Drop position follows the finger; the other buttons make room.
                    val first = toolButtonRect(0, tmpRect).left
                    val target = ((x - first) / toolSlotW).toInt().coerceIn(0, toolbarItems.size - 1)
                    if (target != t.barIndex) {
                        toolbarItems = toolbarItems.toMutableList().apply { add(target, removeAt(t.barIndex)) }
                        t.barIndex = target
                    }
                }
            }
            Mode.SWIPE -> t.trail.add(Triple(x, y, SystemClock.uptimeMillis()))
            Mode.TAP -> {
                t.lastX = x
                t.lastY = y
                if (t.key?.kind == KeyKind.SPACE || t.key?.kind == KeyKind.SHIFT || t.key?.kind == KeyKind.EMOJI) return
                // Moving off a letter before the long-press fires starts slide-to-type.
                val start = t.key
                if (slideToType && plane == Plane.LETTERS && touches.size == 1 && start != null && start.isLetter &&
                    Math.hypot((x - t.downX).toDouble(), (y - t.downY).toDouble()) > SWIPE_START_PT * scale
                ) {
                    handler.removeCallbacks(t.longPress)
                    t.mode = Mode.SWIPE
                    t.trail.add(Triple(t.downX, t.downY, t.downAt))
                    t.trail.add(Triple(x, y, SystemClock.uptimeMillis()))
                    return
                }
                val k = keyAt(x, y)
                if (k != t.key) {
                    handler.removeCallbacks(t.longPress)
                    t.key = k
                    t.downAt = SystemClock.uptimeMillis()
                    if (k?.kind == KeyKind.CHAR && variantsFor(k) != null) handler.postDelayed(t.longPress, longPressMs)
                }
            }
        }
    }

    private fun onLongPress(t: Touch) {
        if (t.mode == Mode.TOOL) {
            // Hold a toolbar button to rearrange them, like iOS home-screen editing.
            rearranging = true
            t.dragging = true
            listener.onFeedback(KeyKind.EMOJI)
            invalidate()
            return
        }
        val key = t.key ?: return
        when (key.kind) {
            KeyKind.SPACE -> {
                t.mode = Mode.TRACKPAD
                listener.onFeedback(KeyKind.SPACE)
            }
            KeyKind.EMOJI -> {
                openMenu(key)
                if (menu != null) t.mode = Mode.MENU
            }
            KeyKind.CHAR -> {
                val v = variantsFor(key) ?: return
                val r = px(key.rect, top = 0f)
                val cw = minOf(34f * scale, (width - 8f * scale) / v.items.size)
                val w = cw * v.items.size
                // A hinted key (Gboard layout) opens with its symbol selected, as in Gboard.
                val baseIndex = if (key.hint != null) 0 else ordered(v).indexOf(output(key)).coerceAtLeast(0)
                var left = r.centerX() - cw * (baseIndex + 0.5f)
                left = left.coerceIn(4f * scale, width - 4f * scale - w)
                t.variantRect = RectF(left, r.top - 56f * scale, left + w, r.top - 6f * scale)
                t.variants = v
                t.variantBase = baseIndex
                t.variantSelected = baseIndex
                t.variantAnchorX = t.lastX
                t.variantsAt = SystemClock.uptimeMillis()
                t.mode = Mode.VARIANTS
            }
            else -> Unit
        }
        invalidate()
    }

    private fun finish(t: Touch, commit: Boolean) {
        touches.remove(t.id)
        handler.removeCallbacks(t.longPress)
        handler.removeCallbacks(t.repeat)
        if (commit && t.mode == Mode.SWIPE) {
            listener.onSwipe(t.trail.map { (x, y, _) -> layoutPoint(x, y) })
            fadingTrail = t.trail
            postInvalidateOnAnimation()
            return
        }
        if (t.mode == Mode.TOOL) {
            if (t.dragging) {
                // Saved after this touch is done, so settings don't change mid-dispatch.
                val order = toolbarItems
                post { listener.onToolbarOrder(order) }
            } else if (commit && !rearranging && toolButtonAt(t.lastX) == t.barIndex) {
                toolbarItems.getOrNull(t.barIndex)?.let { item ->
                    toolbarExpanded = false
                    listener.onToolbar(item)
                }
            }
            invalidate()
            return
        }
        if (t.mode == Mode.CHIP) {
            if (commit && chipAt(t.lastX) == t.barIndex) listener.onChip(t.barIndex)
            invalidate()
            return
        }
        if (t.mode == Mode.MENU) {
            val items = menu
            val chosen = if (commit && items != null && menuSelected >= 0) menuSelected else -1
            when {
                chosen >= 0 -> { val action = menuAction; closeMenu(); action?.invoke(chosen) }
                // Released on the emoji key without choosing: keep it open for a tap, like iOS.
                commit && t.key != null && keyAt(t.lastX, t.lastY) == t.key -> menuSelected = -1
                else -> closeMenu()
            }
            invalidate()
            return
        }
        if (commit && t.mode == Mode.STICKERS && !t.dragging) {
            val i = stickerAt(t.lastX)
            if (i >= 0) { listener.onFeedback(KeyKind.CHAR); listener.onSticker(stickers[i].sticker) }
        }
        if (commit && t.mode == Mode.RESULTS && !t.dragging) {
            val i = resultAt(t.lastX)
            if (i >= 0) { listener.onFeedback(KeyKind.CHAR); listener.onEmojiSearchPick(searchResults[i]) }
        }
        if (commit && t.mode == Mode.BAR) {
            // Only commit if the finger was released over the same slot.
            val i = slotIndex(t.lastX)
            if (i == t.barIndex) listener.onSuggestion(i)
        }
        val key = t.key
        if (commit && key != null && t.mode == Mode.TAP && key.kind == KeyKind.CHAR && characterPreview && animationsOn) {
            fadingBubbles.add(FadingBubble(key, display(key), SystemClock.uptimeMillis()))
        }
        if (commit && key != null) {
            when (t.mode) {
                Mode.VARIANTS -> t.variants?.let { listener.onText(ordered(it)[t.variantSelected], literal = true) }
                Mode.TRACKPAD, Mode.DELETE_REPEAT, Mode.BAR, Mode.SWIPE, Mode.MENU, Mode.TOOL, Mode.CHIP, Mode.RESULTS, Mode.STICKERS -> Unit
                Mode.TAP -> when (key.kind) {
                    KeyKind.CHAR -> {
                        val r = px(key.rect, keysTop)
                        listener.onCharTap((t.lastX - r.centerX()) / r.width(), (t.lastY - r.centerY()) / r.height())
                        listener.onText(output(key))
                    }
                    KeyKind.SPACE -> listener.onSpace()
                    KeyKind.RETURN -> listener.onReturn()
                    KeyKind.GLOBE -> Unit
                    KeyKind.EMOJI -> emojiMode = true
                    KeyKind.TO_NUMBERS, KeyKind.TO_SYMBOLS, KeyKind.TO_LETTERS -> Unit
                    KeyKind.SHIFT, KeyKind.DELETE -> Unit
                }
            }
            // Slid off 123 onto a character: type it and go back, as iOS does.
            val from = t.slideFromPlane
            if (from != null && key.kind == KeyKind.CHAR) setPlane(from)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (t in touches.values.toList()) finish(t, commit = false)
    }

    companion object {
        /** Transparent space above the keys for preview bubbles, in points. */
        const val HEADROOM = 62f
        /** Preview bubbles and accent panels reach this far above their key's top. */
        private const val POPUP_RISE_PT = 56f
        /** Room for the popup's shadow above it. */
        private const val POPUP_SHADOW_PT = 4f
        private const val ONE_HANDED_SCALE = 0.86f
        private const val POPUP_SPRING_MS = 180L
        /** cubic-bezier(0.23, 1, 0.32, 1): a strong ease-out for things appearing. */
        private val STRONG_EASE_OUT = android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)
        /** Glides that follow a target (toolbar buttons): time constant of the exponential ease. */
        private const val GLIDE_TAU_MS = 47f
        // iOS motion timings (matched by eye to iOS recordings).
        private const val TRACKPAD_FADE_MS = 150L
        private const val KEY_RELEASE_MS = 100L
        private const val GLASS_PRESS_MS = 80L
        private const val GLASS_RELEASE_MS = 180L
        private const val GLASS_SWELL = 0.04f
        private const val BAR_FADE_MS = 120L
        private const val ONE_HANDED_MS = 250L
        private const val SHIFT_FADE_MS = 100L
        private const val BUBBLE_RELEASE_MS = 60L
        private const val GLASS_CORNER_PT = 20f
        private const val MENU_WIDTH_PT = 200f
        /** Toolbar, as fractions of the width (Gboard's spacing: first icon at 6 %, then every 22 %). */
        /** Suggestion slots: side margin with the toolbar on (clear of the chevron), word padding. */
        private const val SLOT_MARGIN_FRACTION = 0.09f
        private const val SLOT_PAD_PT = 8f
        private const val CHEVRON_FRACTION = 0.12f
        private const val CHEVRON_CENTER_FRACTION = 0.06f
        /** iOS context menus fade in with their spring and fade out quicker than they came. */
        private const val MENU_FADE_MS = 100L
        private const val MENU_OUT_MS = 150L
        /** Stroke of the toolbar chevron as Glyphs.chevron draws it in a 20 pt box (1.6 / 24 * 20). */
        private const val CHEVRON_STROKE_PT = 1.33f
        private const val CARET_BLINK_MS = 530L
        /** Copied-item chips: height, corner and text size. */
        private const val CHIP_H_PT = 32f
        private const val CHIP_RADIUS_PT = 8f
        private const val CHIP_TEXT_PT = 13.5f
        /** Emoji search results row: height, cell width, side padding. */
        private const val RESULTS_ROW_PT = 44f
        private const val RESULT_CELL_PT = 44f
        private const val RESULTS_PAD_PT = 6f
        /** Emoji Kitchen stickers in the bar: picture size; the emoji panel's search button width. */
        private const val STICKER_PT = 36f
        private const val SEARCH_BUTTON_PT = 40f
        /** Pictures (stickers) fade in when they arrive. */
        private const val IMAGE_FADE_MS = 150L
        /** In-keyboard notices: on screen this long, fading in and out. */
        private const val NOTICE_MS = 2200L
        private const val NOTICE_FADE_IN_MS = 160L
        private const val NOTICE_FADE_OUT_MS = 260L
        /** Labels (chips, slots) never need more than this many characters. */
        private const val LABEL_MAX_CHARS = 300
        private const val PANEL_MS = 260L
        /** How far a panel (or the keys) rises while fading in. */
        private const val PANEL_SLIDE_PT = 18f
        /** Bar height for a panel when the keys have no bar (as BAR_HEIGHT_PT in the service). */
        private const val PANEL_BAR_PT = 44f
        private const val TOOL_FRACTION = 0.22f
        private const val TOOLBAR_MS = 220L
        private const val MENU_ROW_PT = 40f
        /** The keyboard's name stays on the space bar this long after switching, then fades. */
        private const val SPACE_NAME_MS = 1200L
        private const val SPACE_NAME_FADE_MS = 250L
        /** Long paste suggestions scroll at this even pace, after resting at the start once. */
        private const val MARQUEE_PT_PER_S = 25f
        private const val MARQUEE_HOLD_MS = 2000L
        /** A paste suggestion grows in over this long when it appears. */
        private const val PASTE_POP_MS = 350L
        /** Blank space before the scrolling text comes round again (Apple Music style, no divider). */
        private const val MARQUEE_GAP_PT = 40f
        /** Rows are squeezed down to this (no further) when a long menu doesn't fit. */
        private const val MENU_ROW_MIN_PT = 30f
        /** Distance a finger must travel from a letter to start slide-to-type (0.6 key pitch). */
        private const val SWIPE_START_PT = 22.5f
        private const val TRAIL_FADE_MS = 250L
        private val REGULAR = Typeface.create("sans-serif", Typeface.NORMAL)
        private val BOLD = Typeface.create("sans-serif", Typeface.BOLD)
        /** The space bar waits slightly longer than other keys before becoming a trackpad. */
        private const val TRACKPAD_EXTRA_MS = 50L
        private const val DOUBLE_TAP_MS = 350L
        /** Key repeat (delete, and later the editing arrows): first repeat, then the interval. */
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 60L
    }
}
