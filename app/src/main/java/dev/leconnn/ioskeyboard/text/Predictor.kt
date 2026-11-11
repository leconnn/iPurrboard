package dev.leconnn.ioskeyboard.text

/**
 * Fills the three suggestion slots the way iOS does:
 * - while typing a word: left = the typed word in quotes (keeps it as typed), centre = the
 *   autocorrection (bold, applied on space) or the best completion, right = the next completion;
 * - right after a space: next-word predictions.
 * A Text Replacement shortcut takes the centre slot and is applied on space.
 */
class Predictor(
    private val dict: Dictionary,
    private val corrector: Corrector,
    /** Next words learned after [prev], most likely first. */
    private val nextWords: (prev: String?) -> List<String> = { emptyList() },
    /** Word -> emoji for emoji suggestions (null: none). */
    private val emoji: EmojiKeywords? = null,
    /** Suggestions with nothing typed: at a sentence start, and mid-sentence (the keyboard's language). */
    private val startWords: List<String> = Language.ENGLISH.startWords,
    private val midWords: List<String> = Language.ENGLISH.midWords,
) {
    enum class Kind { LITERAL, CORRECTION, COMPLETION, REPLACEMENT, NEXT_WORD, SWIPE, EMOJI }

    /** Lowercase words never suggested (Block Offensive Words); typing them still works. */
    @Volatile var blocked: Set<String> = emptySet()
    private fun allowed(w: String) = blocked.isEmpty() || w.lowercase() !in blocked

    data class Slot(val text: String, val kind: Kind) {
        val display get() = if (kind == Kind.LITERAL) "“$text”" else text
        /** Applied automatically when the word ends (space or punctuation). */
        val automatic get() = kind == Kind.CORRECTION || kind == Kind.REPLACEMENT
    }

    data class Slots(val left: Slot?, val centre: Slot?, val right: Slot?)

    fun predict(
        partial: String,
        prevWord: String?,
        sentenceStart: Boolean,
        autocorrect: Boolean,
        replacements: Map<String, String> = emptyMap(),
        rejected: Set<String> = emptySet(),
        emojiSuggestions: Boolean = false,
        touches: List<Corrector.Touch?>? = null,
    ): Slots {
        val slots = predictWords(partial, prevWord, sentenceStart, autocorrect, replacements, rejected, touches)
        // Like iOS, a word with a matching emoji offers it on the right.
        if (!emojiSuggestions || partial.isEmpty() || slots.centre?.kind == Kind.REPLACEMENT) return slots
        val e = emoji?.forWord(partial)?.firstOrNull() ?: return slots
        return slots.copy(right = Slot(e, Kind.EMOJI))
    }

    private fun predictWords(
        partial: String,
        prevWord: String?,
        sentenceStart: Boolean,
        autocorrect: Boolean,
        replacements: Map<String, String>,
        rejected: Set<String>,
        touches: List<Corrector.Touch?>?,
    ): Slots {
        if (partial.isEmpty()) {
            val learned = nextWords(prevWord).filter(::allowed)
            val words = (learned + if (sentenceStart) startWords else midWords).distinct().take(3)
            val shown = words.map { if (sentenceStart) capitalise(it) else it }
            return Slots(shown.getOrNull(1)?.let { Slot(it, Kind.NEXT_WORD) },
                shown.getOrNull(0)?.let { Slot(it, Kind.NEXT_WORD) },
                shown.getOrNull(2)?.let { Slot(it, Kind.NEXT_WORD) })
        }

        replacements.entries.firstOrNull { it.key.equals(partial, ignoreCase = true) }?.let {
            return Slots(Slot(partial, Kind.LITERAL), Slot(it.value, Kind.REPLACEMENT), null)
        }

        // Words often typed after the previous one, as context for corrections.
        val likely = nextWords(prevWord).map { it.lowercase() }
        val context = likely.toSet()
        val correction = if (autocorrect) corrector.autocorrect(partial, rejected, touches, context) else null
        // Completions that usually follow the previous word come first ("I am go" -> "going").
        val lower = partial.lowercase()
        val fromContext = likely.filter { it.startsWith(lower) && it != lower }.mapNotNull { dict.displayForm(it) }
        val completions = (fromContext + dict.completions(partial, 12)).distinctBy { it.lowercase() }.filter(::allowed)
        if (correction != null && correction != partial) {
            val next = ranked(partial, completions, touches, context, exclude = setOf(correction.lowercase()), n = 1)
            return Slots(Slot(partial, Kind.LITERAL), Slot(correction, Kind.CORRECTION),
                next.firstOrNull()?.let { Slot(it, Kind.COMPLETION) })
        }
        // No autocorrection: the typed word stays (quoted on the left); the two likeliest
        // completions or corrections fill the rest.
        val alternatives = ranked(partial, completions, touches, context, exclude = emptySet(), n = 2)
        return Slots(Slot(partial, Kind.LITERAL), alternatives.getOrNull(0)?.let { Slot(it, Kind.COMPLETION) },
            alternatives.getOrNull(1)?.let { Slot(it, Kind.COMPLETION) })
    }

    /**
     * Completions and corrections of [partial] ranked together, as Gboard does: each scored by how
     * common the word is minus how far it is from what was typed. A completion costs a little per
     * letter still to type (capped, so long words stay reachable); a correction costs its edit
     * distance. So "helo" offers "hello" before the rare "heloise", and "worl" still offers "world"
     * before "work". Words often typed after the previous word get the context bonus.
     */
    private fun ranked(
        partial: String, completions: List<String>, touches: List<Corrector.Touch?>?, context: Set<String>,
        exclude: Set<String>, n: Int,
    ): List<String> {
        val lower = partial.lowercase()
        val pool = HashMap<String, Pair<String, Double>>()
        fun offer(word: String, score: Double) {
            val k = word.lowercase()
            if (k == lower || k in exclude || !allowed(word)) return
            if ((pool[k]?.second ?: Double.NEGATIVE_INFINITY) < score) pool[k] = word to score
        }
        for (w in completions) {
            val i = dict.indexOf(w)
            val freq = if (i >= 0) dict.freq[i] else 0
            val missing = (w.length - partial.length).coerceAtLeast(1)
            val bonus = if (w.lowercase() in context) Corrector.CONTEXT_BONUS else 0.0
            offer(w, corrector.score(freq, minOf(missing * COMPLETION_COST, MAX_COMPLETION_COST)) + bonus)
        }
        for (c in corrector.candidates(partial, Corrector.MAX_COST, 6, touches, context)) offer(c.word, c.score)
        return pool.values.sortedByDescending { it.second }.take(n).map { Corrector.matchCase(partial, it.first) }
    }

    private fun capitalise(w: String) = w.replaceFirstChar { it.uppercaseChar() }

    private companion object {
        /** Cost per letter a completion still needs, and its cap (in the corrector's cost units). */
        const val COMPLETION_COST = 0.25f
        const val MAX_COMPLETION_COST = 0.75f
    }
}
