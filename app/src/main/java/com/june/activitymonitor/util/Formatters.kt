package com.june.activitymonitor.util

import java.util.Locale

object Formatters {

    private const val UNKNOWN = "—"

    fun bytes(value: Long): String {
        if (value < 0L) return UNKNOWN
        if (value < 1024L) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun percent(value: Float): String =
        if (value < 0f) UNKNOWN else String.format(Locale.US, "%.1f%%", value)

    fun temperature(celsius: Float): String =
        if (celsius < 0f) UNKNOWN else String.format(Locale.US, "%.1f°C", celsius)
}
