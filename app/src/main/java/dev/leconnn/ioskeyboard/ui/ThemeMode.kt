package dev.leconnn.ioskeyboard.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsetsController
import dev.leconnn.ioskeyboard.KeyboardTheme
import dev.leconnn.ioskeyboard.Prefs

/** Applies Settings > Light / Dark to the app's own screens (Automatic keeps the system's). */
object ThemeMode {
    /** Use from Activity.attachBaseContext. */
    fun wrap(base: Context): Context {
        val mode = Prefs.get(base).themeMode
        if (mode == Prefs.ThemeMode.AUTO) return base
        val config = Configuration(base.resources.configuration)
        val night = if (mode == Prefs.ThemeMode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    /**
     * Recreates [activity] if Light / Dark changed while it was in the background (e.g. on the
     * Appearance page, which is on top of it). Call from onResume; returns true if it recreated.
     */
    fun refreshIfChanged(activity: Activity): Boolean {
        val mode = Prefs.get(activity).themeMode
        val systemNight = activity.applicationContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val wanted = when (mode) {
            Prefs.ThemeMode.AUTO -> systemNight
            Prefs.ThemeMode.DARK -> true
            Prefs.ThemeMode.LIGHT -> false
        }
        val shown = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (wanted == shown) return false
        activity.recreate()
        return true
    }

    /** Dark status/navigation bar icons on light screens; call after setContentView. */
    fun styleBars(activity: Activity) {
        if (Build.VERSION.SDK_INT < 30) return
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(if (KeyboardTheme.isNight(activity)) 0 else light, light)
    }
}
