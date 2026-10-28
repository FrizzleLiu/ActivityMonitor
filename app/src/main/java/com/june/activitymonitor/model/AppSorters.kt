package com.june.activitymonitor.model

object AppSorters {

    fun comparator(option: SortOption): Comparator<AppProcessInfo> = when (option) {
        SortOption.CPU_DESC -> compareByDescending<AppProcessInfo> { it.cpuPercent }
            .thenByDescending { it.memoryBytes }
            .thenBy { it.appName.lowercase() }

        SortOption.MEMORY_DESC -> compareByDescending<AppProcessInfo> { it.memoryBytes }
            .thenByDescending { it.cpuPercent }
            .thenBy { it.appName.lowercase() }

        SortOption.NAME_ASC -> compareBy { it.appName.lowercase() }
    }
}
