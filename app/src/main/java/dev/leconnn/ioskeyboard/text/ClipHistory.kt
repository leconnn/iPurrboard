package dev.leconnn.ioskeyboard.text

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/**
 * Recently copied text for the clipboard panel and the copied-item chip (Gboard's model):
 * unpinned items are kept for [retentionMs] (Settings > Keep History For, default 1 hour), pinned
 * ones until unpinned. Mirrored in memory; SQLite (device-protected storage) keeps them across
 * restarts, written on a background thread. Copied images and screenshots are kept as image clips
 * (copied images as a private copy, see [ClipImages]); pinned clips keep the order the user dragged
 * them into.
 */
class ClipHistory private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext.createDeviceProtectedStorageContext(), "clips.db", null, 3,
) {
    /**
     * A copied text, or an image ([image]: a screenshot's content URI, or a copied image's private
     * file; [text] is then empty). [pos] orders pinned clips (lowest first).
     */
    data class Clip(val id: Long, val text: String, val at: Long, val pinned: Boolean, val image: String? = null, val pos: Long = 0) {
        /** A copied image (kept as the keyboard's own file), not a screenshot from the photo library. */
        val copiedImage get() = image?.startsWith("file:") == true
    }

    private val writer = Executors.newSingleThreadExecutor()
    private val clips = ArrayList<Clip>()
    /** Screenshots the user deleted or cleared, so a later scan doesn't bring them back. */
    private val dismissed = HashSet<String>()
    private var nextId = 1L

    /** How long unpinned clips are kept. */
    @Volatile var retentionMs = HOUR_MS

    /**
     * Opened when the saved clips are in memory. Every read and change waits for it, so a clip
     * added while the keyboard starts (it reads the clipboard at once) can't take an id the saved
     * clips use, be dropped on save, or show twice.
     */
    private val loaded = java.util.concurrent.CountDownLatch(1)

    private fun awaitLoad() { loaded.await(LOAD_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) }

    init {
        setWriteAheadLoggingEnabled(true)
        writer.execute { try { load() } finally { loaded.countDown() } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clips (id INTEGER PRIMARY KEY, text TEXT NOT NULL, at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, image TEXT, pos INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE dismissed (uri TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 2: screenshots in the clipboard.
            db.execSQL("ALTER TABLE clips ADD COLUMN image TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS dismissed (uri TEXT PRIMARY KEY)")
        }
        // Version 3: pinned clips in the order the user put them.
        if (oldVersion < 3) db.execSQL("ALTER TABLE clips ADD COLUMN pos INTEGER NOT NULL DEFAULT 0")
    }

    private fun load() {
        readableDatabase.rawQuery("SELECT id, text, at, pinned, image, pos FROM clips ORDER BY at DESC", null).use { c ->
            synchronized(this) {
                while (c.moveToNext()) {
                    clips.add(Clip(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) != 0, c.getString(4), c.getLong(5)))
                }
                nextId = (clips.maxOfOrNull { it.id } ?: 0L) + 1
            }
        }
        readableDatabase.rawQuery("SELECT uri FROM dismissed", null).use { c ->
            synchronized(this) { while (c.moveToNext()) dismissed.add(c.getString(0)) }
        }
    }

    /**
     * Recent (unpinned, newest first) and pinned (in the user's order), recent first unless
     * [pinnedFirst]; expired unpinned clips are dropped on the way.
     */
    @Synchronized
    fun items(pinnedFirst: Boolean = false, now: Long = System.currentTimeMillis()): List<Clip> {
        awaitLoad()
        purge(now)
        val recent = clips.filter { !it.pinned }.sortedByDescending { it.at }
        val pinned = clips.filter { it.pinned }.sortedWith(compareBy<Clip> { it.pos }.thenByDescending { it.at })
        return if (pinnedFirst) pinned + recent else recent + pinned
    }

    /** The most recent clip (pinned or not), if any. */
    @Synchronized
    fun latest(now: Long = System.currentTimeMillis()): Clip? {
        awaitLoad()
        purge(now)
        return clips.maxByOrNull { it.at }
    }

    /** Records copied [text]; copying the same text again moves it to the top. */
    fun add(text: String, now: Long = System.currentTimeMillis()): Clip? {
        awaitLoad()
        if (text.isBlank()) return null
        val clip: Clip
        val replaced: Clip?
        val overflow: List<Clip>
        synchronized(this) {
            replaced = clips.firstOrNull { it.text == text && it.image == null }
            if (replaced != null) clips.remove(replaced)
            clip = Clip(nextId++, text, now, replaced?.pinned ?: false, pos = replaced?.pos ?: 0)
            clips.add(clip)
            overflow = trim()
        }
        writer.execute {
            val db = writableDatabase
            replaced?.let { db.delete("clips", "id = ?", arrayOf(it.id.toString())) }
            for (o in overflow) db.delete("clips", "id = ?", arrayOf(o.id.toString()))
            db.insert("clips", null, ContentValues().apply {
                put("id", clip.id); put("text", clip.text); put("at", clip.at); put("pinned", if (clip.pinned) 1 else 0); put("pos", clip.pos)
            })
        }
        ClipImages.discard(overflow)
        return clip
    }

    /**
     * Records an image taken or copied at [at]: a screenshot's content URI, or a copied image's
     * private file. Skipped if it is already here, was deleted by the user, or is older than the
     * history keeps; with [renew] (it was just copied again) it comes back and moves to the top,
     * like text copied again. Returns true if the list changed.
     */
    fun addImage(uri: String, at: Long, now: Long = System.currentTimeMillis(), renew: Boolean = false): Boolean {
        awaitLoad()
        val clip: Clip
        val replaced: Clip?
        val overflow: List<Clip>
        synchronized(this) {
            if (renew) dismissed.remove(uri)
            replaced = clips.firstOrNull { it.image == uri }
            if (uri in dismissed || at < now - retentionMs || (replaced != null && !renew)) return false
            if (replaced != null) clips.remove(replaced)
            clip = Clip(nextId++, "", at, replaced?.pinned ?: false, uri, replaced?.pos ?: 0)
            clips.add(clip)
            overflow = trim()
        }
        writer.execute {
            val db = writableDatabase
            if (renew) db.delete("dismissed", "uri = ?", arrayOf(uri))
            replaced?.let { db.delete("clips", "id = ?", arrayOf(it.id.toString())) }
            for (o in overflow) db.delete("clips", "id = ?", arrayOf(o.id.toString()))
            db.insert("clips", null, ContentValues().apply {
                put("id", clip.id); put("text", ""); put("at", clip.at); put("pinned", if (clip.pinned) 1 else 0)
                put("image", uri); put("pos", clip.pos)
            })
        }
        ClipImages.discard(overflow)
        return true
    }

    /** Drops the oldest unpinned clips past [MAX_UNPINNED] (call holding the lock); returns them. */
    private fun trim(): List<Clip> {
        val out = ArrayList<Clip>()
        while (clips.count { !it.pinned } > MAX_UNPINNED) {
            val old = clips.filter { !it.pinned }.minBy { it.at }
            clips.remove(old)
            out += old
        }
        return out
    }

    /** Remembers removed screenshots so the next scan of the photo library skips them. */
    private fun dismiss(removed: List<Clip>) {
        val uris = removed.filter { !it.copiedImage }.mapNotNull { it.image }
        if (uris.isEmpty()) return
        synchronized(this) { dismissed.addAll(uris) }
        writer.execute {
            val db = writableDatabase
            for (u in uris) db.insertWithOnConflict("dismissed", null, ContentValues().apply { put("uri", u) },
                SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    /** Pins (to the top of the pinned clips) or unpins a clip. */
    fun setPinned(id: Long, pinned: Boolean) {
        awaitLoad()
        val pos: Long
        synchronized(this) {
            val i = clips.indexOfFirst { it.id == id }
            if (i < 0) return
            pos = if (pinned) (clips.filter { it.pinned }.minOfOrNull { it.pos } ?: 1L) - 1 else 0L
            clips[i] = clips[i].copy(pinned = pinned, pos = pos)
        }
        writer.execute { writableDatabase.execSQL("UPDATE clips SET pinned = ?, pos = ? WHERE id = ?", arrayOf(if (pinned) 1 else 0, pos, id)) }
    }

    /** Puts the pinned clips in the order of [ids] (dragged in the clipboard). */
    fun reorderPinned(ids: List<Long>) {
        awaitLoad()
        synchronized(this) {
            for ((n, id) in ids.withIndex()) {
                val i = clips.indexOfFirst { it.id == id && it.pinned }
                if (i >= 0) clips[i] = clips[i].copy(pos = n.toLong())
            }
        }
        writer.execute {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for ((n, id) in ids.withIndex()) db.execSQL("UPDATE clips SET pos = ? WHERE id = ? AND pinned = 1", arrayOf(n, id))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun delete(id: Long) {
        awaitLoad()
        val removed = synchronized(this) { clips.filter { it.id == id }.also { clips.removeAll(it.toSet()) } }
        dismiss(removed)
        ClipImages.discard(removed)
        writer.execute { writableDatabase.delete("clips", "id = ?", arrayOf(id.toString())) }
    }

    /** Removes every unpinned clip (Clear, or turning Clipboard History off). */
    fun clearUnpinned() {
        awaitLoad()
        val removed = synchronized(this) { clips.filter { !it.pinned }.also { clips.removeAll(it.toSet()) } }
        dismiss(removed)
        ClipImages.discard(removed)
        writer.execute { writableDatabase.delete("clips", "pinned = 0", null) }
    }

    private fun purge(now: Long) {
        val cutoff = now - retentionMs
        val expired = clips.filter { !it.pinned && it.at < cutoff }
        if (expired.isEmpty()) return
        clips.removeAll(expired.toSet())
        ClipImages.discard(expired)
        writer.execute { writableDatabase.delete("clips", "pinned = 0 AND at < ?", arrayOf(cutoff.toString())) }
    }

    companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        /** Longest wait for the saved data (it takes milliseconds; this only guards a stuck disk). */
        private const val LOAD_WAIT_S = 3L
        private const val MAX_UNPINNED = 50
        @Volatile private var instance: ClipHistory? = null

        fun get(context: Context): ClipHistory = instance ?: synchronized(this) {
            instance ?: ClipHistory(context.applicationContext).also { instance = it }
        }
    }
}
