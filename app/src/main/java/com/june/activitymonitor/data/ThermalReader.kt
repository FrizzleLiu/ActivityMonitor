package com.june.activitymonitor.data

/**
 * Reads device temperatures from `/sys/class/thermal`. This needs shell privilege (SELinux blocks
 * ordinary apps), so it goes through [PrivilegeShell] like the `/proc` sampling does.
 *
 * Thermal zones report in different units across kernels: most are millidegrees (54409 -> 54.4°C)
 * while a few are already whole degrees (52 -> 52°C). [normalize] handles both.
 */
object ThermalReader {

    data class Temperatures(
        val batteryC: Float,
        val socC: Float,
    ) {
        val hasAny: Boolean get() = batteryC >= 0f || socC >= 0f
    }

    /**
     * Read both sysfs files with shell builtins. Spawning two `cat` processes per thermal zone
     * takes several seconds on devices with many zones and exceeds the helper timeout.
     */
    private val SCRIPT = buildString {
        append("for f in /sys/class/thermal/thermal_zone*/type; do ")
        append("d=\${f%/*}; t=\"\"; v=\"\"; ")
        append("IFS= read -r t < \$f 2>/dev/null; ")
        append("IFS= read -r v < \$d/temp 2>/dev/null; ")
        append("[ -n \"\$t\" ] && [ -n \"\$v\" ] && echo \"Z|\$t|\$v\"; ")
        append("done")
    }

    fun read(): Temperatures {
        val output = PrivilegeShell.exec(SCRIPT, timeoutSec = 4) ?: return Temperatures(-1f, -1f)

        var battery = -1f
        var soc = -1f
        output.lineSequence().forEach { line ->
            if (!line.startsWith("Z|")) return@forEach
            val parts = line.split('|')
            if (parts.size < 3) return@forEach
            val type = parts[1].trim().lowercase()
            val value = normalize(parts[2].trim())
            if (value < 0f) return@forEach
            when {
                type == "battery" || type == "batt" -> battery = maxOf(battery, value)
                type.contains("soc") -> soc = maxOf(soc, value)
            }
        }
        return Temperatures(battery, soc)
    }

    /** Normalizes a raw sysfs value to °C, or -1 when it is clearly invalid (e.g. -40 sentinel). */
    private fun normalize(raw: String): Float {
        val value = raw.toFloatOrNull() ?: return -1f
        val celsius = when {
            value <= -1000f -> return -1f
            kotlin.math.abs(value) >= 1000f -> value / 1000f
            value > 200f -> return -1f
            else -> value
        }
        return if (celsius in 1f..120f) celsius else -1f
    }
}
