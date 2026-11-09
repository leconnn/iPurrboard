package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Runs against the real dictionary asset. */
class CorrectorTest {
    companion object {
        lateinit var dict: Dictionary
        lateinit var corrector: Corrector

        @BeforeClass @JvmStatic fun load() {
            val f = listOf("src/main/assets/dict/en.tsv", "app/src/main/assets/dict/en.tsv").map(::File).first { it.exists() }
            dict = Dictionary.parse(f.readLines(Charsets.UTF_8).asSequence())
            corrector = Corrector(dict)
        }
    }

    @Test fun transposition() = assertEquals("the", corrector.autocorrect("teh"))
    @Test fun missingLetter() = assertEquals("hello", corrector.autocorrect("helo"))
    @Test fun ieSwap() = assertEquals("receive", corrector.autocorrect("recieve"))
    @Test fun neighbouringKey() = assertEquals("the", corrector.autocorrect("thw"))
    @Test fun contractionIm() = assertEquals("I'm", corrector.autocorrect("im"))
    @Test fun contractionDont() = assertEquals("don't", corrector.autocorrect("dont"))
    @Test fun lowercaseI() = assertEquals("I", corrector.autocorrect("i"))
    @Test fun keepsCapital() = assertEquals("The", corrector.autocorrect("Teh"))
    @Test fun keepsAllCaps() = assertEquals("THE", corrector.autocorrect("TEH"))
    @Test fun validWordUnchanged() = assertNull(corrector.autocorrect("hello"))
    @Test fun validShortWordUnchanged() = assertNull(corrector.autocorrect("its"))
    @Test fun gibberishUnchanged() = assertNull(corrector.autocorrect("xqzv"))
    @Test fun rejectedUnchanged() = assertNull(corrector.autocorrect("teh", rejected = setOf("teh")))
    @Test fun numbersUnchanged() = assertNull(corrector.autocorrect("b4"))

    @Test fun blockedWordsAreNeverSuggested() {
        val p = Predictor(dict, Corrector(dict).apply { blocked = setOf("shit") }).apply { blocked = setOf("shit") }
        val slots = p.predict("shi", null, sentenceStart = false, autocorrect = true)
        val shown = listOfNotNull(slots.centre, slots.right).map { it.text.lowercase() }
        assertTrue(shown.toString(), "shit" !in shown)
        val unblocked = Predictor(dict, Corrector(dict)).predict("shi", null, sentenceStart = false, autocorrect = true)
        assertTrue("sanity: unblocked can suggest it", listOfNotNull(unblocked.centre, unblocked.right).any { it.text == "shit" } ||
            dict.completions("shi", 8).contains("shit"))
    }

    @Test fun blockedWordIsNotACorrection() {
        val c = Corrector(dict).apply { blocked = setOf("shit") }
        assertTrue(c.candidates("shiy").none { it.word.equals("shit", ignoreCase = true) })
    }

    @Test fun completions() {
        val c = dict.completions("hel", 4)
        assertTrue(c.toString(), "hello" in c && "help" in c)
    }

    @Test fun predictorWhileTyping() {
        val p = Predictor(dict, corrector).predict("teh", null, sentenceStart = false, autocorrect = true)
        assertEquals(Predictor.Slot("teh", Predictor.Kind.LITERAL), p.left)
        assertEquals(Predictor.Slot("the", Predictor.Kind.CORRECTION), p.centre)
    }

    @Test fun predictorReplacement() {
        val p = Predictor(dict, corrector).predict("omw", null, false, true, mapOf("omw" to "On my way!"))
        assertEquals(Predictor.Slot("On my way!", Predictor.Kind.REPLACEMENT), p.centre)
    }

    @Test fun predictorSentenceStart() {
        val p = Predictor(dict, corrector).predict("", null, sentenceStart = true, autocorrect = true)
        assertEquals("I", p.centre?.text)
    }

    @Test fun speed() {
        val start = System.nanoTime()
        repeat(20) { corrector.candidates("recieve") }
        val ms = (System.nanoTime() - start) / 20 / 1_000_000
        assertTrue("candidates took $ms ms", ms < 60)
    }

    // ---- Gboard / AOSP-style refinements ----

    /** A word typed a letter short reads as unfinished, not as a neighbouring-key typo. */
    @Test fun unfinishedWord() = assertEquals("world", corrector.autocorrect("worl"))

    /** A tap on the edge of G towards H makes "tge" -> "the" very cheap. */
    @Test fun tapOnKeyEdge() {
        val taps = listOf(KeyProximity.touch('t', 0f, 0f), KeyProximity.touch('g', 0.5f, 0f), KeyProximity.touch('e', 0f, 0f))
            .map { p -> p?.let { Corrector.Touch(it.first, it.second) } }
        val best = corrector.candidates("tge", touches = taps).first { it.word == "the" }
        assertTrue("cost ${best.cost}", best.cost < 0.3f)
        val blind = corrector.candidates("tge").first { it.word == "the" }
        assertTrue(best.cost < blind.cost)
    }

    /** A tap in the middle of a far key doesn't get a neighbour's discount. */
    @Test fun tapAtKeyCentreKeepsLetter() {
        val taps = listOf(KeyProximity.touch('t', 0f, 0f), KeyProximity.touch('g', -0.4f, 0f), KeyProximity.touch('e', 0f, 0f))
            .map { p -> p?.let { Corrector.Touch(it.first, it.second) } }
        val near = corrector.candidates("tge", touches = taps).firstOrNull { it.word == "the" }
        val edge = corrector.candidates("tge", touches = listOf(taps[0], KeyProximity.touch('g', 0.5f, 0f)?.let { Corrector.Touch(it.first, it.second) }, taps[2])).first { it.word == "the" }
        assertTrue(near == null || near.cost > edge.cost)
    }

    /** Words learned after the previous word win close calls. */
    @Test fun contextBreaksTies() = assertEquals("work", corrector.autocorrect("worl", context = setOf("work")))
}
