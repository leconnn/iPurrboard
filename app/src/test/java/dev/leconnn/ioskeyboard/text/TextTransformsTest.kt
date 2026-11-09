package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartPunctuationTest {
    @Test fun openingQuoteAtStart() = assertEquals(Edit(0, "“"), SmartPunctuation.transform("", "\""))
    @Test fun openingQuoteAfterSpace() = assertEquals(Edit(0, "“"), SmartPunctuation.transform("say ", "\""))
    @Test fun closingQuoteAfterWord() = assertEquals(Edit(0, "”"), SmartPunctuation.transform("say “hi", "\""))
    @Test fun openingQuoteAfterBracket() = assertEquals(Edit(0, "“"), SmartPunctuation.transform("(", "\""))
    @Test fun apostropheInWord() = assertEquals(Edit(0, "’"), SmartPunctuation.transform("don", "'"))
    @Test fun openingSingleQuote() = assertEquals(Edit(0, "‘"), SmartPunctuation.transform("a ", "'"))
    @Test fun doubleHyphenToEmDash() = assertEquals(Edit(1, "—"), SmartPunctuation.transform("a -", "-"))
    @Test fun singleHyphenUnchanged() = assertNull(SmartPunctuation.transform("a ", "-"))
    @Test fun lettersUnchanged() = assertNull(SmartPunctuation.transform("a", "b"))
}

class MathCompletionTest {
    @Test fun multiply() = assertEquals("48", MathCompletion.resultFor("12*4="))
    @Test fun power() = assertEquals("25", MathCompletion.resultFor("(2+3)^2="))
    @Test fun decimalDivision() = assertEquals("2.5", MathCompletion.resultFor("10/4="))
    @Test fun unicodeOperators() = assertEquals("12", MathCompletion.resultFor("3×8÷2="))
    @Test fun precedence() = assertEquals("14", MathCompletion.resultFor("2+3*4="))
    @Test fun percent() = assertEquals("25", MathCompletion.resultFor("50%*50="))
    @Test fun thousandsSeparators() = assertEquals("2000", MathCompletion.resultFor("1,000*2="))
    @Test fun expressionAfterWords() = assertEquals("7", MathCompletion.resultFor("total is 3+4="))
    @Test fun repeatingDecimal() = assertEquals("0.3333333333", MathCompletion.resultFor("1/3="))
    @Test fun negativeResult() = assertEquals("-2", MathCompletion.resultFor("3-5="))
    @Test fun loneNumber() = assertNull(MathCompletion.resultFor("5="))
    @Test fun signedNumber() = assertNull(MathCompletion.resultFor("-5="))
    @Test fun words() = assertNull(MathCompletion.resultFor("abc="))
    @Test fun noEquals() = assertNull(MathCompletion.resultFor("12*4"))
    @Test fun divisionByZero() = assertNull(MathCompletion.resultFor("1/0="))
    @Test fun unbalanced() = assertNull(MathCompletion.resultFor("(1+2="))
}
