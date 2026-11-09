package dev.leconnn.ioskeyboard.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Runs against the real emoji_search.tsv asset. */
class EmojiSearchTest {
    companion object {
        lateinit var search: EmojiSearch

        @BeforeClass @JvmStatic fun load() {
            val f = listOf("src/main/assets/emoji_search.tsv", "app/src/main/assets/emoji_search.tsv").map(::File).first { it.exists() }
            search = EmojiSearch.parse(f.readLines(Charsets.UTF_8).asSequence())
        }
    }

    @Test fun nameMatchFirst() = assertEquals("🍕", search.search("pizza").first())

    @Test fun prefixWhileTyping() = assertTrue(search.search("piz").contains("🍕"))

    @Test fun everyWordMustMatch() {
        val r = search.search("red heart")
        assertEquals("❤️", r.first())
        assertTrue(r.none { it == "💙" })
    }

    @Test fun keywordsCount() = assertTrue(search.search("lol").contains("😂"))

    @Test fun noMatch() = assertTrue(search.search("zzqx").isEmpty())
}
