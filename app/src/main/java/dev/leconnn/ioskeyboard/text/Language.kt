package dev.leconnn.ioskeyboard.text

/**
 * A keyboard language (Settings > Keyboards). Both use the QWERTY layout, as on iOS and Gboard;
 * what changes is the word list, next-word pairs, offensive-word list, emoji names, the starter
 * suggestions and the dictation language. Data files are in assets (tools/dict, tools/emoji).
 */
enum class Language(
    /** Asset suffix (dict/<code>.tsv, dict/bigrams_<code>.tsv, dict/blocked_<code>.txt). */
    val code: String,
    /** As iOS lists it in Settings > Keyboards. */
    val label: String,
    /** On the space bar right after switching. */
    val spaceLabel: String,
    /** Under the name in Settings > Languages. */
    val description: String,
    /** BCP 47 tag for dictation. */
    val speechTag: String,
    /** Suggestions in an empty field or a new sentence, most likely first. */
    val startWords: List<String>,
    /** Suggestions mid-sentence when nothing better is known. */
    val midWords: List<String>,
) {
    ENGLISH("en", "English (US)", "English", "English words, corrections and dictation", "en-US", listOf("I", "the", "I'm"), listOf("the", "to", "and")),
    FILIPINO("tl", "Filipino", "Filipino", "Tagalog words, corrections and dictation", "fil-PH", listOf("Ako", "Hindi", "Ang"), listOf("ang", "sa", "na")),
    ;

    val dictAsset get() = "dict/$code.tsv"
    val bigramsAsset get() = "dict/bigrams_$code.tsv"
    val blockedAsset get() = "dict/blocked_$code.txt"
    val emojiKeywordsAsset get() = if (this == ENGLISH) "emoji_keywords.tsv" else "emoji_keywords_$code.tsv"
    val emojiSearchAsset get() = if (this == ENGLISH) "emoji_search.tsv" else "emoji_search_$code.tsv"

    companion object {
        /**
         * How much a word from another enabled keyboard counts next to the current one's: with
         * English and Filipino both on, Taglish ("kumain na ako, see you later") is typed without
         * switching, and neither language's words are corrected into the other's.
         */
        const val OTHER_WEIGHT = 0.25
    }
}
