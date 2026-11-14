package dev.leconnn.ioskeyboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/**
 * Recent screenshots for the clipboard (Settings > Clipboard > Save Recent Screenshots), like
 * Gboard's: found in the photo library by their folder name, newest first.
 */
object Screenshots {

    /** The permission that lets the keyboard read the photo library. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** True when photos can be read (all of them, or the ones the user picked on Android 14+). */
    fun granted(context: Context): Boolean {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true
        return Build.VERSION.SDK_INT >= 34 &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }

    /** Screenshots added since [sinceMs] (wall clock), newest first, as (content URI, time in ms). */
    fun recent(context: Context, sinceMs: Long, limit: Int = 10): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folder = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND $folder LIKE ?"
        val args = arrayOf((sinceMs / 1000).toString(), "%screenshot%")
        runCatching {
            context.contentResolver.query(
                images, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += ContentUris.withAppendedId(images, c.getLong(0)).toString() to c.getLong(1) * 1000
                }
            }
        }
        return out
    }

    /** A small version of the image for the clipboard panel, or null if it is gone or unreadable. */
    fun thumbnail(context: Context, uri: String, px: Int): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        if (u.scheme == "file") {
            // A copied image the keyboard keeps (see ClipImages): decoded at about [px].
            val path = u.path ?: return@runCatching null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            return@runCatching android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(u, Size(px, px), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(u),
                MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }.getOrNull()
}
