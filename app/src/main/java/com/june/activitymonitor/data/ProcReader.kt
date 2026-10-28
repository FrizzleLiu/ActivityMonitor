package com.june.activitymonitor.data

import android.system.Os
import android.system.OsConstants
import java.io.File

data class ProcEntry(
    val pid: Int,
    val name: String?,
    val cpuJiffies: Long,
    val rssBytes: Long,
)

data class ProcsSnapshot(
    val entries: List<ProcEntry>,
    val totalJiffies: Long,
    val idleJiffies: Long,
) {
    fun jiffiesByPid(): Map<Int, Long> = entries.associate { it.pid to it.cpuJiffies }

    fun rssByPid(): Map<Int, Long> = entries.associate { it.pid to it.rssBytes }

    fun namesByPid(): Map<Int, String> =
        entries.mapNotNull { entry -> entry.name?.let { entry.pid to it } }.toMap()
}

/**
 * Reads the Linux `/proc` pseudo filesystem. CPU time is expressed in "jiffies": the delta of a
 * process' jiffies divided by the delta of the machine wide jiffies yields that process' CPU
 * share. Without root only our own processes are readable, which is a platform limitation.
 */
object ProcReader {

    private val pageSize: Long by lazy {
        try {
            Os.sysconf(OsConstants._SC_PAGESIZE)
        } catch (t: Throwable) {
            4096L
        }
    }

    /**
     * A single shell snippet, executed with shell (embedded helper) or root privilege, that prints
     * three kinds of lines:
     *  - `T|totalJiffies|idleJiffies` from /proc/stat
     *  - `N|pid|processName`          from /proc/<pid>/cmdline
     *  - `S|pid|utime|stime|rssPages` from /proc/<pid>/stat
     *
     * `/proc/stat`'s `cpu` line is `cpu user nice system idle iowait irq softirq steal ...`, so
     * idle is field 5 (plus iowait in field 6). Summing only user..steal avoids double counting the
     * `guest` fields, which are already included in `user`.
     */
    private val SAMPLE_SCRIPT = """awk '/^cpu /{ t=0; for (i=2;i<=9;i++) t+=${'$'}i; print "T|" t "|" (${'$'}5+${'$'}6) }' /proc/stat
awk 'BEGIN{RS="\0"} NF { p=FILENAME; sub(/\/cmdline${'$'}/,"",p); sub(/^.*\//,"",p); print "N|" p "|" ${'$'}0 }' /proc/[0-9]*/cmdline 2>/dev/null
awk '{ i=index(${'$'}0,")"); r=substr(${'$'}0,i+2); split(r,a," "); print "S|" ${'$'}1 "|" a[12] "|" a[13] "|" a[22] }' /proc/[0-9]*/stat 2>/dev/null"""

    fun readPrivilegedSnapshot(): ProcsSnapshot? {
        val output = PrivilegeShell.exec(SAMPLE_SCRIPT) ?: return null
        if (output.isBlank()) return null
        return parsePrivilegedOutput(output)
    }

    fun readDirectSnapshot(pids: Collection<Int>): ProcsSnapshot {
        val (total, idle) = readCpuLine()
        val entries = pids.mapNotNull { pid ->
            readStatFile("/proc/$pid/stat")?.let { line ->
                parseStatLine(pid, line)
            }
        }
        return ProcsSnapshot(entries, total, idle)
    }

    private fun parsePrivilegedOutput(output: String): ProcsSnapshot {
        var total = 0L
        var idle = 0L
        val names = HashMap<Int, String>()
        val stats = HashMap<Int, LongArray>()

        output.lineSequence().forEach { line ->
            when {
                line.startsWith("T|") -> {
                    val parts = line.split('|')
                    total = parts.getOrNull(1)?.toLongOrNull() ?: total
                    idle = parts.getOrNull(2)?.toLongOrNull() ?: idle
                }

                line.startsWith("N|") -> {
                    val separator = line.indexOf('|', 2)
                    if (separator > 0) {
                        val pid = line.substring(2, separator).toIntOrNull()
                        val name = line.substring(separator + 1)
                        if (pid != null && name.isNotEmpty()) {
                            names[pid] = name
                        }
                    }
                }

                line.startsWith("S|") -> {
                    val parts = line.split('|')
                    val pid = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    val utime = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    val stime = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                    val rssPages = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    stats[pid] = longArrayOf(utime, stime, rssPages)
                }
            }
        }

        val entries = stats.map { (pid, values) ->
            ProcEntry(
                pid = pid,
                name = names[pid],
                cpuJiffies = values[0] + values[1],
                rssBytes = values[2].coerceAtLeast(0L) * pageSize,
            )
        }
        return ProcsSnapshot(entries, total, idle)
    }

    private fun parseStatLine(pid: Int, line: String): ProcEntry? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < 0 || close <= open) return null
        val fields = line.substring(close + 1).trim()
            .split(' ')
            .filter { it.isNotEmpty() }
        // After the "comm" field: [0]=state ... [11]=utime, [12]=stime, [21]=rss
        if (fields.size < 22) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        val rssPages = fields[21].toLongOrNull() ?: 0L
        return ProcEntry(pid, null, utime + stime, rssPages.coerceAtLeast(0L) * pageSize)
    }

    private fun readStatFile(path: String): String? = try {
        File(path).readText()
    } catch (t: Throwable) {
        null
    }

    private fun readCpuLine(): Pair<Long, Long> = try {
        val line = File("/proc/stat").readText()
            .lineSequence()
            .firstOrNull { it.startsWith("cpu ") }
        if (line == null) {
            0L to 0L
        } else {
            val tokens = line.trim().split(Regex("\\s+"))
            var total = 0L
            for (index in 1 until minOf(tokens.size, 9)) {
                total += tokens[index].toLongOrNull() ?: 0L
            }
            val idle = tokens.getOrNull(4)?.toLongOrNull() ?: 0L
            total to idle
        }
    } catch (t: Throwable) {
        0L to 0L
    }
}
