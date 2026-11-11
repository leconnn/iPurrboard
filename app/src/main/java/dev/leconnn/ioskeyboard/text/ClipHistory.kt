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
 * restarts, written on a background thread. Text only.
 */
class ClipHistory private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext.createDeviceProtectedStorageContext(), "clips.db", null, 2,
) {
    /** A copied text, or a screenshot ([image] is its content URI; [text] is then empty). */
    data class Clip(val id: Long, val text: String, val at: Long, val pinned: Boolean, val image: String? = null)

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
        db.execSQL("CREATE TABLE clips (id INTEGER PRIMARY KEY, text TEXT NOT NULL, at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, image TEXT)")
        db.execSQL("CREATE TABLE dismissed (uri TEXT PRIMARY KEY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Version 2: screenshots in the clipboard.
            db.execSQL("ALTER TABLE clips ADD COLUMN image TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS dismissed (uri TEXT PRIMARY KEY)")
        }
    }

    private fun load() {
        readableDatabase.rawQuery("SELECT id, text, at, pinned, image FROM clips ORDER BY at DESC", null).use { c ->
            synchronized(this) {
                while (c.moveToNext()) {
                    clips.add(Clip(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) != 0, c.getString(4)))
                }
                nextId = (clips.maxOfOrNull { it.id } ?: 0L) + 1
            }
        }
        readableDatabase.rawQuery("SELECT uri FROM dismissed", null).use { c ->
            synchronized(this) { while (c.moveToNext()) dismissed.add(c.getString(0)) }
        }
    }

    /** Recent (unpinned) first, then pinned, each newest first; expired unpinned clips are dropped on the way. */
    @Synchronized
    fun items(now: Long = System.currentTimeMillis()): List<Clip> {
        awaitLoad()
        purge(now)
        return clips.sortedWith(compareBy<Clip> { it.pinned }.thenByDescending { it.at })
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
        synchronized(this) {
            replaced = clips.firstOrNull { it.text == text }
            if (replaced != null) clips.remove(replaced)
            clip = Clip(nextId++, text, now, replaced?.pinned ?: false)
            clips.add(clip)
            while (clips.count { !it.pinned } > MAX_UNPINNED) clips.remove(clips.filter { !it.pinned }.minBy { it.at })
        }
        writer.execute {
            val db = writableDatabase
            replaced?.let { db.delete("clips", "id = ?", arrayOf(it.id.toString())) }
            db.insert("clips", null, ContentValues().apply {
                put("id", clip.id); put("text", clip.text); put("at", clip.at); put("pinned", if (clip.pinned) 1 else 0)
            })
        }
        return clip
    }

    /**
     * Records a screenshot taken at [at] (its content URI), unless it is already here, was
     * deleted by the user, or is older than the history keeps. Returns true if it was added.
     */
    fun addImage(uri: String, at: Long, now: Long = System.currentTimeMillis()): Boolean {
        awaitLoad()
        val clip: Clip
        synchronized(this) {
            if (uri in dismissed || at < now - retentionMs || clips.any { it.image == uri }) return false
            clip = Clip(nextId++, "", at, false, uri)
            clips.add(clip)
            while (clips.count { !it.pinned } > MAX_UNPINNED) clips.remove(clips.filter { !it.pinned }.minBy { it.at })
        }
        writer.execute {
            writableDatabase.insert("clips", null, ContentValues().apply {
                put("id", clip.id); put("text", ""); put("at", clip.at); put("pinned", 0); put("image", uri)
            })
        }
        return true
    }

    /** Remembers removed screenshots so the next scan of the photo library skips them. */
    private fun dismiss(removed: List<Clip>) {
        val uris = removed.mapNotNull { it.image }
        if (uris.isEmpty()) return
        synchronized(this) { dismissed.addAll(uris) }
        writer.execute {
            val db = writableDatabase
            for (u in uris) db.insertWithOnConflict("dismissed", null, ContentValues().apply { put("uri", u) },
                SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        awaitLoad()
        synchronized(this) {
            val i = clips.indexOfFirst { it.id == id }
            if (i < 0) return
            clips[i] = clips[i].copy(pinned = pinned)
        }
        writer.execute { writableDatabase.execSQL("UPDATE clips SET pinned = ? WHERE id = ?", arrayOf(if (pinned) 1 else 0, id)) }
    }

    fun delete(id: Long) {
        awaitLoad()
        val removed = synchronized(this) { clips.filter { it.id == id }.also { clips.removeAll(it.toSet()) } }
        dismiss(removed)
        writer.execute { writableDatabase.delete("clips", "id = ?", arrayOf(id.toString())) }
    }

    /** Removes every unpinned clip (Clear, or turning Clipboard History off). */
    fun clearUnpinned() {
        awaitLoad()
        val removed = synchronized(this) { clips.filter { !it.pinned }.also { clips.removeAll(it.toSet()) } }
        dismiss(removed)
        writer.execute { writableDatabase.delete("clips", "pinned = 0", null) }
    }

    private fun purge(now: Long) {
        val cutoff = now - retentionMs
        if (clips.none { !it.pinned && it.at < cutoff }) return
        clips.removeAll { !it.pinned && it.at < cutoff }
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
