package dev.leconnn.ioskeyboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AppleEmojiIndexTest {
    private fun file(entries: List<Pair<String, ByteArray>>, magic: String = "AEMJ", version: Int = 1): ByteBuffer {
        val keys = entries.map { it.first.toByteArray(Charsets.UTF_8) }
        val headerSize = 16 + keys.sumOf { 2 + it.size + 8 }
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        head.put(magic.toByteArray()).putInt(version).putInt(entries.size).putInt(96)
        var offset = headerSize
        entries.forEachIndexed { i, (_, data) ->
            head.putShort(keys[i].size.toShort()).put(keys[i]).putInt(offset).putInt(data.size)
            offset += data.size
        }
        out.write(head.array())
        entries.forEach { out.write(it.second) }
        return ByteBuffer.wrap(out.toByteArray())
    }

    @Test fun findsEntries() {
        val buf = file(listOf("😀" to ByteArray(10) { 1 }, "👋🏼" to ByteArray(7) { 2 }))
        val index = AppleEmojiIndex.parse(buf)!!
        assertEquals(2, index.size)
        assertEquals(96, index.pixelSize)
        val headerSize = 16 + (2 + 4 + 8) + (2 + 8 + 8)
        assertEquals(headerSize to 10, index.find("😀"))
        assertEquals(headerSize + 10 to 7, index.find("👋🏼"))
        assertNull(index.find("🐢"))
    }

    @Test fun rejectsWrongMagic() = assertNull(AppleEmojiIndex.parse(file(emptyList(), magic = "NOPE")))

    @Test fun rejectsWrongVersion() = assertNull(AppleEmojiIndex.parse(file(emptyList(), version = 2)))
}
