package com.june.activitymonitor.privileged.adb

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.june.activitymonitor.R
import com.june.activitymonitor.privileged.HelperClient
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Performs the long-running native-adb pairing while the app can stay in Settings. */
class WirelessPairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val wirelessStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_WIRELESS_DEBUG_STATUS) return
            if (intent.getIntExtra(EXTRA_WIRELESS_STATUS, -1) != WIRELESS_STATUS_CONNECTED) return
            val port = intent.getIntExtra(EXTRA_ADB_PORT, -1)
            if (port in 1..65_535) {
                WirelessAdbPortStore.save(context, port)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        WirelessPairingNotifications.ensureChannel(this)
        ContextCompat.registerReceiver(
            this,
            wirelessStatusReceiver,
            IntentFilter(ACTION_WIRELESS_DEBUG_STATUS),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!busy.compareAndSet(false, true)) return START_NOT_STICKY
        val action = intent?.action ?: ACTION_RETRY_CONNECT
        val firstMessage = when (action) {
            ACTION_RETRY_CONNECT -> getString(R.string.helper_connecting)
            else -> getString(R.string.pair_notification_pairing)
        }
        startForegroundCompat(firstMessage)

        when (action) {
            ACTION_START_PAIR -> {
                val port = intent?.getStringExtra(WirelessPairingNotifications.EXTRA_PORT)
                    ?.toIntOrNull()
                    ?: WirelessPairingStore.port(this)?.toIntOrNull()
                val code = intent?.getStringExtra(WirelessPairingNotifications.EXTRA_CODE)
                    ?: WirelessPairingStore.code(this)
                if (port == null || port !in 1..65_535 || code == null ||
                    !code.matches(Regex("[0-9]{6}"))
                ) {
                    finishPairingFailure()
                    return START_NOT_STICKY
                }
                scope.launch { pairAndConnect(port, code) }
            }

            ACTION_RETRY_CONNECT -> scope.launch { connectAndStartHelper() }
            else -> finishPairingFailure()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(wirelessStatusReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pairAndConnect(port: Int, code: String) {
        var paired = false
        try {
            paired = WirelessAdb.pair(this, port, code).success
            if (!paired) {
                finishPairingFailure()
                return
            }
            WirelessPairingStore.markPaired(this)

            updateProgress(getString(R.string.helper_connecting))
            WirelessAdb.cycleWirelessDebuggingForConnect(this)
            if (!WirelessAdb.connect(
                    this,
                    60_000L,
                    onWaitingForPort = { WirelessPairingNotifications.showConnectPortNeeded(this) },
                )
            ) {
                finishConnectFailure()
                return
            }
            startHelperAfterConnection()
        } catch (t: Throwable) {
            PairingLog.record(this, "pairAndConnect failed paired=$paired", t)
            if (paired) finishConnectFailure() else finishPairingFailure()
        }
    }

    private suspend fun connectAndStartHelper() {
        try {
            if (!WirelessAdb.connect(
                    this,
                    60_000L,
                    onWaitingForPort = { WirelessPairingNotifications.showConnectPortNeeded(this) },
                )
            ) {
                finishConnectFailure()
                return
            }
            startHelperAfterConnection()
        } catch (t: Throwable) {
            PairingLog.record(this, "connectAndStartHelper failed", t)
            finishConnectFailure()
        }
    }

    private suspend fun startHelperAfterConnection() {
        updateProgress(getString(R.string.helper_starting))
        // connect() above already established and verified this serial. Repeating discovery here
        // leaves some vendor adb builds waiting forever on their own daemon.
        val started = WirelessAdb.bootstrapHelper(
            this,
            HelperClient.bootstrapCommand(),
            connectBeforeHelper = false,
        )
        if (!started) {
            finishConnectFailure()
            return
        }
        WirelessPairingStore.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        WirelessPairingNotifications.showSuccess(this)
        busy.set(false)
        stopSelf()
    }

    private fun finishPairingFailure() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        WirelessPairingNotifications.showPairingFailure(this)
        busy.set(false)
        stopSelf()
    }

    private fun finishConnectFailure() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        WirelessPairingNotifications.showConnectFailure(this)
        busy.set(false)
        stopSelf()
    }

    private fun updateProgress(message: String) {
        WirelessPairingNotifications.showProgress(this, message)
    }

    private fun startForegroundCompat(message: String) {
        val notification = WirelessPairingNotifications.progressNotification(this, message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                WirelessPairingNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            startForeground(WirelessPairingNotifications.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START_PAIR = "com.june.activitymonitor.action.START_PAIRING"
        const val ACTION_RETRY_CONNECT = "com.june.activitymonitor.action.RETRY_CONNECT"
        private const val ACTION_WIRELESS_DEBUG_STATUS = "com.android.server.adb.WIRELESS_DEBUG_STATUS"
        private const val EXTRA_WIRELESS_STATUS = "status"
        private const val EXTRA_ADB_PORT = "adb_port"
        private const val WIRELESS_STATUS_CONNECTED = 4
    }
}
