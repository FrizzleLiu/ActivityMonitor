package com.june.activitymonitor.data

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.june.activitymonitor.model.AppProcessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Collects the data shown by the monitor:
 *  - the installed apps (system / user split),
 *  - which of them are currently running (processes and services),
 *  - per process CPU usage and resident memory.
 *
 * On a stock device Android only exposes our own process, so most rows will show "—". Once the
 * embedded helper is started (shell privilege, no root required) the repository talks to `/proc`
 * through that channel and the data becomes complete.
 */
class ProcessRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    data class InstalledApp(
        val packageName: String,
        val label: String,
        val uid: Int,
        val isSystem: Boolean,
        val icon: Drawable?,
    )

    data class RunningInfo(
        val pids: MutableSet<Int> = mutableSetOf(),
        var serviceCount: Int = 0,
    )

    data class Result(
        val apps: List<AppProcessInfo>,
        val totalCpuPercent: Float,
        val totalMemoryPercent: Float,
        val batteryTempC: Float,
        val socTempC: Float,
        val privileged: Boolean,
        val backend: PrivilegeShell.Backend,
        val usageAccessGranted: Boolean,
    )

    /** How a "end app" request turned out, which the UI reports back to the user. */
    enum class KillOutcome {
        /** Shell privilege (embedded helper / root): `am force-stop` was executed. */
        STOPPED,

        /** No shell privilege: best effort `killBackgroundProcesses`, the system may ignore it. */
        BEST_EFFORT,

        /** Nothing could be requested at all. */
        FAILED,
    }

    /** The lifecycle controls offered by long pressing a row. */
    enum class ControlAction { FREEZE, DISABLE, RESTORE }

    /** Whether a [ControlAction] actually took effect. */
    enum class ControlOutcome { DONE, FAILED }

    /** Packages currently suspended / disabled, so the list can show and restore them. */
    private data class ControlState(
        val frozen: Set<String> = emptySet(),
        val disabled: Set<String> = emptySet(),
    )

    suspend fun collect(): Result = withContext(Dispatchers.IO) {
        val installed = loadInstalledApps()
        val backend = PrivilegeShell.backend()
        val usePrivileged = backend != PrivilegeShell.Backend.NONE

        val first = sample(usePrivileged)
        delay(CPU_SAMPLE_INTERVAL_MS)
        val second = sample(usePrivileged)

        val running = loadRunningInfo()
        if (usePrivileged && second != null) {
            second.namesByPid().forEach { (pid, name) ->
                val packageName = name.substringBefore(':')
                running.getOrPut(packageName) { RunningInfo() }.pids.add(pid)
            }
        }

        // Without root ActivityManager only exposes our own process, so fall back to the usage
        // history when the user has granted "Usage access". Otherwise nothing else is detectable.
        val usageAccess = UsageStatsReader.hasUsageAccess(context)
        val usageRunning = if (!usePrivileged && usageAccess) {
            UsageStatsReader.activePackages(context)
        } else {
            emptySet()
        }

        val cpuByPid = computeCpuByPid(first, second)
        val rssByPid = second?.rssByPid().orEmpty()
        val totalCpu = computeTotalCpu(first, second)
        val temperatures = if (usePrivileged) {
            ThermalReader.read()
        } else {
            ThermalReader.Temperatures(UNKNOWN, UNKNOWN)
        }
        val control = loadControlState(usePrivileged)

        val apps = installed.map { app ->
            val info = running[app.packageName]
            val pids = info?.pids.orEmpty()
            val cpu = pids.mapNotNull { cpuByPid[it] }
                .takeIf { it.isNotEmpty() }
                ?.sum()
                ?: UNKNOWN
            val memory = pids.mapNotNull { rssByPid[it] }
                .takeIf { it.isNotEmpty() }
                ?.sum()
                ?: UNKNOWN.toLong()
            val services = info?.serviceCount ?: 0

            AppProcessInfo(
                packageName = app.packageName,
                appName = app.label,
                uid = app.uid,
                isSystem = app.isSystem,
                icon = app.icon,
                isRunning = pids.isNotEmpty() || services > 0 ||
                    app.packageName in usageRunning,
                processCount = pids.size,
                serviceCount = services,
                cpuPercent = cpu,
                memoryBytes = memory,
                isFrozen = app.packageName in control.frozen,
                isDisabled = app.packageName in control.disabled,
            )
        }

        Result(
            apps = apps,
            totalCpuPercent = totalCpu,
            totalMemoryPercent = readTotalMemoryPercent(),
            batteryTempC = temperatures.batteryC,
            socTempC = temperatures.socC,
            privileged = usePrivileged,
            backend = backend,
            usageAccessGranted = usageAccess,
        )
    }

    /**
     * Device-wide memory usage percentage from [ActivityManager.MemoryInfo], which any app may
     * read (no privilege needed).
     */
    private fun readTotalMemoryPercent(): Float = try {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        if (info.totalMem <= 0L) UNKNOWN else {
            val used = (info.totalMem - info.availMem).coerceAtLeast(0L)
            (used.toFloat() / info.totalMem.toFloat()) * 100f
        }
    } catch (t: Throwable) {
        UNKNOWN
    }

    fun killApp(packageName: String): KillOutcome {
        // With shell privilege (embedded helper / root) `am force-stop` ends the app and its services.
        if (PrivilegeShell.isReady() && PrivilegeShell.exec("am force-stop $packageName") != null) {
            return KillOutcome.STOPPED
        }

        // Without shell privilege `killBackgroundProcesses` is the only available API. It is
        // unreliable:
        // it only affects cached background processes and several OEM ROMs (e.g. ColorOS) block or
        // silently ignore calls from third-party apps, so the result cannot be guaranteed.
        return try {
            activityManager.killBackgroundProcesses(packageName)
            KillOutcome.BEST_EFFORT
        } catch (t: Throwable) {
            KillOutcome.FAILED
        }
    }

    /** Freezes [packageName] (`pm suspend`) and stops its current process, so it cannot auto-start. */
    fun freezeApp(packageName: String): ControlOutcome {
        val result = runControl("pm suspend --user 0 $packageName")
        if (result == ControlOutcome.DONE) {
            PrivilegeShell.exec("am force-stop $packageName")
        }
        return result
    }

    /** Disables [packageName] (`pm disable-user`), which also stops it. */
    fun disableApp(packageName: String): ControlOutcome =
        runControl("pm disable-user --user 0 $packageName")

    /** Reverses both controls: un-suspends and re-enables the package. */
    fun restoreApp(packageName: String): ControlOutcome =
        runControl("pm unsuspend --user 0 $packageName; pm enable --user 0 $packageName")

    private fun runControl(command: String): ControlOutcome {
        if (!PrivilegeShell.isReady()) return ControlOutcome.FAILED
        val output = PrivilegeShell.exec(command) ?: return ControlOutcome.FAILED
        val lower = output.lowercase()
        val failed = lower.contains("error") || lower.contains("exception") ||
            lower.contains("denied") || lower.contains("failure") ||
            lower.contains("unknown package")
        return if (failed) ControlOutcome.FAILED else ControlOutcome.DONE
    }

    /**
     * Reads which packages are currently suspended or disabled. Both are collected in one shell
     * round trip because each refresh needs them for the whole list.
     */
    private fun loadControlState(usePrivileged: Boolean): ControlState {
        if (!usePrivileged) return ControlState()
        val output = PrivilegeShell.exec(CONTROL_STATE_SCRIPT) ?: return ControlState()
        val frozen = HashSet<String>()
        val disabled = HashSet<String>()
        var section = SECTION_NONE
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line == FROZEN_MARKER -> section = SECTION_FROZEN
                line == DISABLED_MARKER -> section = SECTION_DISABLED
                section == SECTION_FROZEN -> frozen.add(line)
                section == SECTION_DISABLED -> disabled.add(line)
            }
        }
        return ControlState(frozen, disabled)
    }

    private fun sample(usePrivileged: Boolean): ProcsSnapshot? = try {
        if (usePrivileged) {
            ProcReader.readPrivilegedSnapshot()
        } else {
            val runningProcesses: List<ActivityManager.RunningAppProcessInfo> = try {
                activityManager.getRunningAppProcesses() ?: emptyList()
            } catch (t: Throwable) {
                emptyList()
            }
            val pids: Set<Int> = runningProcesses
                .map { process -> process.pid }
                .filter { pid -> pid > 0 }
                .toSet()
            ProcReader.readDirectSnapshot(pids)
        }
    } catch (t: Throwable) {
        null
    }

    private fun loadInstalledApps(): List<InstalledApp> {
        val applications = try {
            packageManager.getInstalledApplications(0)
        } catch (t: Throwable) {
            emptyList<ApplicationInfo>()
        }

        return applications.map { info ->
            val isSystem = (info.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            InstalledApp(
                packageName = info.packageName,
                label = try {
                    packageManager.getApplicationLabel(info).toString()
                } catch (t: Throwable) {
                    info.packageName
                },
                uid = info.uid,
                isSystem = isSystem,
                icon = try {
                    packageManager.getApplicationIcon(info)
                } catch (t: Throwable) {
                    null
                },
            )
        }
    }

    private fun loadRunningInfo(): MutableMap<String, RunningInfo> {
        val map = HashMap<String, RunningInfo>()

        try {
            val runningProcesses: List<ActivityManager.RunningAppProcessInfo> =
                activityManager.getRunningAppProcesses() ?: emptyList()
            runningProcesses.forEach { process ->
                if (process.pid > 0) {
                    val entry = map.getOrPut(process.processName.substringBefore(':')) {
                        RunningInfo()
                    }
                    entry.pids.add(process.pid)
                }
            }
        } catch (_: Throwable) {
        }

        try {
            @Suppress("DEPRECATION")
            val services = activityManager.getRunningServices(Int.MAX_VALUE)
            services?.forEach { service ->
                val entry = map.getOrPut(service.service.packageName) { RunningInfo() }
                entry.serviceCount += 1
                if (service.pid > 0) entry.pids.add(service.pid)
            }
        } catch (_: Throwable) {
        }

        return map
    }

    private fun computeCpuByPid(first: ProcsSnapshot?, second: ProcsSnapshot?): Map<Int, Float> {
        if (first == null || second == null) return emptyMap()
        val totalDelta = second.totalJiffies - first.totalJiffies
        if (totalDelta <= 0L) return emptyMap()

        val firstJiffies = first.jiffiesByPid()
        val result = HashMap<Int, Float>()
        second.jiffiesByPid().forEach { (pid, secondValue) ->
            val firstValue = firstJiffies[pid] ?: return@forEach
            val delta = secondValue - firstValue
            if (delta < 0L) return@forEach
            result[pid] = (delta.toFloat() / totalDelta.toFloat()) * 100f
        }
        return result
    }

    private fun computeTotalCpu(first: ProcsSnapshot?, second: ProcsSnapshot?): Float {
        if (first == null || second == null) return UNKNOWN
        val totalDelta = second.totalJiffies - first.totalJiffies
        if (totalDelta <= 0L) return UNKNOWN
        val idleDelta = second.idleJiffies - first.idleJiffies
        val busy = (totalDelta - idleDelta).coerceAtLeast(0L)
        return (busy.toFloat() / totalDelta.toFloat()) * 100f
    }

    companion object {
        private const val CPU_SAMPLE_INTERVAL_MS = 500L
        const val UNKNOWN = -1f

        private const val SECTION_NONE = 0
        private const val SECTION_FROZEN = 1
        private const val SECTION_DISABLED = 2
        private const val FROZEN_MARKER = "@@FROZEN@@"
        private const val DISABLED_MARKER = "@@DISABLED@@"

        /**
         * Suspended and disabled packages in one round trip. `dumpsys package` is the only command
         * that exposes the suspended flag; `pm list packages -d` covers disabled ones.
         */
        private val CONTROL_STATE_SCRIPT = """
            echo $FROZEN_MARKER
            dumpsys package packages 2>/dev/null | awk '/^  Package \[/{p=${'$'}2; gsub(/[][:]/,"",p)} /suspended=true/{print p}' | sort -u
            echo $DISABLED_MARKER
            pm list packages -d | sed 's/^package://'
        """.trimIndent()
    }
}
