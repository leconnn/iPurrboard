package dev.leconnn.ioskeyboard.emoji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Emoji images drawn instead of the system emoji font, from an optional image pack built into
 * the app. Standard builds have none, so the emoji panel uses the system font.
 */
interface EmojiArt {
    fun has(emoji: String): Boolean

    /** Draws [emoji] into [dst]; returns false (drawing nothing) if the pack doesn't have it. */
    fun draw(canvas: Canvas, emoji: String, dst: RectF, filterPaint: Paint? = null): Boolean

    /** An image pack a build can include: class [PACK_CLASS] with a no-argument constructor. */
    interface Source {
        /** Name of the Settings switch that turns the pack on. */
        val name: String
        fun available(context: Context): Boolean
        fun open(context: Context): EmojiArt?
    }

    companion object {
        private const val PACK_CLASS = "dev.leconnn.ioskeyboard.emoji.EmojiArtPack"

        private val source: Source? by lazy {
            runCatching { Class.forName(PACK_CLASS).getDeclaredConstructor().newInstance() as Source }.getOrNull()
        }

        /** True if this build includes an image pack (shows its switch in Settings). */
        fun available(context: Context) = source?.available(context) == true

        fun open(context: Context): EmojiArt? = source?.open(context)

        val name: String get() = source?.name ?: "Emoji Images"
    }
}
