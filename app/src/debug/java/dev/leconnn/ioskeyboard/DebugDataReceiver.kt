package dev.leconnn.ioskeyboard

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dev.leconnn.ioskeyboard.text.ClipHistory
import dev.leconnn.ioskeyboard.text.UserWords

/**
 * Debug builds only: swaps the data the UI shows (clipboard history, learned words, text
 * replacements, the clipboard itself) for realistic worst cases, to see what breaks.
 *
 *     adb shell am broadcast -a dev.leconnn.ioskeyboard.SEED_DATA -p dev.leconnn.ioskeyboard --es set worst
 *
 * Sets: `worst` (long, short, emoji, right-to-left, multi-line, accented values mixed across
 * items), `huge` (the most the app keeps: 50 clips, 300 learned words, 120 replacements), `one`
 * (one of each), `empty` (nothing). `copy` + `--es text "…"` puts text on the clipboard (copied
 * chip); `copy-long` copies a 20,000-character document. Seeding replaces what's there, so back
 * the data up first (tests do).
 */
class DebugDataReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val clips = ClipHistory.get(context)
        val words = UserWords.get(context)
        val prefs = Prefs.get(context)
        when (intent.getStringExtra("set")) {
            "worst" -> {
                reset(clips, words, prefs)
                // Oldest first, so the list shows them newest first in this order reversed.
                WORST_CLIPS.forEachIndexed { i, t -> clips.add(t, System.currentTimeMillis() - (WORST_CLIPS.size - i) * 1000L) }
                clips.items().firstOrNull { it.text.startsWith("Hi Aleksandra") }?.let { clips.setPinned(it.id, true) }
                clips.items().firstOrNull { it.text == "k" }?.let { clips.setPinned(it.id, true) }
                WORST_WORDS.forEach { w -> words.add(w) }
                repeat(1284) { words.commit(null, "wiśniewska") }
                WORST_REJECTED.forEach { words.reject(it) }
                prefs.textReplacements = WORST_REPLACEMENTS
            }
            "huge" -> {
                reset(clips, words, prefs)
                for (i in 1..60) clips.add("Order #${100000 + i} shipped to Bartholomew Fitzgerald, 1284 Northwind Way — tracking 1Z999AA1012345678$i",
                    System.currentTimeMillis() - (60 - i) * 1000L)
                // 300 distinct made-up words: the index spelled in letters (zorblaxab, zorblaxac, …).
                for (i in 1..300) words.add("zorblax${'a' + i / 26}${'a' + i % 26}")
                prefs.textReplacements = (1..120).map { "sc$it".filter { c -> c.isLetter() } + "x".repeat(it % 5) + it to "Replacement phrase number $it for testing" }
            }
            "one" -> {
                reset(clips, words, prefs)
                clips.add("Meeting moved to 3:30")
                words.add("Jo")
                words.reject("teh")
                prefs.textReplacements = listOf("omw" to "On my way!")
            }
            "empty" -> reset(clips, words, prefs)
            "copy" -> copy(context, intent.getStringExtra("text") ?: return)
            "copy-long" -> copy(context, LONG_DOCUMENT)
        }
    }

    private fun reset(clips: ClipHistory, words: UserWords, prefs: Prefs) {
        clips.items().forEach { clips.delete(it.id) }
        words.clearAll()
        prefs.textReplacements = emptyList()
    }

    private fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("test", text))
    }

    private companion object {
        val WORST_CLIPS = listOf(
            "Hi Aleksandra,\n\nThanks for sending the Q3 numbers over. I've gone through them with Jo and we have a few questions about the Northwind Industries Holdings line items:\n\n1.\tWhy did the logistics spend jump 1,284% in August?\n2.\tCan we get the raw export rather than the PDF?\n\nBest regards,\nBartholomew",
            "https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit?usp=sharing&ouid=118264839455210934&rtpof=true&sd=true#gid=1934827561",
            "bartholomew.fitzgerald@northwind-industries-holdings.example.com",
            "k",
            "🏳️‍🌈👨‍👩‍👧‍👦🫶🏽",
            "مرحبا، الاجتماع غدًا الساعة العاشرة صباحًا في المكتب الرئيسي",
            "    if (user.isAdmin && !user.suspended) {\n\t\treturn grantAccess(user, Scope.ALL)\n\t}",
            "Tiếng Việt có dấu: Nguyễn Thị Ánh Tuyết, Đường Lê Lợi, Quận 1",
            "Aleksandra Wiśniewska-Kowalczyk",
            "+48 12 345 67 89 ext. 1284",
            "Your verification code is 482913. It expires in 10 minutes.",
            "                                    padded",
        )
        val WORST_WORDS = listOf(
            "Llanfairpwllgwyngyllgogerychwyrndrobwllllantysiliogogogoch",
            "Jo", "Wiśniewska", "Nguyễn", "O'Shaughnessy-Fitzgerald",
        )
        val WORST_REJECTED = listOf("Llanfairpwllgwyngyll", "teh", "Kowalczykowna")
        val WORST_REPLACEMENTS = listOf(
            "addr" to "Aleksandra Wiśniewska-Kowalczyk, Flat 12B, 1284 Northwind Industries Holdings Business Park, ul. Floriańska 15, 30-001 Kraków, Poland",
            "sig" to "Best regards,\nAleksandra Wiśniewska-Kowalczyk\nSenior Logistics Coordinator",
            "mysuperlongshortcutthatsomeonereallytyped" to "x",
            "e" to "bartholomew.fitzgerald@northwind-industries-holdings.example.com",
            "shrug" to "¯\\_(ツ)_/¯",
            "omw" to "On my way!",
        )
        val LONG_DOCUMENT = buildString {
            while (length < 20_000) append("Section ${length / 400 + 1}. The quarterly logistics review covers inbound freight, warehouse throughput and last-mile delivery for every Northwind Industries Holdings region. ")
        }
    }
}
