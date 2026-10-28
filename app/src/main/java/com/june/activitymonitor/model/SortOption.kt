package com.june.activitymonitor.model

/** Order applied to the app list. Unknown (-1) values always sink to the bottom. */
enum class SortOption {
    CPU_DESC,
    MEMORY_DESC,
    NAME_ASC;

    companion object {
        fun fromKey(key: String?): SortOption =
            entries.firstOrNull { it.name == key } ?: CPU_DESC
    }
}
