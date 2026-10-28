package com.june.activitymonitor.model

data class MonitorUiState(
    val loading: Boolean = false,
    /** Shell privilege (embedded helper or root) is currently usable: full CPU/memory + kill. */
    val privileged: Boolean = false,
    /** The embedded shell helper is currently running and reachable. */
    val helperRunning: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val totalCpuPercent: Float = -1f,
    val totalMemoryPercent: Float = -1f,
    val batteryTempC: Float = -1f,
    val socTempC: Float = -1f,
    val systemApps: List<AppProcessInfo> = emptyList(),
    val userApps: List<AppProcessInfo> = emptyList(),
    val error: String? = null,
    val lastUpdated: Long = 0L,
    val sortOption: SortOption = SortOption.CPU_DESC,
) {
    val appCount: Int get() = systemApps.size + userApps.size
}
