package dev.leconnn.ioskeyboard.text

import dev.leconnn.ioskeyboard.text.ClipEntities.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipEntitiesTest {
    private fun first(text: String) = ClipEntities.find(text).firstOrNull()

    @Test fun linkInSentence() =
        assertEquals(ClipEntities.Entity(Kind.LINK, "https://example.com/a?b=1"), first("See https://example.com/a?b=1, thanks"))

    @Test fun email() = assertEquals(ClipEntities.Entity(Kind.EMAIL, "jo.doe@mail.co"), first("Write to jo.doe@mail.co today"))

    @Test fun internationalPhone() = assertEquals(Kind.PHONE, first("Call me on +63 917 123 4567 later")?.kind)

    @Test fun usPhone() = assertEquals(ClipEntities.Entity(Kind.PHONE, "(555) 123-4567"), first("Office: (555) 123-4567"))

    @Test fun verificationCode() =
        assertEquals(ClipEntities.Entity(Kind.CODE, "482913"), first("Your verification code is 482913. Don't share it."))

    @Test fun streetAddress() =
        assertEquals(Kind.ADDRESS, first("Meet at\n1600 Pennsylvania Avenue NW\nWashington")?.kind)

    @Test fun plainSentenceHasNothing() = assertTrue(ClipEntities.find("See you at the cafe at noon").isEmpty())

    @Test fun dateIsNotAPhone() = assertTrue(ClipEntities.find("Due 2024-01-02 please").isEmpty())

    @Test fun numberWithoutCodeWordsIsNotACode() = assertTrue(ClipEntities.find("Room 4821 is free").isEmpty())

    @Test fun wholeTextIsNotRepeated() = assertTrue(ClipEntities.find("https://example.com").isEmpty())
}
