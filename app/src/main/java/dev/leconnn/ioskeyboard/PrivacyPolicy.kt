package dev.leconnn.ioskeyboard

/**
 * The privacy policy shown in Settings > Privacy Policy. Keep it in step with PRIVACY.md at the
 * repository root (same sections, same wording) and with what the code actually does.
 */
object PrivacyPolicy {
    const val UPDATED = "November 10, 2026"

    /** The features that go online in this build (GIFs only where a build includes them). */
    private val online = if (BuildConfig.GIFS) "GIFs and Emoji Kitchen stickers" else "Emoji Kitchen stickers"

    val SUMMARY = "iPurrboard has no accounts, ads, analytics or tracking. What you type stays on " +
        "your phone. The keyboard only goes online for features you turn on: $online."

    /** Section title, then its paragraphs. */
    val SECTIONS: List<Pair<String, List<String>>> = listOf(
        "What you type" to listOf(
            "Your typing is processed on your phone to show suggestions and corrections. It is never sent " +
                "anywhere and never leaves the device.",
            "To improve suggestions, the keyboard remembers words you type that aren't in its dictionary, which " +
                "words you type after which, and corrections you undo. You can see and delete these in Settings > " +
                "Learned Words.",
            "Nothing is learned in password fields, or in fields where the app asks keyboards not to learn " +
                "(such as a browser's incognito tab).",
        ),
        "Clipboard and screenshots" to listOf(
            "With Clipboard History on, text you copy is kept on your phone so you can paste it again. Items " +
                "are deleted after the time you choose (1 hour by default) unless pinned. Copies from password " +
                "or incognito fields, and text marked sensitive (for example by password managers), are never kept.",
            "Show Copied Items reads the clipboard when the keyboard opens, to offer what you just copied.",
            "With Save Recent Screenshots on (and photo access allowed), the keyboard looks for new " +
                "screenshots in your photo library to offer them in the clipboard. They stay in your library and are " +
                "never uploaded; one is only copied when you paste it into an app.",
        ),
        "Voice typing" to listOf(
            "Dictation uses your phone's speech recognition service (usually Google's). Your voice is " +
                "handled by that service under its own privacy policy, which may process it online. " +
                "iPurrboard only receives the text and does not store any audio.",
        ),
        "Features that go online" to listOfNotNull(
            "These are off until you turn them on. When used, your phone connects directly to the service, " +
                "which receives your IP address and the request.",
            if (!BuildConfig.GIFS) null else "GIFs (GIPHY): the trending list and what you search for are sent to " +
                "GIPHY. GIPHY's privacy policy applies: giphy.com/privacy.",
            "Emoji Kitchen stickers: sticker pictures are loaded from Google's servers (gstatic.com), so Google " +
                "can see which emoji combinations are shown. Google's privacy policy applies: policies.google.com/privacy.",
            "Results and pictures are cached on your phone for a short time to load faster.",
        ),
        "What is stored, and where" to listOf(
            "Settings, learned words, clipboard history, emoji recents and text replacements are stored only " +
                "on your phone, in the app's private storage. They are not included in Android backups.",
            "You can delete them anytime: Settings > Learned Words > Reset Learned Data, Clear in the clipboard, " +
                "or uninstall the app to remove everything.",
        ),
        "Permissions" to listOf(
            "Microphone: only for voice typing, asked the first time you use it.",
            "Photos: only for Save Recent Screenshots, asked when you turn it on.",
            "Internet: only for $online.",
            "Vibration: for key feedback.",
        ),
        "No sharing or selling" to listOf(
            "iPurrboard does not collect personal data, so it has nothing to share or sell. There are no " +
                "analytics, crash reporting, ads or third-party trackers in the app.",
        ),
        "Children" to listOf(
            "iPurrboard does not knowingly collect information from anyone, including children.",
        ),
        "Changes and contact" to listOf(
            "If this policy changes, the new version will be in the app and at github.com/leconnn/iPurrboard. " +
                "Questions: open an issue on that page.",
        ),
    )
}
