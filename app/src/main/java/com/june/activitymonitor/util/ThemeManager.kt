package com.june.activitymonitor.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    private const val PREFS = "monitor_settings"
    private const val KEY_NIGHT_MODE = "night_mode"

    fun savedMode(context: Context): Int =
        prefs(context).getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun applySavedMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(savedMode(context))
    }

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
