package com.june.activitymonitor.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.june.activitymonitor.data.ProcessRepository
import com.june.activitymonitor.data.PrivilegeShell
import com.june.activitymonitor.model.AppProcessInfo
import com.june.activitymonitor.model.AppSorters
import com.june.activitymonitor.model.MonitorUiState
import com.june.activitymonitor.model.SortOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProcessRepository(application)
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(MonitorUiState(sortOption = loadSortOption()))
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = try {
                repository.collect()
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message) }
                return@launch
            }
            _state.update { current ->
                // Keep running apps plus the ones the user froze/disabled, so they stay visible and
                // can be restored from the same list.
                val running = result.apps.filter { app ->
                    app.isRunning || app.isControlled
                }
                val system = running.filter { app -> app.isSystem }
                val user = running.filter { app -> !app.isSystem }
                current.copy(
                    loading = false,
                    privileged = result.privileged,
                    helperRunning = result.privileged || PrivilegeShell.isHelperRunning(),
                    usageAccessGranted = result.usageAccessGranted,
                    totalCpuPercent = result.totalCpuPercent,
                    totalMemoryPercent = result.totalMemoryPercent,
                    batteryTempC = result.batteryTempC,
                    socTempC = result.socTempC,
                    systemApps = sort(system, current.sortOption),
                    userApps = sort(user, current.sortOption),
                    lastUpdated = System.currentTimeMillis(),
                )
            }
        }
    }

    fun setSortOption(option: SortOption) {
        prefs.edit().putString(KEY_SORT, option.name).apply()
        _state.update { current ->
            current.copy(
                sortOption = option,
                systemApps = sort(current.systemApps, option),
                userApps = sort(current.userApps, option),
            )
        }
    }

    fun killApp(app: AppProcessInfo, onResult: (ProcessRepository.KillOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                repository.killApp(app.packageName)
            }
            onResult(outcome)
            refresh()
        }
    }

    /** Freezes, disables or restores [app]; the list is refreshed afterwards either way. */
    fun controlApp(
        app: AppProcessInfo,
        action: ProcessRepository.ControlAction,
        onResult: (ProcessRepository.ControlOutcome) -> Unit,
    ) {
        viewModelScope.launch {
            // Freezing / disabling a system app or service can break the device; refuse it even if a
            // caller asks, so the restriction holds no matter which screen triggers it.
            if (app.isSystem && action != ProcessRepository.ControlAction.RESTORE) {
                onResult(ProcessRepository.ControlOutcome.FAILED)
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) {
                when (action) {
                    ProcessRepository.ControlAction.FREEZE -> repository.freezeApp(app.packageName)
                    ProcessRepository.ControlAction.DISABLE -> repository.disableApp(app.packageName)
                    ProcessRepository.ControlAction.RESTORE -> repository.restoreApp(app.packageName)
                }
            }
            onResult(outcome)
            refresh()
        }
    }

    /** Re-checks whether the embedded helper is reachable; call after the user starts it. */
    fun refreshHelperState() {
        viewModelScope.launch {
            val running = withContext(Dispatchers.IO) { PrivilegeShell.isHelperRunning() }
            _state.update {
                it.copy(helperRunning = running, privileged = running || it.privileged)
            }
            if (running) refresh()
        }
    }

    private fun sort(apps: List<AppProcessInfo>, option: SortOption): List<AppProcessInfo> =
        apps.sortedWith(
            compareBy<AppProcessInfo> {
                when {
                    it.isDisabled -> 2
                    it.isFrozen -> 1
                    else -> 0
                }
            }.then(AppSorters.comparator(option))
        )

    private fun loadSortOption(): SortOption =
        SortOption.fromKey(prefs.getString(KEY_SORT, null))

    companion object {
        private const val PREFS = "monitor_settings"
        private const val KEY_SORT = "sort_option"
    }
}
