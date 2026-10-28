package com.june.activitymonitor.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** System developer / ADB switches required before wireless pairing can be used. */
object DeveloperSettings {

    data class Status(
        val developerOptionsEnabled: Boolean,
        val wirelessDebuggingSupported: Boolean,
        val wirelessDebuggingEnabled: Boolean,
    )

    enum class OpenResult {
        WIRELESS_DEBUGGING,
        DEVELOPER_OPTIONS,
        FAILED,
    }

    private const val ACTION_WIRELESS_DEBUGGING = "android.settings.WIRELESS_DEBUGGING"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val EXTRA_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val WIRELESS_DEBUGGING_PREFERENCE_KEY = "toggle_adb_wireless"

    fun read(context: Context): Status {
        val wirelessSupported = supportsWirelessDebugging(context)
        return Status(
            developerOptionsEnabled = isGlobalSettingEnabled(
                context,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            ),
            wirelessDebuggingSupported = wirelessSupported,
            wirelessDebuggingEnabled = wirelessSupported &&
                isGlobalSettingEnabled(context, ADB_WIFI_ENABLED),
        )
    }

    /**
     * Wireless debugging is part of Developer options on Android 11+, but some vendors remove the
     * feature entirely. The global `adb_wifi_enabled` key can still exist on those builds, so the
     * reliable signal is whether Settings exposes one of its known Wireless debugging activities.
     */
    fun supportsWirelessDebugging(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return wirelessDebuggingIntents().any { intent ->
            val component = intent.component
            if (component != null) {
                runCatching {
                    context.packageManager.getActivityInfo(component, 0).enabled
                }.getOrDefault(false)
            } else {
                context.packageManager.queryIntentActivities(intent, 0)
                    .any { it.activityInfo.enabled }
            }
        }
    }

    fun openDeveloperOptions(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    fun openDeviceInfoSettings(context: Context): Boolean =
        tryStart(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    /**
     * Opens Developer options with its Wireless debugging preference selected. Settings consumes
     * this hidden argument to scroll to and highlight the preference; it is not a text search.
     * Some ROMs reject the documented entry point, so retain explicit wireless-page fallbacks.
     */
    fun openWirelessDebugging(context: Context): OpenResult {
        if (!supportsWirelessDebugging(context)) {
            return if (openDeveloperOptions(context)) {
                OpenResult.DEVELOPER_OPTIONS
            } else {
                OpenResult.FAILED
            }
        }

        val highlightedDeveloperOptions = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARGS_KEY, WIRELESS_DEBUGGING_PREFERENCE_KEY)
        if (tryStart(context, highlightedDeveloperOptions)) {
            return OpenResult.WIRELESS_DEBUGGING
        }

        if (wirelessDebuggingIntents().any { tryStart(context, it) }) {
            return OpenResult.WIRELESS_DEBUGGING
        }
        return if (openDeveloperOptions(context)) {
            OpenResult.DEVELOPER_OPTIONS
        } else {
            OpenResult.FAILED
        }
    }

    private fun isGlobalSettingEnabled(context: Context, key: String): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, key, 0) == 1
        }.getOrDefault(false)

    private fun explicitSettingsIntent(className: String): Intent =
        Intent().setComponent(ComponentName("com.android.settings", className))

    private fun wirelessDebuggingIntents(): List<Intent> = listOf(
        Intent(ACTION_WIRELESS_DEBUGGING),
        explicitSettingsIntent("com.android.settings.Settings\$WirelessDebuggingActivity"),
        explicitSettingsIntent(
            "com.android.settings.wifi.debugging.WirelessDebuggingActivity",
        ),
    )

    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching {
            // Keep system Settings in its own task so ActivityMonitor remains a separate entry in
            // Recents when the user returns from the Developer/Wireless debugging page.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
}
