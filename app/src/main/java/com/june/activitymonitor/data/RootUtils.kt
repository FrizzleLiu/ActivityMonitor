package com.june.activitymonitor.data

import java.util.concurrent.TimeUnit

/**
 * Small helper around the `su` binary. Everything here is best effort: if the device is not
 * rooted (or the user denies the prompt) every method fails gracefully and the caller falls back
 * to the regular, permission limited Android APIs.
 */
object RootUtils {

    @Volatile
    private var cachedAvailability: Boolean? = null

    fun isAvailable(): Boolean {
        cachedAvailability?.let { return it }
        synchronized(this) {
            cachedAvailability?.let { return it }
            val available = checkRoot()
            cachedAvailability = available
            return available
        }
    }

    private fun checkRoot(): Boolean = try {
        exec(listOf("su", "-c", "id"), timeoutSec = 4)?.contains("uid=0") == true
    } catch (t: Throwable) {
        false
    }

    /**
     * Runs [script] inside a root shell and returns its stdout, or null when it failed/timed out.
     */
    fun run(script: String, timeoutSec: Long = 12): String? = try {
        exec(listOf("su", "-c", script), timeoutSec)
    } catch (t: Throwable) {
        null
    }

    private fun exec(command: List<String>, timeoutSec: Long): String? {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return null
        }
        process.outputStream.close()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            } catch (_: Throwable) {
                // ignored, we only care about whatever was produced before the failure
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = try {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        reader.join(1500)
        return output.toString()
    }
}
