package com.june.activitymonitor.privileged.adb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wireless ADB pairing and connection backed by the same native adb executable used by LADB. */
object WirelessAdb {

    private const val TAG = "AMWirelessAdb"
    private const val RETRY_PORT_AFTER_MS = 4_000L

    data class PairingResult(val success: Boolean, val error: String?) {
        companion object {
            val SUCCESS = PairingResult(true, null)
        }
    }

    fun startConnectDiscovery(context: Context) {
        WirelessConnectDiscovery.start(context)
    }

    suspend fun pair(context: Context, pairingPort: Int, pairingCode: String): PairingResult =
        withContext(Dispatchers.IO) {
            PairingLog.record(
                context,
                "native pair start port=$pairingPort codeLength=${pairingCode.length}",
            )
            val result = NativeAdb.pair(context, pairingPort, pairingCode)
            // LADB always resets the server after pair so the next connect starts from clean state.
            NativeAdb.killServer(context)
            // Registration can happen after the existing DNS-SD query went idle. Re-query now so
            // the connect port is not missed on ROMs that do not answer the first request.
            WirelessConnectDiscovery.restart(context)
            PairingLog.record(
                context,
                "native pair result success=${result.success} port=$pairingPort",
            )
            PairingResult(
                success = result.success,
                error = result.output.takeIf(String::isNotBlank),
            )
        }

    /** Waits for a native-adb connection to the phone's own Wireless debugging daemon. */
    suspend fun connect(
        context: Context,
        timeoutMs: Long = 60_000L,
        onWaitingForPort: (() -> Unit)? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            startConnectDiscovery(context)
            NativeAdb.startServer(context)
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + timeoutMs
            val nextAttemptAt = HashMap<Int, Long>()
            val procPorts = LinkedHashSet<Int>()
            var lastNativeMdnsCheck = 0L
            var lastDiscoveryRefresh = startedAt
            var lastShellPortCheck = 0L
            var loggedNoTarget = false
            var waitingNotified = false

            while (System.currentTimeMillis() < deadline) {
                val ports = linkedSetOf<Int>()
                WirelessConnectDiscovery.awaitTarget(100)?.let { ports.add(it.port) }
                WirelessAdbPortStore.port(context)?.let(ports::add)
                val now = System.currentTimeMillis()
                if (now - lastShellPortCheck >= 2_000L) {
                    lastShellPortCheck = now
                    NativeAdb.shellListenPorts().forEach { port ->
                        if (procPorts.add(port)) {
                            PairingLog.record(context, "shell adb listener port=$port")
                        }
                        ports.add(port)
                    }
                }
                if (ports.isEmpty() && now - lastNativeMdnsCheck >= 8_000L) {
                    lastNativeMdnsCheck = now
                    ports.addAll(NativeAdb.mdnsConnectPorts(context))
                }
                if (ports.isEmpty() && now - lastDiscoveryRefresh >= 5_000L) {
                    lastDiscoveryRefresh = now
                    PairingLog.record(context, "native connect refreshing mdns discovery")
                    WirelessConnectDiscovery.restart(context)
                }

                for (port in ports) {
                    val nowForPort = System.currentTimeMillis()
                    if (nowForPort < (nextAttemptAt[port] ?: 0L)) continue
                    nextAttemptAt[port] = nowForPort + RETRY_PORT_AFTER_MS
                    PairingLog.record(context, "native connect attempt port=$port")
                    if (NativeAdb.connect(context, port)) return@withContext true
                    NativeAdb.disconnect(context, port)
                    WirelessConnectDiscovery.invalidate(port)
                    WirelessAdbPortStore.remove(context, port)
                }
                if (ports.isEmpty() && !loggedNoTarget) {
                    loggedNoTarget = true
                    PairingLog.record(
                        context,
                        "native connect waiting: no _adb-tls-connect target yet",
                    )
                }
                if (!waitingNotified && System.currentTimeMillis() - startedAt >= 10_000L) {
                    waitingNotified = true
                    PairingLog.record(
                        context,
                        "native connect waiting for port after 10s cached=${WirelessAdbPortStore.port(context)}",
                    )
                    onWaitingForPort?.invoke()
                }
                Thread.sleep(250)
            }

            PairingLog.record(context, "native connect timed out after ${timeoutMs}ms")
            false
        }

    /** Applies LADB's Wireless-debugging restart workaround when the permission is available. */
    suspend fun cycleWirelessDebuggingForConnect(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val cycled = NativeAdb.cycleWirelessDebugging(context)
            if (cycled) startConnectDiscovery(context)
            PairingLog.record(context, "wireless debugging cycle result=$cycled")
            cycled
        }

    suspend fun runShell(context: Context, command: String, timeoutMs: Long = 20_000L): String? =
        withContext(Dispatchers.IO) {
            val result = NativeAdb.runShell(context, command, timeoutMs) ?: return@withContext null
            PairingLog.record(
                context,
                "native shell exit=${result.exitCode} timedOut=${result.timedOut} " +
                    "output=${result.output.take(2_000)}",
            )
            result.output
        }

    suspend fun bootstrapHelper(
        context: Context,
        command: String,
        connectBeforeHelper: Boolean = true,
    ): Boolean {
        if (connectBeforeHelper && !connect(context)) return false
        if (!NativeAdb.canWriteSecureSettings(context)) {
            NativeAdb.grantSecureSettings(context, context.packageName)
        }
        val result = NativeAdb.runShell(context, command, 20_000) ?: return false
        Log.i(TAG, "bootstrap exit=${result.exitCode} timedOut=${result.timedOut}")
        PairingLog.record(
            context,
            "bootstrap exit=${result.exitCode} timedOut=${result.timedOut} " +
                "output=${result.output.take(2_000)}",
        )
        return result.exitCode == 0 && !result.timedOut
    }
}
