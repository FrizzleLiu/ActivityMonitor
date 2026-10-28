package com.june.activitymonitor.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * UsageStats based, best effort answer to "which apps are running?".
 *
 * Modern Android (API 22+) hides other apps' processes from [android.app.ActivityManager], so
 * without root the usage history is the only remaining signal: an app that brought an activity to
 * the foreground recently is still very likely resident. Requires the user to grant the special
 * "Usage access" permission via [android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS].
 */
object UsageStatsReader {

    /** Apps foregrounded within this window are considered running. */
    private const val RECENT_WINDOW_MS = 15 * 60 * 1000L

    /** How far back we look to work out which app is on screen right now. */
    private const val STATE_WINDOW_MS = 3 * 60 * 60 * 1000L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        } catch (t: Throwable) {
            AppOpsManager.MODE_ERRORED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The packages that currently have an activity on screen plus the ones that were foregrounded
     * within [RECENT_WINDOW_MS]. Empty when usage access has not been granted.
     */
    fun activePackages(context: Context): Set<String> {
        if (!hasUsageAccess(context)) return emptySet()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptySet()

        val now = System.currentTimeMillis()
        val events = try {
            manager.queryEvents(now - STATE_WINDOW_MS, now)
        } catch (t: Throwable) {
            return emptySet()
        }

        val active = HashSet<String>()
        var foreground: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (event.timeStamp >= now - RECENT_WINDOW_MS) active.add(packageName)
                    foreground = packageName
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (packageName == foreground) foreground = null
                }
            }
        }
        // An app idle in the foreground for longer than the window still counts as running.
        foreground?.let { active.add(it) }
        return active
    }
}
