package com.june.activitymonitor

import android.app.Application
import com.june.activitymonitor.privileged.HelperConfig
import com.june.activitymonitor.util.LocaleManager
import com.june.activitymonitor.util.ThemeManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        HelperConfig.init(this)
        // Generate/persist the helper token up front so the bootstrap command can include it.
        HelperConfig.token()
        // Restore the user's language and light/dark preference before any Activity is created.
        ThemeManager.applySavedMode(this)
        LocaleManager.applySavedLocale(this)
    }
}
