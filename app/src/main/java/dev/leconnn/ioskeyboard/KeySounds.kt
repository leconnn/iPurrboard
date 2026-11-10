package dev.leconnn.ioskeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * Key clicks. Uses res/raw/key_press_click / key_press_delete / key_press_modifier when a build
 * includes them (optional, not part of this repository), otherwise falls back to
 * Android's own keypress sound effects. Silent when the ringer is silent or on vibrate, as on iOS.
 */
class KeySounds(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val click = load("key_press_click")
    private val delete = load("key_press_delete")
    private val modifier = load("key_press_modifier")

    @SuppressLint("DiscouragedApi") // the files are optional, so they can't be referenced via R.raw
    private fun load(name: String): Int {
        val id = context.resources.getIdentifier(name, "raw", context.packageName)
        return if (id == 0) 0 else pool.load(context, id, 1)
    }

    /** iOS Keyboard Feedback > Sound. */
    var enabled = true
    /** Settings > Sound Volume, 0..1. */
    var volume = 1f

    fun play(kind: KeyKind) {
        if (!enabled || volume <= 0f || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val (sample, fallback) = when (kind) {
            KeyKind.CHAR, KeyKind.SPACE -> click to AudioManager.FX_KEYPRESS_STANDARD
            KeyKind.DELETE -> delete to AudioManager.FX_KEYPRESS_DELETE
            KeyKind.RETURN -> modifier to AudioManager.FX_KEYPRESS_RETURN
            else -> modifier to AudioManager.FX_KEYPRESS_STANDARD
        }
        val v = VOLUME * volume
        if (sample != 0) pool.play(sample, v, v, 1, 0, 1f) else audio.playSoundEffect(fallback, volume)
    }

    fun release() = pool.release()

    private companion object {
        const val VOLUME = 0.6f
    }
}
