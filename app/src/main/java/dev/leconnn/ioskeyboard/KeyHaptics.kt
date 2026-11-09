package dev.leconnn.ioskeyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Key-press vibration with a chosen strength (Settings > Keyboard Feedback > Vibration). Uses the
 * vibrator directly so the strength applies even when the system's touch haptics are off; the
 * Haptic switch still turns it off entirely.
 */
class KeyHaptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    var strength = Prefs.Vibration.MEDIUM
        set(value) { field = value; effect = build(value) }
    private var effect: VibrationEffect = build(strength)

    private fun build(s: Prefs.Vibration): VibrationEffect = if (Build.VERSION.SDK_INT >= 29) {
        VibrationEffect.createPredefined(
            when (s) {
                Prefs.Vibration.LIGHT -> VibrationEffect.EFFECT_TICK
                Prefs.Vibration.MEDIUM -> VibrationEffect.EFFECT_CLICK
                Prefs.Vibration.STRONG -> VibrationEffect.EFFECT_HEAVY_CLICK
            },
        )
    } else {
        when (s) {
            Prefs.Vibration.LIGHT -> VibrationEffect.createOneShot(15, 80)
            Prefs.Vibration.MEDIUM -> VibrationEffect.createOneShot(25, 160)
            Prefs.Vibration.STRONG -> VibrationEffect.createOneShot(35, 255)
        }
    }

    fun tap() {
        val v = vibrator ?: return
        if (v.hasVibrator()) v.vibrate(effect)
    }
}
