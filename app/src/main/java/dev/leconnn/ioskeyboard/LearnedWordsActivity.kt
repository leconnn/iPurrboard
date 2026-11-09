package dev.leconnn.ioskeyboard

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.leconnn.ioskeyboard.text.Dictionary
import dev.leconnn.ioskeyboard.text.UserWords
import dev.leconnn.ioskeyboard.ui.IosAlert
import dev.leconnn.ioskeyboard.ui.IosList
import dev.leconnn.ioskeyboard.ui.ThemeMode
import java.util.concurrent.Executors

/**
 * Settings > Learned Words: what the keyboard has picked up from typing. Shows the words it
 * learned that aren't in its dictionary (your names, slang, jargon) and the corrections you undid,
 * each removable; plus Add Word and Reset Learned Data.
 */
class LearnedWordsActivity : Activity() {

    private lateinit var users: UserWords
    private var dictionaryWords: Set<String>? = null
    private var query = ""
    private lateinit var scroll: ScrollView

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemeMode.wrap(newBase))

    override fun onResume() {
        super.onResume()
        // Light / Dark may have changed on the Appearance page while this screen was underneath.
        ThemeMode.refreshIfChanged(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        users = UserWords.get(this)
        scroll = ScrollView(this)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ThemeMode.styleBars(this)
        render()
        // The dictionaries (every enabled keyboard language) decide which learned words are
        // "new"; load them off the main thread.
        val languages = Prefs.get(this).keyboards
        Executors.newSingleThreadExecutor().execute {
            val words = HashSet<String>()
            for (l in languages) {
                val d = assets.open(l.dictAsset).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it) }
                (0 until d.size).mapTo(words) { d.keys[it] }
            }
            runOnUiThread { dictionaryWords = words; render() }
        }
    }

    private fun render() {
        val list = IosList(this)
        scroll.setBackgroundColor(list.background)
        list.backButton("Settings") { finish() }
        list.largeTitle("Learned Words")

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

        scroll.removeAllViews()
        scroll.addView(list.root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
                // Keep typing in the rebuilt search field.
                (scroll.getChildAt(0) as? LinearLayout)?.let { root -> findSearch(root)?.requestFocus() }
            }
        })
    }

    private fun findSearch(group: android.view.ViewGroup): EditText? {
        for (i in 0 until group.childCount) {
            when (val v = group.getChildAt(i)) {
                is EditText -> return v
                is android.view.ViewGroup -> findSearch(v)?.let { return it }
            }
        }
        return null
    }

    private fun confirmDelete(word: String) {
        IosAlert(this)
            .title("Remove “$word”?")
            .message("The keyboard will stop suggesting it, and may correct it again.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Remove", IosAlert.Style.DESTRUCTIVE) { users.delete(word); render() }
            .show()
    }

    private fun confirmUnreject(word: String) {
        IosAlert(this)
            .title("Correct “$word” Again?")
            .message("Auto-Correction will fix this word again when it looks like a typo.")
            .action("Cancel", IosAlert.Style.CANCEL)
            .action("Allow", IosAlert.Style.PREFERRED) { users.unreject(word); render() }
            .show()
    }

    private fun addWord() {
        val alert = IosAlert(this).title("Add Word").message("It will be suggested and never autocorrected.")
        val field = alert.field("Word", type = InputType.TYPE_CLASS_TEXT)
        alert.action("Cancel", IosAlert.Style.CANCEL)
            .action("Add", IosAlert.Style.PREFERRED) { users.add(field.text.toString()); render() }
            .show()
    }

    private fun confirmReset() {
        IosAlert(this)
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
