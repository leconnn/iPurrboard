package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class RecapitalizeTest {
    private val en = Locale.US

    @Test fun mixedCaseCyclesThroughAllFourAndBack() {
        val r = Recapitalize("hello World", en)
        assertEquals("hello world", r.next())
        assertEquals("Hello World", r.next())
        assertEquals("HELLO WORLD", r.next())
        assertEquals("hello World", r.next())
    }

    @Test fun lowercaseStartsAtLowerSoFirstPressIsTitle() {
        val r = Recapitalize("good morning", en)
        assertEquals("Good Morning", r.next())
        assertEquals("GOOD MORNING", r.next())
        assertEquals("good morning", r.next())
    }

    @Test fun allCapsGoesToLowercase() {
        assertEquals("nasa", Recapitalize("NASA", en).next())
    }

    @Test fun titleCaseKeepsContractionsAndHyphensAsOneWord() {
        assertEquals("Don't Stop Well-known Ones", Recapitalize.titleCase("don't STOP well-known ones", en))
    }

    @Test fun nothingToChangeStaysPut() {
        val r = Recapitalize("123 !?", en)
        assertEquals("123 !?", r.next())
    }

    @Test fun singleLetterSkipsDuplicateModes() {
        // "a": lower = original, title = upper = "A"; the cycle is just a ↔ A.
        val r = Recapitalize("a", en)
        assertEquals("A", r.next())
        assertEquals("a", r.next())
    }
}
