package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The Filipino keyboard on its own and together with English (Taglish), on the real assets. */
class FilipinoTest {
    companion object {
        lateinit var tl: Dictionary
        lateinit var en: Dictionary
        lateinit var tagalog: Corrector
        /** Filipino in use, English also enabled. */
        lateinit var taglish: Corrector
        lateinit var bigrams: Bigrams

        private fun asset(path: String) =
            listOf("src/main/assets/$path", "app/src/main/assets/$path").map(::File).first { it.exists() }
                .readLines(Charsets.UTF_8).asSequence()

        @BeforeClass @JvmStatic fun load() {
            tl = Dictionary.parse(asset("dict/tl.tsv"))
            en = Dictionary.parse(asset("dict/en.tsv"))
            tagalog = Corrector(tl)
            taglish = Corrector(Dictionary.merge(listOf(tl to 1.0, en to Language.OTHER_WEIGHT)))
            bigrams = Bigrams.combine(listOf(Bigrams.parse(asset("dict/bigrams_tl.tsv")), Bigrams.parse(asset("dict/bigrams_en.tsv"))))
        }
    }

    @Test fun commonWordsKnown() {
        for (w in "ang ng nang mga po opo salamat kumain mahal kita gusto yung sige talaga".split(' ')) {
            assertTrue(w, tl.contains(w))
        }
    }

    // The keyboard ends a word at a hyphen, so prefixes of "mag-aral" must stay as typed.
    @Test fun hyphenPrefixesUnchanged() {
        for (w in listOf("mag", "pag", "nag", "isa")) assertNull(w, tagalog.autocorrect(w))
    }

    @Test fun missingLetter() = assertEquals("salamat", tagalog.autocorrect("salamt"))
    @Test fun transposition() = assertEquals("kumain", tagalog.autocorrect("kumian"))
    @Test fun neighbouringKey() = assertEquals("gusto", tagalog.autocorrect("gustp"))
    @Test fun daysCapitalised() = assertEquals("Lunes", tagalog.autocorrect("lunes"))

    @Test fun taglishKeepsEnglish() {
        for (w in listOf("later", "meeting", "deadline", "anyway")) assertNull(w, taglish.autocorrect(w))
    }

    @Test fun taglishKeepsTagalog() {
        for (w in listOf("kasi", "naman", "bukas", "mamaya")) assertNull(w, taglish.autocorrect(w))
    }

    @Test fun taglishPrefersFilipino() = assertEquals("salamat", taglish.autocorrect("salamt"))

    @Test fun mergedNextWordsTagalogFirst() {
        val next = bigrams.after("salamat")
        assertTrue(next.toString(), next.first() in setOf("sa", "po"))
    }

    @Test fun filipinoStarterWords() {
        val dict = Dictionary.merge(listOf(tl to 1.0))
        val p = Predictor(dict, Corrector(dict), startWords = Language.FILIPINO.startWords, midWords = Language.FILIPINO.midWords)
        val slots = p.predict("", null, sentenceStart = true, autocorrect = true)
        assertEquals("Ako", slots.centre?.text)
    }
}
