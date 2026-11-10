package dev.leconnn.ioskeyboard

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import dev.leconnn.ioskeyboard.text.Dictionary
import dev.leconnn.ioskeyboard.text.UserWords
import dev.leconnn.ioskeyboard.ui.IosAlert
import dev.leconnn.ioskeyboard.ui.IosList
import java.util.concurrent.Executors

/**
 * Settings > Learned Words: what the keyboard has picked up from typing. Shows the words it
 * learned that aren't in its dictionary (your names, slang, jargon) and the corrections you undid,
 * each removable; plus Add Word and Reset Learned Data.
 */
class LearnedWordsPage(private val activity: Activity, private val rerender: () -> Unit) {

    private val users = UserWords.get(activity)
    private var dictionaryWords: Set<String>? = null
    private var query = ""
    /** Typing in the search field rebuilds the page; the new field takes the focus back. */
    private var refocus = false

    init {
        // The dictionaries (every enabled keyboard language) decide which learned words are
        // "new"; load them off the main thread.
        val languages = Prefs.get(activity).keyboards
        Executors.newSingleThreadExecutor().execute {
            val words = HashSet<String>()
            for (l in languages) {
                val d = activity.assets.open(l.dictAsset).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it) }
                (0 until d.size).mapTo(words) { d.keys[it] }
            }
            activity.runOnUiThread { dictionaryWords = words; if (!activity.isFinishing) rerender() }
        }
    }

    private fun render() = rerender()

    fun build(list: IosList) {
        val search = list.searchField("Search", query).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        list.section()
        list.actionRow("Add Word…") { addWord() }

        val dict = dictionaryWords
        val learned = users.learned().filter { (w, _) -> (dict == null || w.lowercase() !in dict) && w.contains(query, ignoreCase = true) }
        list.section("Words")
        if (dict == null) {
            list.placeholderRow("Loading…")
        } else if (learned.isEmpty()) {
            list.placeholderRow(if (query.isEmpty()) "No learned words yet" else "No matches")
        } else {
            learned.take(MAX_ROWS).forEach { (w, n) -> list.navRow(w, "$n×") { confirmDelete(w) } }
        }
        val more = if (learned.size > MAX_ROWS) " Showing $MAX_ROWS of ${learned.size}; search to find the others." else ""
        list.footer("Words you've typed that aren't in the keyboard's dictionary. Tap one to remove it.$more")

        val rejected = users.rejectedList().filter { it.contains(query, ignoreCase = true) }
        list.section("Corrections You Undid")
        if (rejected.isEmpty()) list.placeholderRow(if (query.isEmpty()) "None" else "No matches")
        rejected.take(MAX_ROWS).forEach { w -> list.navRow(w) { confirmUnreject(w) } }
        val moreRejected = if (rejected.size > MAX_ROWS) " Showing $MAX_ROWS of ${rejected.size}; search to find the others." else ""
        list.footer("These words are never autocorrected. Tap one to let Auto-Correction fix it again.$moreRejected")

        list.section()
        list.actionRow("Reset Learned Data", list.red) { confirmReset() }

        // Keep typing in the rebuilt search field.
        if (refocus) { refocus = false; search.post { search.requestFocus() } }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                refocus = true
                render()
            }
        })
    }

    private fun confirmDelete(word: String) {
        IosAlert(activity)
            .title("Remove “$word”?")
            .message("The keyboard will stop suggesting it, and may correct it again.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Remove", IosAlert.Style.DESTRUCTIVE) { users.delete(word); render() }
            .show()
    }

    private fun confirmUnreject(word: String) {
        IosAlert(activity)
            .title("Correct “$word” Again?")
            .message("Auto-Correction will fix this word again when it looks like a typo.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Allow", IosAlert.Style.PREFERRED) { users.unreject(word); render() }
            .show()
    }

    private fun addWord() {
        val alert = IosAlert(activity).title("Add Word").message("It will be suggested and never autocorrected.")
        val field = alert.field("Word", type = InputType.TYPE_CLASS_TEXT)
        alert.action("Cancel", IosAlert.Style.CANCEL)
            .action("Add", IosAlert.Style.PREFERRED) { users.add(field.text.toString()); render() }
            .show()
    }

    private fun confirmReset() {
        IosAlert(activity)
            .title("Reset Learned Data?")
            .message("Learned words, word pairs and undone corrections are deleted. Text replacements and settings are kept.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Reset", IosAlert.Style.DESTRUCTIVE) { users.clearAll(); render() }
            .show()
    }

    private companion object {
        /** Rows per list; search narrows longer lists. */
        const val MAX_ROWS = 200
    }
}
