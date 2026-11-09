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
        val completions = (fromContext + dict.completions(partial, 8)).distinctBy { it.lowercase() }.filter(::allowed)
            .take(4).map { Corrector.matchCase(partial, it) }
            .filter { !it.equals(partial, ignoreCase = true) && it != correction }
        if (correction != null && correction != partial) {
            return Slots(Slot(partial, Kind.LITERAL), Slot(correction, Kind.CORRECTION),
                completions.firstOrNull()?.let { Slot(it, Kind.COMPLETION) })
        }
        // No autocorrection: the typed word stays (quoted on the left), completions fill the rest.
        val alternatives = completions.ifEmpty {
            corrector.candidates(partial, Corrector.MAX_COST, 2, touches, context).map { Corrector.matchCase(partial, it.word) }
        }
        return Slots(Slot(partial, Kind.LITERAL), alternatives.getOrNull(0)?.let { Slot(it, Kind.COMPLETION) },
            alternatives.getOrNull(1)?.let { Slot(it, Kind.COMPLETION) })
    }

    private fun capitalise(w: String) = w.replaceFirstChar { it.uppercaseChar() }
}
