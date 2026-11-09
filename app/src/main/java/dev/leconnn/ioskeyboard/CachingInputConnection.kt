package dev.leconnn.ioskeyboard

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * An [InputConnection] that remembers the text before the cursor, so that the many small reads
 * per key (auto-capitalisation, smart punctuation, suggestions, delete) cost one round trip to the
 * app instead of several. Every read is a blocking call into the app's process, and slow apps made
 * typing and especially holding delete lag.
 *
 * Our own edits update the cached text in place (commit, composing, delete before the cursor);
 * anything else (key events, editor actions, selection changes) drops it. [onSelectionUpdate]
 * drops it too when the app reports a cursor position we didn't expect, e.g. after the app
 * changed the text itself. Gboard does the equivalent with timeouts on its connection calls.
 */
class CachingInputConnection(val target: InputConnection) : InputConnectionWrapper(target, false) {

    /** Up to [WINDOW] characters before the cursor, or null when unknown. */
    private var cache: String? = null
    /** True when [cache] reaches the start of the field (the app had fewer than [WINDOW]). */
    private var complete = false
    /** Length of the composing text at the end of [cache] (it is part of the text before the cursor). */
    private var composingLength = 0
    /** Where the cursor should be after our edits, or -1 when unknown. */
    private var expectedCursor = -1
    /** The selection the app last reported. */
    private var lastSelStart = -1
    private var lastSelEnd = -1

    // An inline prediction: grey text after the cursor that is part of the composing text but not
    // yet typed. [ghostWord] is the typed part, [ghostLength] the grey part's length, [ghostStart]
    // where the composing text starts.
    private var ghostWord: CharSequence? = null
    private var ghostLength = 0
    private var ghostStart = -1
    /** True while an inline prediction is showing after the cursor. */
    val hasGhost get() = ghostWord != null

    fun invalidate() {
        cache = null
        expectedCursor = -1
    }

    /** The app reported the selection; keep the cache only if it matches what our edits predicted. */
    fun onSelectionUpdate(selStart: Int, selEnd: Int) {
        if (selStart != selEnd || selStart != expectedCursor) cache = null
        expectedCursor = if (selStart == selEnd) selStart else -1
        lastSelStart = selStart
        lastSelEnd = selEnd
    }

    /**
     * Shows [text] as the composing text with the cursor before its last [ghost] characters (an
     * inline prediction drawn in grey). Returns false, changing nothing, when the cursor position
     * isn't known.
     */
    fun setComposingWithGhost(text: CharSequence, ghost: Int): Boolean {
        if (expectedCursor < 0 || ghost <= 0 || ghost >= text.length) return false
        val typed = text.subSequence(0, text.length - ghost)
        replaceComposing(typed, composingAfter = typed.length)
        ghostWord = typed
        ghostLength = ghost
        ghostStart = expectedCursor - typed.length
        super.beginBatchEdit()
        super.setComposingText(text, 1)
        super.setSelection(expectedCursor, expectedCursor)
        super.endBatchEdit()
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        if (flags != 0 || n > WINDOW) return super.getTextBeforeCursor(n, flags)
        val c = cache
        if (c != null && (c.length >= n || complete)) return c.takeLast(n)
        val fetched = super.getTextBeforeCursor(WINDOW, 0)?.toString() ?: return null
        cache = fetched
        complete = fetched.length < WINDOW
        return fetched.takeLast(n)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (newCursorPosition == 1) replaceComposing(text, composingAfter = 0) else invalidate()
        composingLength = 0
        ghostWord = null
        return super.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (newCursorPosition == 1) replaceComposing(text, composingAfter = text.length) else invalidate()
        ghostWord = null
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        composingLength = 0
        val typed = ghostWord ?: return super.finishComposingText()
        // Drop the inline prediction before finishing, so its grey text isn't left in the field.
        // If the user moved the cursor (a tap elsewhere), put it back where they put it.
        ghostWord = null
        val wordEnd = ghostStart + typed.length
        val ghostEnd = wordEnd + ghostLength
        fun adjust(p: Int) = when {
            p >= ghostEnd -> p - ghostLength
            p > wordEnd -> wordEnd
            else -> p
        }
        val moved = lastSelStart >= 0 && ghostStart >= 0 && !(lastSelStart == wordEnd && lastSelEnd == wordEnd)
        super.beginBatchEdit()
        super.setComposingText(typed, 1)
        if (moved) super.setSelection(adjust(lastSelStart), adjust(lastSelEnd))
        val ok = super.finishComposingText()
        super.endBatchEdit()
        invalidate()
        return ok
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        ghostWord = null
        // Marking existing text before the cursor as composing doesn't change the text.
        if (end == expectedCursor && start <= end) composingLength = end - start else invalidate()
        return super.setComposingRegion(start, end)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (composingLength == 0) {
            val c = cache
            cache = if (c != null && beforeLength <= c.length) c.dropLast(beforeLength) else null
            if (expectedCursor >= 0) expectedCursor = (expectedCursor - beforeLength).coerceAtLeast(0)
        } else {
            invalidate()
        }
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        invalidate()
        return super.sendKeyEvent(event)
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        invalidate()
        return super.performEditorAction(editorAction)
    }

    override fun performContextMenuAction(id: Int): Boolean {
        invalidate()
        return super.performContextMenuAction(id)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        invalidate()
        return super.setSelection(start, end)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        invalidate()
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    /** Replaces the composing text (or inserts at the cursor) with [text] in the cache. */
    private fun replaceComposing(text: CharSequence, composingAfter: Int) {
        val c = cache
        cache = if (c != null && composingLength <= c.length) {
            (c.dropLast(composingLength) + text).takeLast(WINDOW).also { if (it.length == WINDOW) complete = false }
        } else null
        if (expectedCursor >= 0) expectedCursor += text.length - composingLength
        composingLength = composingAfter
    }

    companion object {
        /** Characters kept before the cursor; matches the longest read any feature makes. */
        const val WINDOW = 64
    }
}
