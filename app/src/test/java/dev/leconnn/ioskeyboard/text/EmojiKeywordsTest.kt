package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Runs against the real keyword and dictionary assets. */
class EmojiKeywordsTest {
    companion object {
        lateinit var keywords: EmojiKeywords
        lateinit var dict: Dictionary

        private fun asset(name: String) =
            listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }

        @BeforeClass @JvmStatic fun load() {
            keywords = EmojiKeywords.parse(asset("emoji_keywords.tsv").readLines(Charsets.UTF_8).asSequence())
            dict = Dictionary.parse(asset("dict/en.tsv").readLines(Charsets.UTF_8).asSequence())
        }
    }

    @Test fun pizza() = assertEquals("\uD83C\uDF55", keywords.forWord("pizza").first())
    @Test fun anyCase() = assertEquals(keywords.forWord("pizza"), keywords.forWord("Pizza"))
    @Test fun functionWordsHaveNone() = assertTrue(keywords.forWord("the").isEmpty())

    @Test fun predictorOffersEmojiOnTheRight() {
        val p = Predictor(dict, Corrector(dict), emoji = keywords)
        val slots = p.predict("pizza", null, sentenceStart = false, autocorrect = true, emojiSuggestions = true)
        assertEquals(Predictor.Kind.EMOJI, slots.right?.kind)
        assertEquals("\uD83C\uDF55", slots.right?.text)
    }

    @Test fun offWhenDisabled() {
        val p = Predictor(dict, Corrector(dict), emoji = keywords)
        val slots = p.predict("pizza", null, sentenceStart = false, autocorrect = true, emojiSuggestions = false)
        assertTrue(slots.right?.kind != Predictor.Kind.EMOJI)
    }
}
