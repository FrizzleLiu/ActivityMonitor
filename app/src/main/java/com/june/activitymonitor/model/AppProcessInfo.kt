package com.june.activitymonitor.model

import android.graphics.drawable.Drawable

data class AppProcessInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val isSystem: Boolean,
    val icon: Drawable?,
    val isRunning: Boolean,
    val processCount: Int,
    val serviceCount: Int,
    val cpuPercent: Float,
    val memoryBytes: Long,
    /** Frozen with `pm suspend`: the app may run but Android blocks it from starting on its own. */
    val isFrozen: Boolean = false,
    /** Disabled with `pm disable-user`: the app is turned off until it is enabled again. */
    val isDisabled: Boolean = false,
) {
    val hasCpu: Boolean get() = cpuPercent >= 0f

    val hasMemory: Boolean get() = memoryBytes >= 0L

    /** True when the app was taken out of normal operation by the freeze/disable controls. */
    val isControlled: Boolean get() = isFrozen || isDisabled
}
