package dev.leconnn.ioskeyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves copies of clipboard images (screenshots) to the app they are pasted into. The keyboard
 * can read the photo library but can't pass that access on, so a pasted image is copied into the
 * keyboard's cache and offered from here, read-only, with a one-time grant (commitContent).
 */
class ClipImageProvider : ContentProvider() {

    override fun onCreate() = true

    override fun getType(uri: Uri): String = mimeFor(uri.lastPathSegment.orEmpty())

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Name and size, for apps that ask before reading. */
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { c ->
                when (c) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(dir(context ?: return null), name).takeIf { it.isFile }
    }

    companion object {
        private fun authority(context: Context) = "${context.packageName}.clipimages"
        private fun dir(context: Context) = File(context.cacheDir, "clip_images")

        private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/png"
        }

        /** Like [share], for image bytes already in memory (a downloaded GIF). */
        fun shareBytes(context: Context, bytes: ByteArray, mime: String, name: String): Uri? = runCatching {
            val d = dir(context).apply { mkdirs() }
            d.listFiles()?.forEach { it.delete() }
            val ext = when (mime) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "png"
            }
            val f = File(d, "${name}_${System.currentTimeMillis()}.$ext")
            f.writeBytes(bytes)
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(f.name).build()
        }.getOrNull()

        /**
         * Copies the image at [source] into the cache and returns a URI this provider serves it
         * from, or null if it couldn't be read. Older copies are removed. Call off the main thread.
         */
        fun share(context: Context, source: Uri, mime: String): Uri? = runCatching {
            val d = dir(context).apply { mkdirs() }
            d.listFiles()?.forEach { it.delete() }
            val ext = when (mime) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "png"
            }
            val f = File(d, "screenshot_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(source)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                ?: return null
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(f.name).build()
        }.getOrNull()
    }
}
