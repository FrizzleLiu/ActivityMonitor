package com.june.activitymonitor.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LocaleManager {

    const val LANG_SYSTEM = "system"
    const val LANG_CHINESE = "zh"
    const val LANG_ENGLISH = "en"

    private const val PREFS = "monitor_settings"
    private const val KEY_LANGUAGE = "app_language"

    fun savedLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM

    /** Re-applies the persisted language, called once from the Application. */
    fun applySavedLocale(context: Context) {
        apply(context, savedLanguage(context), persist = false)
    }

    fun setLanguage(context: Context, language: String) {
        apply(context, language, persist = true)
    }

    private fun apply(context: Context, language: String, persist: Boolean) {
        if (persist) {
            prefs(context).edit().putString(KEY_LANGUAGE, language).apply()
        }
        val locales = when (language) {
            LANG_CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
            LANG_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
