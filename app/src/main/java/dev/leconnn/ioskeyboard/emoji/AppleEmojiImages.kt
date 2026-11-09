package dev.leconnn.ioskeyboard.emoji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Apple emoji art from assets/apple_emoji.bin, an optional, personal-use file built by
 * tools/emoji/extract_apple_emoji.py from the user's IPSW (gitignored). The file is stored
 * uncompressed in the APK and memory-mapped; images are decoded on demand and cached.
 */
class AppleEmojiImages private constructor(private val data: MappedByteBuffer, private val index: AppleEmojiIndex) {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun has(emoji: String) = index.find(emoji) != null

    /** Draws [emoji] into [dst]; returns false (drawing nothing) if the file doesn't have it. */
    fun draw(canvas: Canvas, emoji: String, dst: RectF, filterPaint: Paint? = null): Boolean {
        val bmp = bitmap(emoji) ?: return false
        canvas.drawBitmap(bmp, null, dst, filterPaint ?: paint)
        return true
    }

    private fun bitmap(emoji: String): Bitmap? {
        cache.get(emoji)?.let { return it }
        val (offset, length) = index.find(emoji) ?: return null
        val bytes = ByteArray(length)
        synchronized(data) { data.position(offset); data.get(bytes) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, length) ?: return null
        cache.put(emoji, bmp)
        return bmp
    }

    companion object {
        private const val ASSET = "apple_emoji.bin"
        private const val CACHE_BYTES = 12 * 1024 * 1024

        /** Opens the asset if this build includes it; null otherwise. */
        fun open(context: Context): AppleEmojiImages? = runCatching {
            context.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    val map = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    AppleEmojiIndex.parse(map)?.let { AppleEmojiImages(map, it) }
                }
            }
        }.getOrNull()

        /** True if the build contains the asset (cheap; used to show the settings switch). */
        fun available(context: Context): Boolean =
            runCatching { context.assets.openFd(ASSET).use { true } }.getOrDefault(false)
    }
}
