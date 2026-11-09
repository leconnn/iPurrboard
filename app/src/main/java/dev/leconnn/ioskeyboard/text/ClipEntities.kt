package dev.leconnn.ioskeyboard.text

/**
 * Picks useful parts out of copied text for extra suggestion chips, like Gboard's "items pulled
 * from recently copied text": one-time codes, links, emails, phone numbers and street addresses.
 * Pattern-based and offline; deliberately conservative so it rarely offers something wrong.
 */
object ClipEntities {
    enum class Kind { CODE, LINK, EMAIL, PHONE, ADDRESS }

    data class Entity(val kind: Kind, val text: String)

    private val CODE_CONTEXT = Regex("""(?i)\b(code|otp|pin|passcode|verification|verify|one-time)\b""")
    private val CODE = Regex("""(?<![\d\-])\d{4,8}(?![\d\-])""")
    private val LINK = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']*[^\s<>"'.,;:!?)\]]""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val PHONE = Regex("""(?<![\w+])\+?\(?\d[\d\s().\-]{5,}\d(?!\w)""")
    private val LOCAL_NUMBER = Regex("""\d{3}[\s.\-]\d{4}$""")
    private val STREET = Regex(
        """(?im)^[ \t]*\d{1,6}[A-Za-z]?\s+(?:[A-Za-z0-9.'\-]+\s+){0,4}""" +
            """(?:st|street|ave|avenue|rd|road|blvd|boulevard|ln|lane|dr|drive|way|ct|court|pl|place|""" +
            """hwy|highway|pkwy|parkway|sq|square|ter|terrace)\b\.?[^\n]*""",
    )

    /** Up to [max] entities in [text], most useful first; never the whole text itself. */
    fun find(text: String, max: Int = 2): List<Entity> {
        val whole = text.trim()
        val out = LinkedHashMap<String, Entity>()
        fun add(kind: Kind, s: String) {
            val t = s.trim().trimEnd(',', ';')
            if (t.isNotEmpty() && t != whole && t !in out) out[t] = Entity(kind, t)
        }
        if (CODE_CONTEXT.containsMatchIn(text)) CODE.findAll(text).forEach { add(Kind.CODE, it.value) }
        LINK.findAll(text).forEach { add(Kind.LINK, it.value) }
        EMAIL.findAll(text).forEach { add(Kind.EMAIL, it.value) }
        PHONE.findAll(text).forEach { m ->
            val s = m.value.trim()
            val digits = s.count(Char::isDigit)
            val looksLikePhone = digits in 7..15 &&
                (s.startsWith("+") || '(' in s || digits >= 10 || LOCAL_NUMBER.containsMatchIn(s))
            if (looksLikePhone && out.values.none { it.kind == Kind.CODE && it.text == s }) add(Kind.PHONE, s)
        }
        STREET.findAll(text).forEach { add(Kind.ADDRESS, it.value) }
        return out.values.take(max)
    }
}
