package dev.leconnn.ioskeyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: lets adb tests change settings without driving the settings UI.
 * Usage: `adb shell am broadcast -a dev.leconnn.ioskeyboard.SET_PREF -p dev.leconnn.ioskeyboard --es key K --ez value false`.
 * `--ez value true|false` writes a boolean, `--es value X` writes a string (enums, JSON).
 */
class DebugPrefsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val edit = Prefs.get(context).raw.edit()
        @Suppress("DEPRECATION") // typed getters would need the type up front
        when (val v = intent.extras?.get("value")) {
            is Boolean -> edit.putBoolean(key, v)
            is String -> edit.putString(key, v)
            null -> edit.remove(key)
        }
        edit.apply()
    }
}
