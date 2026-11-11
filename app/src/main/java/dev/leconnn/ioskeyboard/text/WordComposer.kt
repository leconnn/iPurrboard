package dev.leconnn.ioskeyboard.text

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.SpannableStringBuilder
import android.text.style.SuggestionSpan
import android.view.inputmethod.InputConnection
import java.util.concurrent.Executors

/**
 * The word being typed, held as composing text so it can be corrected or replaced cleanly, plus
 * the suggestion bar contents. The service forwards key events here first; methods return false
 * when the event isn't about a word, so the service handles it as before.
 *
 * Dictionary work (predictions, corrections, swipe decoding) runs on one background thread so
 * typing never waits for it; the newest request wins. At a word boundary the correction for the
 * exact current word is computed on the spot if the background result isn't ready yet.
 */
class WordComposer(context: Context, private val ic: () -> InputConnection?) {

    /** Per-field and per-setting switches, refreshed by the service. */
    data class Options(
        val enabled: Boolean = false,
        val autocorrect: Boolean = false,
        val checkSpelling: Boolean = false,
        val curlyApostrophe: Boolean = false,
        val mathResults: Boolean = false,
        val blockOffensive: Boolean = true,
        val emojiSuggestions: Boolean = true,
        /** Predict in an empty field ("I", "The", "I'm"); off when the toolbar fills the empty bar. */
        val emptyFieldPredictions: Boolean = true,
        val replacements: Map<String, String> = emptyMap(),
        /** Show Predictions Inline: the predicted rest of the word in grey after the cursor. */
        val inline: Boolean = false,
        /** Learn typed words and undone corrections (off in incognito fields). */
        val learn: Boolean = true,
    )

    /** One language's data, loaded once. */
    private class LanguageData(
        val dict: Dictionary, val offensive: Set<String>, val emoji: EmojiKeywords, val bigrams: Bigrams,
    )

    private class Engine(
        val languages: List<Language>, val dict: Dictionary, val users: UserWords, val offensive: Set<String>,
        emoji: EmojiKeywords, bigrams: Bigrams,
    ) {
        val corrector = Corrector(dict) { users.learnedFrequency(it) }
        /** Only touched on the worker thread (its template cache isn't thread-safe). */
        val swipe by lazy { SwipeDecoder(dict) }
        /** Next words: the user's own word pairs first, then the built-in ones. */
        val predictor = Predictor(dict, corrector, { prev ->
            (users.nextWords(prev) + bigrams.after(prev)).distinctBy { it.lowercase() }
        }, emoji, languages[0].startWords, languages[0].midWords)

        /** Applies Block Offensive Words; only ever called on the worker thread. */
        fun block(on: Boolean) {
            val set = if (on) offensive else emptySet()
            if (corrector.blocked === set) return
            corrector.blocked = set
            predictor.blocked = set
            swipe.blocked = set
        }
    }

    /** [reject]: undoing also stops the word being autocorrected again (not for emoji swaps). */
    private class Undo(val original: String, val inserted: String, val reject: Boolean = true)

    /** The last swiped word (as inserted, plus its automatic space) and the decoder's alternatives. */
    private class Swiped(val inserted: String, val alternatives: List<String>)

    /** Everything a prediction depends on, captured on the main thread. */
    private class Snapshot(val word: String, val context: String, val options: Options, val touches: List<Corrector.Touch?>) {
        fun sameAs(o: Snapshot?) = o != null && o.word == word && o.context == context && o.options === options &&
            o.touches == touches
    }

    @Volatile private var engine: Engine? = null
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    var options = Options()
    /**
     * Set while delete auto-repeats: [refresh] waits, then runs once when this is cleared, so a
     * held delete doesn't recompute suggestions for every character.
     */
    var deferRefresh = false
        set(value) {
            field = value
            if (!value && refreshPending) { refreshPending = false; refresh() }
        }
    private var refreshPending = false
    /** Called on the main thread when the bar contents change. */
    var onSlotsChanged: (() -> Unit)? = null

    private val word = StringBuilder()
    /** Where each letter of [word] was tapped (null: unknown), for the corrector's spatial model. */
    private val touches = ArrayList<Corrector.Touch?>()
    /** The whole word the inline prediction completes [word] to, and the grey part now shown. */
    private var ghostFull: String? = null
    private var shownGhost = ""
    /** Inline predictions show only while typing: not for a word picked up again under the cursor. */
    private var ghostAllowed = false
    /** Text before the current word (read once when the word starts, saving an IPC per key). */
    private var wordContext: String? = null
    private var undo: Undo? = null
    private var swiped: Swiped? = null
    /** Slots shown in the bar: left, centre, right; [slotsFor] is the word they were computed for. */
    var slots: List<Predictor.Slot?> = EMPTY
        private set
    private var slotsFor: String? = null
    private var math: String? = null
    private var generation = 0
    /** The last snapshot sent to the worker; an identical request (a key, then its selection update) is skipped. */
    private var requested: Snapshot? = null

    val composing get() = word.isNotEmpty()

    /** Per-language data, only touched on the worker thread. */
    private val loaded = HashMap<Language, LanguageData>()
    private var wantedLanguages: List<Language> = emptyList()

    init {
        setLanguages(Language.ENGLISH, listOf(Language.ENGLISH))
    }

    /**
     * The keyboard's [current] language and every [enabled] one (Settings > Keyboards). Words
     * from the other enabled languages are known too (weighted lower), so mixed-language typing
     * isn't autocorrected. The engine in use stays until the new one is ready.
     */
    fun setLanguages(current: Language, enabled: List<Language>) {
        val order = listOf(current) + enabled.filter { it != current }
        if (order == wantedLanguages) return
        wantedLanguages = order
        worker.execute {
            if (engine?.languages == order) return@execute
            val data = order.map { loaded.getOrPut(it) { load(it) } }
            val dict = Dictionary.merge(data.mapIndexed { i, d -> d.dict to if (i == 0) 1.0 else Language.OTHER_WEIGHT })
            val built = Engine(order, dict, UserWords.get(appContext), data.flatMapTo(HashSet()) { it.offensive },
                EmojiKeywords.combine(data.map { it.emoji }), Bigrams.combine(data.map { it.bigrams }))
            // Unchanged by a later request meanwhile? Then use it (else that request builds its own).
            main.post {
                if (wantedLanguages == order) { engine = built; requested = null; refresh() }
            }
        }
    }

    private fun load(language: Language): LanguageData {
        fun lines(path: String) = appContext.assets.open(path).bufferedReader(Charsets.UTF_8)
        return LanguageData(
            lines(language.dictAsset).useLines { Dictionary.parse(it) },
            lines(language.blockedAsset).useLines { l -> l.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim() }.toHashSet() },
            lines(language.emojiKeywordsAsset).useLines { EmojiKeywords.parse(it) },
            lines(language.bigramsAsset).useLines { Bigrams.parse(it) },
        )
    }

    private val active get() = options.enabled && engine != null

    fun reset() {
        clearWord()
        undo = null
        swiped = null
    }

    /**
     * The cursor sits inside the word being composed (Gboard: tap into a word and the bar offers
     * replacements for the whole word). [midOffset] is the cursor's place in [word]. Typing or
     * deleting there edits the text normally; picking a suggestion replaces the whole word.
     */
    private var midWord = false
    private var midOffset = 0

    private fun clearWord() {
        midWord = false
        word.clear()
        touches.clear()
        wordContext = null
        ghostFull = null
        shownGhost = ""
    }

    /**
     * A character key. Returns true if it extended the current word. [tap] is where on the key it
     * was tapped (offset from the key's centre in key widths / heights), if known.
     */
    fun onChar(text: String, tap: Pair<Float, Float>? = null): Boolean {
        undo = null
        val s = swiped
        swiped = null
        if (!active) return false
        // Punctuation right after a swiped word replaces its automatic space ("word." not "word .").
        if (s != null && text in WORD_END) {
            val conn = ic()
            if (conn?.getTextBeforeCursor(1, 0)?.toString() == " ") conn.deleteSurroundingText(1, 0)
        }
        if (midWord) { leaveMidWord(); return false }
        val isLetter = text.length == 1 && text[0].isLetter()
        val isApostrophe = text == "'" && word.isNotEmpty()
        if (!isLetter && !isApostrophe) return false
        if (word.isEmpty()) wordContext = ic()?.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString().orEmpty()
        word.append(if (isApostrophe && options.curlyApostrophe) "’" else text)
        touches.add(tap?.let { (dx, dy) -> KeyProximity.touch(text[0], dx, dy)?.let { (x, y) -> Corrector.Touch(x, y) } })
        ghostAllowed = true
        showComposing()
        refresh()
        return true
    }

    /**
     * Ends the current word because [separator] (space, punctuation, return) is about to be typed,
     * applying an autocorrection or text replacement if one is pending. The caller then types the
     * separator and calls [afterSeparator].
     */
    fun finishWord(separator: String) {
        val conn = ic() ?: return
        if (word.isEmpty()) return
        // A space or punctuation typed inside a word splits it; nothing is autocorrected.
        if (midWord) { leaveMidWord(); return }
        val original = word.toString()
        // Space accepts an inline prediction, as on iOS (before any autocorrection).
        val inline = shownGhost.takeIf { it.isNotEmpty() && separator == " " }
        if (inline == null && slotsFor != original) computeNow()
        val auto = slots[1]?.takeIf { inline == null && it.automatic && (separator == " " || separator in WORD_END) }
        val final = inline?.let { original + it } ?: auto?.text?.let(::styleApostrophes) ?: original
        if (final != original) {
            conn.setComposingText(final, 1)
            // Backspace after an accepted inline prediction just deletes the space, as on iOS;
            // after an autocorrection it brings back what was typed.
            if (inline == null) undo = Undo(original, final)
        }
        conn.finishComposingText()
        learn(final, wordContext.orEmpty())
        clearWord()
    }

    /** Called after the separator following [finishWord] was committed. */
    fun afterSeparator(separator: String) {
        undo = undo?.let { Undo(it.original, it.inserted + separator, it.reject) }
    }

    /**
     * A slide-to-type path ended. Decodes it in the background, then inserts the best word (with a
     * space before it if needed and an automatic space after, like iOS) and offers the alternatives
     * in the bar. [capitals]: 0 none, 1 first letter, 2 all. [done] runs on the main thread with
     * true if a word was inserted.
     */
    fun onSwipe(points: List<Pair<Float, Float>>, capitals: Int, done: (Boolean) -> Unit) {
        val e = engine ?: return done(false)
        val curly = options.curlyApostrophe
        val block = options.blockOffensive
        worker.execute {
            e.block(block)
            val decoded = e.swipe.decode(points, 3).map { caps(it.word, capitals) }
            main.post { done(commitSwipe(decoded.map { if (curly) it.replace('\'', '’') else it })) }
        }
    }

    private fun commitSwipe(results: List<String>): Boolean {
        val conn = ic() ?: return false
        if (results.isEmpty()) return false
        undo = null
        if (word.isNotEmpty()) {
            conn.finishComposingText()
            learn(word.toString(), wordContext.orEmpty())
            clearWord()
        }
        val before = conn.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString().orEmpty()
        val needSpace = before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in OPENERS
        val text = results[0]
        conn.commitText((if (needSpace) " " else "") + text + " ", 1)
        learn(text, before + if (needSpace) " " else "")
        swiped = Swiped(text + " ", results.drop(1))
        refresh()
        return true
    }

    private fun caps(w: String, capitals: Int) = when (capitals) {
        2 -> w.uppercase()
        1 -> w.replaceFirstChar { it.uppercaseChar() }
        else -> w
    }

    /** Backspace. Returns true if handled (undoing an autocorrection or editing the word). */
    fun onDelete(repeat: Int, swipeDeletesWord: Boolean = true): Boolean {
        val conn = ic() ?: return false
        val s = swiped
        swiped = null
        if (repeat == 0 && s != null && swipeDeletesWord &&
            conn.getTextBeforeCursor(s.inserted.length, 0)?.toString() == s.inserted
        ) {
            // "Delete Slide-to-Type by Word": one backspace removes the whole swiped word.
            conn.deleteSurroundingText(s.inserted.length, 0)
            return true
        }
        val u = undo
        undo = null
        if (repeat == 0 && u != null) {
            val before = conn.getTextBeforeCursor(u.inserted.length, 0)?.toString()
            if (before == u.inserted) {
                // Like iOS: one backspace right after an autocorrection restores what was typed.
                conn.deleteSurroundingText(u.inserted.length, 0)
                conn.commitText(u.original, 1)
                if (u.reject && options.learn) engine?.users?.reject(normalise(u.original))
                return true
            }
        }
        if (word.isEmpty()) return false
        if (midWord) { leaveMidWord(); return false }
        word.deleteCharAt(word.length - 1)
        if (touches.isNotEmpty()) touches.removeAt(touches.size - 1)
        if (word.isEmpty()) {
            conn.commitText("", 1)
            wordContext = null
        } else {
            showComposing()
        }
        refresh()
        return true
    }

    /** A suggestion slot was tapped. Returns true if it inserted something. */
    fun onSuggestion(index: Int): Boolean {
        val conn = ic() ?: return false
        if (index == 1 && math != null) {
            if (word.isNotEmpty()) conn.finishComposingText()
            clearWord()
            conn.commitText(math, 1)
            return true
        }
        val slot = slots.getOrNull(index) ?: return false
        undo = null
        val s = swiped
        if (slot.kind == Predictor.Kind.SWIPE && s != null) {
            // Swap the swiped word for the chosen alternative.
            swiped = null
            if (conn.getTextBeforeCursor(s.inserted.length, 0)?.toString() == s.inserted) {
                conn.deleteSurroundingText(s.inserted.length, 0)
            }
            val before = conn.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString().orEmpty()
            conn.commitText(slot.text + " ", 1)
            learn(slot.text, before)
            return true
        }
        swiped = null
        if (slot.kind == Predictor.Kind.EMOJI) {
            // The emoji replaces the word; a backspace right after brings the word back.
            val emoji = toned(slot.text)
            val original = word.toString()
            if (word.isNotEmpty()) conn.setComposingText(emoji, 1) else conn.commitText(emoji, 1)
            conn.finishComposingText()
            clearWord()
            conn.commitText(" ", 1)
            undo = Undo(original, "$emoji ", reject = false)
            prefs.addEmojiRecent(emoji)
            return true
        }
        val text = if (slot.kind == Predictor.Kind.LITERAL) word.toString() else styleApostrophes(slot.text)
        if (slot.kind == Predictor.Kind.LITERAL && options.learn) engine?.users?.reject(normalise(text))
        val context = wordContext ?: conn.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString().orEmpty()
        if (word.isNotEmpty()) conn.setComposingText(text, 1) else conn.commitText(text, 1)
        conn.finishComposingText()
        learn(text, context)
        clearWord()
        // A word picked up from the text (a tap into or after it) may already have a space after
        // it: step over that one instead of adding another.
        val next = conn.getTextAfterCursor(1, 0)?.toString().orEmpty()
        if (next == " ") {
            conn.beginBatchEdit()
            conn.commitText(" ", 1)
            conn.deleteSurroundingText(0, 1)
            conn.endBatchEdit()
        } else {
            conn.commitText(" ", 1)
        }
        return true
    }

    /**
     * The cursor moved. If it left the composing word (tap elsewhere, app edit), stop composing.
     * If it now sits at the end of a word that isn't being composed (a tap after a word, or a
     * backspace into one), pick that word up so the bar predicts and corrects it, like iOS.
     */
    fun onSelectionChanged(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (word.isNotEmpty() && midWord) {
            // Still in the same word at the same place (this is the update for our own
            // composing region): keep it. Anywhere else: stop, and let the next update pick up
            // whatever the cursor is in now.
            val same = selStart == selEnd && candidatesStart >= 0 && candidatesEnd - candidatesStart == word.length &&
                selStart == candidatesStart + midOffset
            if (!same) leaveMidWord()
            return
        }
        if (word.isNotEmpty()) {
            // With an inline prediction the composing text runs past the cursor.
            // (the cursor at the end of the grey text means the user tapped there, not typing).
            val atWordEnd = if (shownGhost.isNotEmpty()) candidatesStart >= 0 && selEnd == candidatesStart + word.length
            else candidatesEnd == selEnd
            if (selStart != selEnd || !atWordEnd) {
                ic()?.finishComposingText()
                clearWord()
            }
            return
        }
        if (selStart == selEnd && candidatesStart < 0 && selStart > 0) resumeWord(selStart)
    }

    /**
     * Starts composing the word the cursor is in or at the end of, if there is one: the bar then
     * predicts and corrects it, and a suggestion replaces the whole word (iOS at a word's end;
     * Gboard anywhere in it).
     */
    private fun resumeWord(cursor: Int) {
        if (!active || swiped != null) return
        val conn = ic() ?: return
        val before = conn.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString() ?: return
        val run = TRAILING_WORD.find(before)?.value ?: return
        // Not part of an address, handle, number or path ("me@mail", "#tag", "v2beta").
        val prev = before.getOrNull(before.length - run.length - 1)
        if (prev != null && (prev.isLetterOrDigit() || prev in "@#/_\\.:-")) return
        val after = conn.getTextAfterCursor(MAX_RESUME + 1, 0)?.toString().orEmpty()
        val tail = LEADING_WORD.find(after)?.value.orEmpty()
        val whole = run + tail
        if (whole.last() == '\'' || whole.last() == '’' || whole.length > MAX_RESUME) return
        // The word must end there too (not "word2", "name@host", "snake_case").
        val next = after.getOrNull(tail.length)
        if (next != null && (next.isLetterOrDigit() || next in "@_")) return
        if (!conn.setComposingRegion(cursor - run.length, cursor + tail.length)) return
        word.append(whole)
        repeat(whole.length) { touches.add(null) }
        wordContext = before.dropLast(run.length)
        midWord = tail.isNotEmpty()
        midOffset = run.length
        ghostAllowed = false
        refresh()
    }

    /** Stops composing a word the cursor was inside, leaving its text as it is. */
    private fun leaveMidWord() {
        ic()?.finishComposingText()
        clearWord()
    }

    /** Recomputes the bar for the text before the cursor, in the background. */
    fun refresh() {
        if (deferRefresh) { refreshPending = true; return }
        val e = engine
        if (e == null || !options.enabled) {
            setSlots(EMPTY, null, null)
            return
        }
        swiped?.let { s ->
            // After a swipe the bar offers the decoder's other guesses, like iOS.
            val alt = s.alternatives.map { Predictor.Slot(it, Predictor.Kind.SWIPE) }
            setSlots(listOf(alt.getOrNull(0), null, alt.getOrNull(1)), null, null)
            return
        }
        val snapshot = snapshot() ?: return
        if (snapshot.sameAs(requested)) return
        requested = snapshot
        val gen = ++generation
        worker.execute {
            val (newSlots, newMath) = compute(e, snapshot)
            main.post { if (gen == generation) setSlots(newSlots, newMath, snapshot.word) }
        }
    }

    /** Synchronous refresh for the exact current word (used when a word ends before the worker caught up). */
    private fun computeNow() {
        val e = engine ?: return
        val snapshot = snapshot() ?: return
        generation++ // any in-flight result is now stale
        requested = snapshot
        val (newSlots, newMath) = compute(e, snapshot)
        slots = newSlots
        math = newMath
        slotsFor = snapshot.word
    }

    private fun snapshot(): Snapshot? {
        val context = wordContext ?: ic()?.getTextBeforeCursor(MAX_CONTEXT, 0)?.toString() ?: return null
        return Snapshot(word.toString(), context, options, touches.toList())
    }

    private fun compute(e: Engine, s: Snapshot): Pair<List<Predictor.Slot?>, String?> {
        e.block(s.options.blockOffensive)
        // With the toolbar on, an empty field shows its buttons instead of predictions.
        if (!s.options.emptyFieldPredictions && s.word.isEmpty() && s.context.isBlank()) return EMPTY to null
        val math = if (s.options.mathResults) MathCompletion.resultFor(s.context + s.word) else null
        val prev = PREV_WORD.find(s.context)?.groupValues?.get(1)
        val sentenceStart = s.context.isBlank() || SENTENCE_END.containsMatchIn(s.context)
        val p = e.predictor.predict(normalise(s.word), prev, sentenceStart, s.options.autocorrect,
            s.options.replacements, e.users.rejected, s.options.emojiSuggestions, s.touches)
        return listOf(p.left, p.centre, p.right) to math
    }

    private fun setSlots(newSlots: List<Predictor.Slot?>, newMath: String?, forWord: String?) {
        if (forWord == null) requested = null
        slots = newSlots
        math = newMath
        slotsFor = forWord
        // A new prediction for the word being typed: update the grey inline text.
        if (word.isNotEmpty() && forWord == word.toString()) {
            ghostFull = inlineCandidate(newSlots)
            if (ghostText() != shownGhost) showComposing()
        }
        onSlotsChanged?.invoke()
    }

    /**
     * The word an inline prediction would complete to: the centre suggestion when it is a
     * completion of what was typed (not a correction), keeping the typed letters as typed.
     */
    private fun inlineCandidate(s: List<Predictor.Slot?>): String? {
        if (!options.inline || word.length < INLINE_MIN) return null
        val c = s.getOrNull(1)?.takeIf { it.kind == Predictor.Kind.COMPLETION } ?: return null
        val typed = word.toString()
        // A word that is already complete ("you", "thank") gets no inline ending, or space
        // would turn it into a longer word.
        val e = engine
        if (e != null && (e.dict.contains(normalise(typed)) || e.users.learnedFrequency(normalise(typed)) > 0)) return null
        val full = styleApostrophes(c.text)
        if (full.length <= typed.length || !normalise(full).startsWith(normalise(typed), ignoreCase = true)) return null
        return typed + full.substring(typed.length)
    }

    /** The grey rest of the word for what has been typed so far ("" for none). */
    private fun ghostText(): String {
        val full = ghostFull ?: return ""
        val typed = word.toString()
        if (!ghostAllowed || !options.inline || full.length <= typed.length) return ""
        return if (normalise(full).startsWith(normalise(typed), ignoreCase = true)) full.substring(typed.length) else ""
    }

    /** Bar contents as (display text, bold) per slot; math results take the centre. */
    fun displaySlots(): List<Pair<String, Boolean>?> {
        math?.let { return listOf(null, it to false, null) }
        return slots.map { s ->
            s?.let {
                val text = if (it.kind == Predictor.Kind.EMOJI) toned(it.text) else styleApostrophes(it.display)
                text to (it.kind == Predictor.Kind.CORRECTION)
            }
        }
    }

    private fun showComposing() {
        val conn = ic() ?: return
        val text = word.toString()
        val ghost = ghostText()
        if (ghost.isNotEmpty() && conn is dev.leconnn.ioskeyboard.CachingInputConnection) {
            val s = SpannableStringBuilder(text).append(ghost)
            s.setSpan(ForegroundColorSpan(GHOST_COLOR), text.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (conn.setComposingWithGhost(s, ghost.length)) { shownGhost = ghost; return }
        }
        shownGhost = ""
        val e = engine
        val unknown = options.checkSpelling && e != null && text.length > 1 &&
            !e.dict.contains(normalise(text)) && e.users.learnedFrequency(normalise(text)) == 0
        if (unknown) {
            val s = SpannableString(text)
            s.setSpan(SuggestionSpan(appContext, arrayOf(), SuggestionSpan.FLAG_MISSPELLED), 0, text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            conn.setComposingText(s, 1)
        } else {
            conn.setComposingText(text, 1)
        }
    }

    /** Records [text], typed after [context], for learning words and word pairs. */
    private fun learn(text: String, context: String) {
        if (!options.learn) return
        val e = engine ?: return
        val w = normalise(text)
        if (w.isEmpty() || w.any { !it.isLetter() && it != '\'' }) return
        e.users.commit(PREV_WORD.find(context)?.groupValues?.get(1), w)
    }

    private val prefs = dev.leconnn.ioskeyboard.Prefs.get(appContext)
    /** The skin tone last chosen for [emoji] in the emoji panel, if any. */
    private fun toned(emoji: String) = prefs.emojiTone(emoji) ?: emoji

    private fun normalise(s: String) = s.replace('’', '\'')
    private fun styleApostrophes(s: String) = if (options.curlyApostrophe) s.replace('\'', '’') else s

    private companion object {
        const val MAX_CONTEXT = 64
        /** Letters typed before an inline prediction appears. */
        const val INLINE_MIN = 2
        /** Longest word picked up again when the cursor lands after it. */
        const val MAX_RESUME = 32
        /** Inline prediction text: a mid grey that reads on light and dark fields, like iOS. */
        const val GHOST_COLOR = 0xFF8E8E93.toInt()
        /** A word (letters, inner apostrophes) right before the cursor. */
        val TRAILING_WORD = Regex("""\p{L}[\p{L}'’]*$""")
        /** Letters right after the cursor (the rest of a word the cursor is in). */
        val LEADING_WORD = Regex("""^[\p{L}'’]*""")
        val EMPTY: List<Predictor.Slot?> = listOf(null, null, null)
        /** Punctuation that ends a word and triggers autocorrection, as on iOS. */
        val WORD_END = setOf(".", ",", "!", "?", ";", ":", "\n")
        /** The word right before the cursor, if only spaces separate them. */
        val PREV_WORD = Regex("""([\p{L}'’]+) +$""")
        val SENTENCE_END = Regex("""[.!?]\s*$""")
        /** No space is added between these and a swiped word. */
        const val OPENERS = "([{“‘\"'"
    }
}
