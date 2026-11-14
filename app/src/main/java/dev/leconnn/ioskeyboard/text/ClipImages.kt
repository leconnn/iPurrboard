package dev.leconnn.ioskeyboard.text

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * Copied images in the clipboard history. An app that copies an image only lends the keyboard
 * access to it while it is on the clipboard, so the keyboard keeps its own copy (in device-protected
 * storage, beside the history) for as long as the clip is kept. Files are named by their content,
 * so copying the same image again finds the same clip.
 */
object ClipImages {

    private const val DIR = "copied_images"
    /** Larger images aren't kept (the clip still pastes while it is on the clipboard). */
    private const val MAX_BYTES = 25L * 1024 * 1024

    private fun dir(context: Context) = File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, DIR)

    /**
     * Copies the image at [source] into the keyboard's storage and returns its URI ("file:…"), or
     * null if it couldn't be read or is too large. Call off the main thread.
     */
    fun store(context: Context, source: Uri, mime: String): String? = runCatching {
        val d = dir(context).apply { mkdirs() }
        val tmp = File(d, ".incoming")
        val digest = MessageDigest.getInstance("SHA-1")
        var size = 0L
        context.contentResolver.openInputStream(source)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > MAX_BYTES) return@runCatching null.also { tmp.delete() }
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        } ?: return@runCatching null
        if (size == 0L) return@runCatching null.also { tmp.delete() }
        val name = digest.digest().joinToString("") { "%02x".format(it) } + "." + extension(mime)
        val f = File(d, name)
        if (f.exists()) tmp.delete() else if (!tmp.renameTo(f)) return@runCatching null
        Uri.fromFile(f).toString()
    }.getOrNull()

    /** Deletes the files of copied images among [removed] clips (screenshots stay in the photo library). */
    fun discard(removed: List<ClipHistory.Clip>) {
        for (c in removed) if (c.copiedImage) runCatching { Uri.parse(c.image).path?.let { File(it).delete() } }
    }

    /** The image type of a stored copy, from its name. */
    fun mimeOf(uri: String) = when (uri.substringAfterLast('.', "").lowercase()) {
        "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    private fun extension(mime: String) = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }
}
