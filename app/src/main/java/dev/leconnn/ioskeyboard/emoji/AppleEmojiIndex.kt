package dev.leconnn.ioskeyboard.emoji

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Index of apple_emoji.bin (written by tools/emoji/extract_apple_emoji.py):
 * "AEMJ", u32 version, u32 count, u32 pixel size, then count x (u16 key length, UTF-8 key,
 * u32 offset, u32 length), little-endian. Offsets are from the start of the file.
 */
class AppleEmojiIndex private constructor(val pixelSize: Int, private val entries: Map<String, Long>) {

    /** (offset, length) of the image for [emoji], or null if the file doesn't have it. */
    fun find(emoji: String): Pair<Int, Int>? = entries[emoji]?.let { (it ushr 32).toInt() to it.toInt() }

    val size get() = entries.size

    companion object {
        private const val VERSION = 1

        /** Parses the header and index; returns null if [buf] is not a version-1 file. */
        fun parse(buf: ByteBuffer): AppleEmojiIndex? {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (b.remaining() < 16) return null
            val magic = ByteArray(4).also { b.get(it) }
            if (!magic.contentEquals("AEMJ".toByteArray()) || b.int != VERSION) return null
            val count = b.int
            val pixelSize = b.int
            val entries = HashMap<String, Long>(count * 2)
            repeat(count) {
                val key = ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) }
                val offset = b.int.toLong() and 0xFFFFFFFFL
                val length = b.int.toLong() and 0xFFFFFFFFL
                entries[String(key, Charsets.UTF_8)] = (offset shl 32) or length
            }
            return AppleEmojiIndex(pixelSize, entries)
        }
    }
}
