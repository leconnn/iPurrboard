package dev.leconnn.ioskeyboard

import android.view.HapticFeedbackConstants
import android.widget.EditText

/** Minimal stand-in for [IosKeyboardService] that edits an EditText. */
internal class PreviewListener(private val field: EditText, private val view: () -> KeyboardView) : KeyboardView.Listener {
    private var lastSpace = 0L

    private fun replace(text: String, deleteBefore: Int = 0) {
        val e = field.text
        val end = field.selectionEnd.coerceAtLeast(0)
        val start = (minOf(field.selectionStart, end) - deleteBefore).coerceAtLeast(0)
        e.replace(start, end, text)
    }

    override fun onText(text: String, literal: Boolean) {
        replace(text)
        if (view().shift == Glyphs.ShiftState.ON) view().setShiftState(Glyphs.ShiftState.OFF)
        if (view().plane != Plane.LETTERS && text == "'") view().setPlane(Plane.LETTERS)
    }

    override fun onDelete(repeat: Int) {
        if (field.selectionStart != field.selectionEnd) replace("") else replace("", 1)
    }

    override fun onSpace() {
        val now = System.currentTimeMillis()
        val pos = field.selectionStart
        val before = field.text.subSequence(maxOf(0, pos - 2), pos).toString()
        if (now - lastSpace < 600 && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            replace(". ", 1)
            lastSpace = 0
        } else {
            replace(" ")
            lastSpace = now
        }
        if (view().plane != Plane.LETTERS) view().setPlane(Plane.LETTERS)
    }

    override fun onReturn() = replace("\n")

    override fun onCursorMove(dx: Int, dy: Int) {
        val p = (field.selectionStart + dx).coerceIn(0, field.length())
        field.setSelection(p)
    }

    override fun onFeedback(kind: KeyKind) {
        view().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onShiftChanged(state: Glyphs.ShiftState) = Unit
}
