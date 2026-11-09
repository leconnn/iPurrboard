package dev.leconnn.ioskeyboard.text

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** What the focused field allows, from its input type. */
class FieldTraits(info: EditorInfo?) {
    private val type = info?.inputType ?: InputType.TYPE_NULL
    private val cls = type and InputType.TYPE_MASK_CLASS
    private val variation = type and InputType.TYPE_MASK_VARIATION

    val isText = cls == InputType.TYPE_CLASS_TEXT
    val isPassword = (isText && variation in PASSWORD_VARIATIONS) ||
        (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    val isEmailOrUrl = isText && variation in EMAIL_URL_VARIATIONS
    val noSuggestions = type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0

    /** Smart punctuation and similar rewrites: plain prose fields only. */
    val smartAllowed = isText && !isPassword && !isEmailOrUrl

    /**
     * The app asked keyboards not to learn from this field (IME_FLAG_NO_PERSONALIZED_LEARNING,
     * e.g. a browser's incognito tab), as Gboard honours.
     */
    val incognito = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    /** Learning new words and keeping clipboard history: never in password or incognito fields. */
    val learningAllowed = !isPassword && !incognito

    /** The suggestion bar (iOS hides it in password fields and where apps opt out). */
    val suggestionsAllowed = isText && !isPassword && !noSuggestions

    private companion object {
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        val EMAIL_URL_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
    }
}
