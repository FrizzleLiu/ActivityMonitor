package com.june.activitymonitor.privileged.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** Runs the adb executable bundled by LADB instead of keeping an ADB client inside this process. */
object NativeAdb {

    private const val TAG = "AMNativeAdb"
    private const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    private const val PAIRING_DEVICE_NAME = "ActivityMonitor"

    data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
    ) {
        val succeeded: Boolean get() = !timedOut && exitCode == 0
    }

    data class PairResult(val success: Boolean, val output: String)

    @Volatile
    private var connectedSerial: String? = null

    fun pair(context: Context, port: Int, pairingCode: String): PairResult {
        if (port !in 1..65_535 || !pairingCode.matches(Regex("[0-9]{6}"))) {
            return PairResult(false, "invalid pairing port or code")
        }
        ensurePairingIdentity(context)
        PairingLog.record(context, "native pair process starting port=$port")
        val process = startProcess(context, listOf("pair", "localhost:$port"))
        val output = ByteArrayOutputStream()
        val reader = collectOutput(process, output)
        reader.start()
        val input = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)

        try {
            // adb pair prints its prompt before reading the pairing code from stdin.
            SystemClock.sleep(5_000)
            PairingLog.record(context, "native pair submitting code port=$port")
            // Do not close stdin here. Closing it raises EOF in adb's prompt loop while the
            // pairing handshake is still running, which can leave the process alive indefinitely.
            input.appendLine(pairingCode)
            input.flush()
            PairingLog.record(context, "native pair code submitted port=$port")
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to submit pairing code", t)
            PairingLog.record(context, "native pair code submit failed port=$port", t)
        }

        PairingLog.record(context, "native pair waiting for completion port=$port")
        val waitResult = waitForProcess(process, 10_000L)
        PairingLog.record(
            context,
            "native pair process finished port=$port completed=${waitResult.completed} " +
                "exit=${waitResult.exitCode}",
        )
        reader.join(2_000)
        runCatching { input.close() }

        val text = output.toString(StandardCharsets.UTF_8.name()).trim()
        val exitCode = waitResult.exitCode
        // LADB treats adb pair's exit code as the source of truth. Some adb builds print
        // non-fatal diagnostics to the same stream, so matching words such as "error" here
        // incorrectly turns a successful pairing into a failure.
        val success = waitResult.completed && exitCode == 0 &&
            text.contains("Successfully paired", ignoreCase = true)
        PairingLog.record(
            context,
            "native adb pair port=$port completed=${waitResult.completed} " +
                "exit=$exitCode success=$success output=$text",
        )
        return PairResult(success, text)
    }

    fun startServer(context: Context): Boolean {
        val result = runCommand(context, listOf("start-server"), 30_000)
        PairingLog.record(
            context,
            "native adb start-server exit=${result.exitCode} timedOut=${result.timedOut} " +
                "output=${result.output.trim()}",
        )
        return result.succeeded
    }

    fun connect(context: Context, port: Int): Boolean {
        val serial = "localhost:$port"
        val result = runCommand(context, listOf("connect", serial), 12_000)
        val output = result.output.trim()
        val ready = waitForDeviceReady(context, serial, 4_000)
        if (ready) connectedSerial = serial
        PairingLog.record(
            context,
            "native adb connect serial=$serial exit=${result.exitCode} ready=$ready " +
                "output=$output",
        )
        return ready
    }

    fun findReadyWirelessSerial(context: Context): String? {
        connectedSerial?.let { serial ->
            if (isDeviceReady(context, serial)) return serial
        }
        val devices = runCommand(context, listOf("devices"), 8_000)
        val serial = devices.output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.split(Regex("\\s+")) }
            .firstOrNull { fields ->
                fields.size >= 2 && fields[1] == "device" &&
                    (fields[0].startsWith("localhost:") ||
                        fields[0].startsWith("127.0.0.1:") ||
                        fields[0].contains("_adb-tls-connect._tcp."))
            }
            ?.first()
        if (serial != null) connectedSerial = serial
        return serial
    }

    fun mdnsConnectPorts(context: Context): List<Int> {
        val result = runCommand(context, listOf("mdns", "services"), 3_000)
        if (!result.succeeded) return emptyList()
        val ports = result.output.lineSequence()
            .filter { it.contains(CONNECT_SERVICE) }
            .mapNotNull { line ->
                // Example: "adb-... _adb-tls-connect._tcp. 127.0.0.1:41235".
                line.trim()
                    .split(Regex("\\s+"))
                    .lastOrNull()
                    ?.substringAfterLast(':')
                    ?.toIntOrNull()
            }
            .filter { it in 1..65_535 }
            .distinct()
            .toList()
        if (ports.isNotEmpty()) {
            PairingLog.record(context, "native adb mdns ports=$ports")
        }
        return ports
    }

    /**
     * Reads TCP LISTEN sockets owned by the shell UID. Android Wireless debugging's adbd listens
     * here even on ROMs/networks where the mDNS connect service is not advertised.
     */
    fun shellListenPorts(): List<Int> {
        val ports = linkedSetOf<Int>()
        for (path in PROC_TCP_FILES) {
            runCatching {
                File(path).useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val fields = line.trim().split(Regex("\\s+"))
                        if (fields.size < 8 || fields[3] != TCP_STATE_LISTEN) return@forEach
                        if (fields[7].toIntOrNull() != SHELL_UID) return@forEach
                        val port = fields[1].substringAfterLast(':').toIntOrNull(16)
                            ?: return@forEach
                        if (port in 1..65_535) ports.add(port)
                    }
                }
            }
        }
        return ports.toList()
    }

    fun runShell(context: Context, command: String, timeoutMs: Long): CommandResult? {
        // connect() has already verified this serial. Rechecking it with a separate
        // `adb devices` process is unnecessary and can hang while adbd is still settling.
        val serial = connectedSerial ?: findReadyWirelessSerial(context) ?: return null
        return runCommand(context, listOf("-s", serial, "shell", command), timeoutMs)
    }

    /** LADB grants this from its first adb shell so later Wireless debugging cycles work. */
    fun grantSecureSettings(context: Context, packageName: String): Boolean {
        val result = runShell(
            context,
            "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
            10_000,
        ) ?: return false
        PairingLog.record(
            context,
            "native pm grant WRITE_SECURE_SETTINGS exit=${result.exitCode} " +
                "timedOut=${result.timedOut} output=${result.output.trim()}",
        )
        return result.succeeded
    }

    fun disconnect(context: Context, port: Int) {
        runCommand(context, listOf("disconnect", "localhost:$port"), 5_000)
        if (connectedSerial == "localhost:$port") connectedSerial = null
    }

    fun killServer(context: Context) {
        runCommand(context, listOf("kill-server"), 10_000)
        connectedSerial = null
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** LADB-compatible workaround for devices that need Wireless debugging restarted. */
    fun cycleWirelessDebugging(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, SETTING_ADB_WIFI_ENABLED, 0) == 1) {
            Settings.Global.putInt(resolver, SETTING_ADB_WIFI_ENABLED, 0)
            SystemClock.sleep(3_000)
        }
        Settings.Global.putInt(resolver, SETTING_ADB_WIFI_ENABLED, 1)
        SystemClock.sleep(3_000)
        Settings.Global.putInt(resolver, SETTING_ADB_WIFI_ENABLED, 0)
        SystemClock.sleep(3_000)
        Settings.Global.putInt(resolver, SETTING_ADB_WIFI_ENABLED, 1)
        SystemClock.sleep(3_000)
        PairingLog.record(context, "native adb cycled Wireless debugging")
        return true
    }

    fun adbPath(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libadb.so")

    private fun isDeviceReady(context: Context, serial: String): Boolean {
        val state = runCommand(context, listOf("-s", serial, "get-state"), 3_000)
        return state.output.lineSequence().any { it.trim() == "device" }
    }

    private fun waitForDeviceReady(context: Context, serial: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (isDeviceReady(context, serial)) return true
            SystemClock.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun runCommand(
        context: Context,
        arguments: List<String>,
        timeoutMs: Long,
    ): CommandResult {
        return try {
            val process = startProcess(context, arguments)
            val output = ByteArrayOutputStream()
            val reader = collectOutput(process, output)
            reader.start()
            val waitResult = waitForProcess(process, timeoutMs)
            reader.join(2_000)
            CommandResult(
                exitCode = waitResult.exitCode,
                output = output.toString(StandardCharsets.UTF_8.name()),
                timedOut = !waitResult.completed,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "adb command failed: ${arguments.firstOrNull()}", t)
            PairingLog.record(context, "native adb command failed args=$arguments", t)
            CommandResult(-1, t.message.orEmpty(), true)
        }
    }

    private fun startProcess(context: Context, arguments: List<String>): Process {
        val adb = adbPath(context)
        check(adb.isFile) { "Bundled adb executable is missing: ${adb.absolutePath}" }
        val command = ArrayList<String>(arguments.size + 1).apply {
            add(adb.absolutePath)
            addAll(arguments)
        }
        return ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()
    }

    private data class ProcessWaitResult(val completed: Boolean, val exitCode: Int)

    /** Polls exitValue instead of waitFor; several vendor Process implementations never wake up. */
    private fun waitForProcess(process: Process, timeoutMs: Long): ProcessWaitResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return ProcessWaitResult(true, process.exitValue())
            } catch (_: IllegalThreadStateException) {
                SystemClock.sleep(50)
            }
        }
        process.destroyForcibly()
        SystemClock.sleep(250)
        val exitCode = try {
            process.exitValue()
        } catch (_: IllegalThreadStateException) {
            -1
        }
        return ProcessWaitResult(false, exitCode)
    }

    private fun collectOutput(process: Process, output: ByteArrayOutputStream): Thread =
        Thread {
            runCatching {
                process.inputStream.use { input -> input.copyTo(output) }
            }
        }.apply { isDaemon = true }

    /**
     * Native adb takes the display name from the public-key comment. Rewrite it before pairing so
     * this app always appears as ActivityMonitor instead of the Android app UID/hostname.
     */
    private fun ensurePairingIdentity(context: Context) {
        val adbHome = File(context.filesDir, ".android")
        if (!adbHome.isDirectory && !adbHome.mkdirs()) {
            PairingLog.record(context, "native adb identity directory unavailable")
            return
        }

        val privateKey = File(adbHome, "adbkey")
        if (!privateKey.isFile) {
            val keygen = runCommand(context, listOf("keygen", privateKey.absolutePath), 10_000)
            PairingLog.record(
                context,
                "native adb keygen exit=${keygen.exitCode} timedOut=${keygen.timedOut}",
            )
        }

        val publicKey = File(adbHome, "adbkey.pub")
        val contents = when {
            publicKey.isFile -> publicKey.readText()
            privateKey.isFile -> runCommand(
                context,
                listOf("pubkey", privateKey.absolutePath),
                10_000,
            ).output
            else -> return
        }
        val parts = contents.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            PairingLog.record(context, "native adb identity public key unavailable")
            return
        }

        val identity = "${parts[0]} ${parts[1]} $PAIRING_DEVICE_NAME\n"
        if (contents != identity) {
            runCatching { publicKey.writeText(identity) }
                .onSuccess {
                    PairingLog.record(
                        context,
                        "native adb identity set name=$PAIRING_DEVICE_NAME",
                    )
                }
                .onFailure {
                    PairingLog.record(context, "native adb identity update failed", it)
                }
        }
    }

    private const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val TCP_STATE_LISTEN = "0A"
    private const val SHELL_UID = 2000
    private val PROC_TCP_FILES = listOf("/proc/net/tcp", "/proc/net/tcp6")
}
