package dev.leconnn.ioskeyboard.text

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/**
 * What the keyboard learns from typing: words outside the dictionary (known after [LEARN_AFTER]
 * commits), word pairs for next-word prediction, and words whose autocorrection was undone (never
 * corrected again). SQLite in device-protected storage; writes go to a background thread and
 * counts are mirrored in memory so lookups never touch the disk on the typing path.
 */
class UserWords private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext.createDeviceProtectedStorageContext(), "userwords.db", null, 2,
) {
    private val writer = Executors.newSingleThreadExecutor()
    private val counts = HashMap<String, Int>()
    private val rejectedWords = HashSet<String>()
    private val pairs = HashMap<String, HashMap<String, Int>>()
    /** How a learned word is written when that isn't all lowercase ("Wiśniewska", "iPhone"). */
    private val shown = HashMap<String, String>()

    val rejected: Set<String> get() { awaitLoad(); return rejectedWords }

    /** Opened when the saved data is in memory; changes made before then would be overwritten. */
    private val loaded = java.util.concurrent.CountDownLatch(1)

    private fun awaitLoad() { loaded.await(LOAD_WAIT_S, java.util.concurrent.TimeUnit.SECONDS) }

    init {
        // Write-ahead logging: saving learned words doesn't block reads (Gboard uses WAL for its
        // learning database too).
        setWriteAheadLoggingEnabled(true)
        writer.execute { try { load() } finally { loaded.countDown() } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE words (word TEXT PRIMARY KEY, count INTEGER NOT NULL, rejected INTEGER NOT NULL DEFAULT 0, shown TEXT)")
        db.execSQL("CREATE TABLE pairs (prev TEXT NOT NULL, word TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (prev, word))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2: how each word is written (names keep their capitals).
        if (oldVersion < 2) db.execSQL("ALTER TABLE words ADD COLUMN shown TEXT")
    }

    private fun load() {
        readableDatabase.rawQuery("SELECT word, count, rejected, shown FROM words", null).use { c ->
            while (c.moveToNext()) synchronized(this) {
                counts[c.getString(0)] = c.getInt(1)
                if (c.getInt(2) != 0) rejectedWords.add(c.getString(0))
                c.getString(3)?.let { shown[c.getString(0)] = it }
            }
        }
        readableDatabase.rawQuery("SELECT prev, word, count FROM pairs", null).use { c ->
            while (c.moveToNext()) synchronized(this) {
                pairs.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getInt(2)
            }
        }
    }

    /** Usage count of a word the user has committed enough times to count as known, else 0. */
    fun learnedFrequency(word: String): Int {
        awaitLoad()
        return synchronized(this) { counts[word.lowercase()]?.takeIf { it >= LEARN_AFTER } ?: 0 }
    }

    fun nextWords(prev: String?, n: Int = 3): List<String> {
        awaitLoad()
        synchronized(this) {
            val next = pairs[prev?.lowercase() ?: return emptyList()] ?: return emptyList()
            return next.entries.sortedByDescending { it.value }.take(n).map { shown[it.key] ?: it.key }
        }
    }

    /** How [w] (lowercase) is written. */
    private fun display(w: String) = shown[w] ?: w

    /**
     * Notes how a word is written: with capitals typed mid-sentence (after [prev]) it's a name
     * ("Wiśniewska"); typed in lowercase mid-sentence it's an ordinary word again. A capital at
     * the start of a sentence says nothing, so it's ignored.
     */
    private fun noteCase(prev: String?, word: String, w: String): Boolean {
        if (prev == null) return false
        val changed = if (word != w) shown.put(w, word) != word else shown.remove(w) != null
        return changed
    }

    /** Records that [word] was typed (after [prev], if any). */
    fun commit(prev: String?, word: String) {
        awaitLoad()
        val w = word.lowercase()
        val p = prev?.lowercase()
        val caseChanged: Boolean
        synchronized(this) {
            counts[w] = (counts[w] ?: 0) + 1
            if (p != null) pairs.getOrPut(p) { HashMap() }.merge(w, 1, Int::plus)
            caseChanged = noteCase(prev, word, w)
        }
        val shownNow = synchronized(this) { shown[w] }
        // Insert-or-ignore then update: UPSERT needs SQLite 3.24 (Android 11+), minSdk is 26.
        writer.execute {
            val db = writableDatabase
            db.execSQL("INSERT OR IGNORE INTO words (word, count) VALUES (?, 0)", arrayOf(w))
            db.execSQL("UPDATE words SET count = count + 1 WHERE word = ?", arrayOf(w))
            if (caseChanged) db.execSQL("UPDATE words SET shown = ? WHERE word = ?", arrayOf(shownNow, w))
            if (p != null) {
                db.execSQL("INSERT OR IGNORE INTO pairs (prev, word, count) VALUES (?, ?, 0)", arrayOf(p, w))
                db.execSQL("UPDATE pairs SET count = count + 1 WHERE prev = ? AND word = ?", arrayOf(p, w))
            }
        }
    }

    /** The user undid an autocorrection of [word]: keep it as typed from now on. */
    fun reject(word: String) {
        awaitLoad()
        val w = word.lowercase()
        synchronized(this) { rejectedWords.add(w) }
        writer.execute {
            val values = ContentValues().apply { put("word", w); put("count", 0); put("rejected", 1) }
            writableDatabase.insertWithOnConflict("words", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.execSQL("UPDATE words SET rejected = 1 WHERE word = ?", arrayOf(w))
        }
    }

    // ---- managing learned data (Settings > Learned Words) --------------------------------

    /** Words the keyboard has learned (used at least [LEARN_AFTER] times), most used first. */
    fun learned(): List<Pair<String, Int>> {
        awaitLoad()
        return synchronized(this) {
            counts.entries.filter { it.value >= LEARN_AFTER }.sortedByDescending { it.value }.map { display(it.key) to it.value }
        }
    }

    /** Words whose autocorrection was undone, alphabetically. */
    fun rejectedList(): List<String> {
        awaitLoad()
        return synchronized(this) { rejectedWords.sorted().map { display(it) } }
    }

    /** Forgets [word]: its count, its word pairs and any undone correction. */
    fun delete(word: String) {
        awaitLoad()
        val w = word.lowercase()
        synchronized(this) {
            counts.remove(w)
            shown.remove(w)
            rejectedWords.remove(w)
            pairs.remove(w)
            pairs.values.forEach { it.remove(w) }
        }
        writer.execute {
            val db = writableDatabase
            db.delete("words", "word = ?", arrayOf(w))
            db.delete("pairs", "prev = ? OR word = ?", arrayOf(w, w))
        }
    }

    /** Lets the autocorrector fix [word] again. */
    fun unreject(word: String) {
        awaitLoad()
        val w = word.lowercase()
        synchronized(this) { rejectedWords.remove(w) }
        writer.execute { writableDatabase.execSQL("UPDATE words SET rejected = 0 WHERE word = ?", arrayOf(w)) }
    }

    /** Adds [word] as known straight away, so it is suggested and never autocorrected. */
    fun add(word: String) {
        awaitLoad()
        val typed = word.trim()
        val w = typed.lowercase()
        if (w.isEmpty()) return
        // Added in Settings: written exactly as entered.
        val written = typed.takeIf { it != w }
        synchronized(this) {
            counts[w] = maxOf(counts[w] ?: 0, LEARN_AFTER)
            if (written != null) shown[w] = written else shown.remove(w)
        }
        writer.execute {
            val db = writableDatabase
            db.execSQL("INSERT OR IGNORE INTO words (word, count) VALUES (?, 0)", arrayOf(w))
            db.execSQL("UPDATE words SET count = MAX(count, ?), shown = ? WHERE word = ?", arrayOf(LEARN_AFTER, written, w))
        }
    }

    /** Forgets everything learned: words, word pairs and undone corrections. */
    fun clearAll() {
        awaitLoad()
        synchronized(this) {
            counts.clear()
            shown.clear()
            rejectedWords.clear()
            pairs.clear()
        }
        writer.execute {
            val db = writableDatabase
            db.delete("words", null, null)
            db.delete("pairs", null, null)
        }
    }

    companion object {
        private const val LEARN_AFTER = 2
        /** Longest wait for the saved data (it takes milliseconds; this only guards a stuck disk). */
        private const val LOAD_WAIT_S = 3L
        @Volatile private var instance: UserWords? = null

        /**
         * The one instance per process: the keyboard and the settings screen share it, so edits
         * made in settings apply to the keyboard's in-memory copy straight away.
         */
        fun get(context: Context): UserWords = instance ?: synchronized(this) {
            instance ?: UserWords(context.applicationContext).also { instance = it }
        }
    }
}
