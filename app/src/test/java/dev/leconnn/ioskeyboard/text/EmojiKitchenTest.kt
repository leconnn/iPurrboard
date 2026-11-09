package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Runs against the real emoji_kitchen.dat asset. */
class EmojiKitchenTest {
    companion object {
        lateinit var kitchen: EmojiKitchen

        @BeforeClass @JvmStatic fun load() {
            val f = listOf("src/main/assets/emoji_kitchen.dat", "app/src/main/assets/emoji_kitchen.dat").map(::File).first { it.exists() }
            kitchen = f.inputStream().use { EmojiKitchen.read(it) }
        }
    }

    @Test fun knownEmoji() = assertTrue(kitchen.has("😂") && kitchen.has("❤") && kitchen.has("❤️"))

    @Test fun pairFirst() {
        val s = kitchen.stickers("😂", "☕").first()
        assertEquals("https://www.gstatic.com/android/keyboard/emojikitchen/20230301/u1f602/u1f602_u2615.png", s.url)
    }

    @Test fun pairEitherOrder() {
        val s = kitchen.stickers("☕", "😂").first()
        assertTrue(s.url.endsWith("u1f602_u2615.png"))
    }

    @Test fun manyForPopularEmoji() = assertEquals(16, kitchen.stickers("😂").size)

    @Test fun unknownEmoji() = assertTrue(kitchen.stickers("🫥🫥").isEmpty())
}
