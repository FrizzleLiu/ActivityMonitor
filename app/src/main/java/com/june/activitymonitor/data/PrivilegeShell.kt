package com.june.activitymonitor.data

import com.june.activitymonitor.privileged.HelperClient

/**
 * Single entry point for the privileged operations the monitor needs: reading every process'
 * `/proc` entry and ending apps via `am force-stop`.
 *
 * Backends, in priority order:
 *  1. [Backend.HELPER] - the app's own embedded shell helper, started once with `app_process`,
 *     running as `shell` (uid 2000). This is the primary path and needs no root.
 *  2. [Backend.ROOT]   - optional silent fallback for rooted devices.
 */
object PrivilegeShell {

    enum class Backend { NONE, HELPER, ROOT }

    /** Cached helper availability so a burst of checks does not each pay the socket round trip. */
    private const val PROBE_TTL_MS = 2_000L

    @Volatile
    private var lastProbeAt = 0L

    @Volatile
    private var lastProbeResult = false

    /** Live probe, bypassing the cache. */
    fun isHelperRunning(): Boolean = HelperClient.isRunning()

    private fun isHelperRunningCached(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProbeAt < PROBE_TTL_MS) return lastProbeResult
        val result = HelperClient.isRunning()
        lastProbeResult = result
        lastProbeAt = now
        return result
    }

    fun backend(): Backend = when {
        isHelperRunningCached() -> Backend.HELPER
        RootUtils.isAvailable() -> Backend.ROOT
        else -> Backend.NONE
    }

    fun isReady(): Boolean = backend() != Backend.NONE

    /** Runs [script] with the highest available privilege, or returns null when none is granted. */
    fun exec(script: String, timeoutSec: Long = 12): String? {
        return when (backend()) {
            Backend.HELPER -> HelperClient.exec(script, (timeoutSec * 1000).toInt())
            Backend.ROOT -> RootUtils.run(script, timeoutSec)
            Backend.NONE -> null
        }
    }
}
