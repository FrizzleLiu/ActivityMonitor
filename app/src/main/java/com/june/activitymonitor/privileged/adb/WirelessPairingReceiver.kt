package com.june.activitymonitor.privileged.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.june.activitymonitor.R
import com.june.activitymonitor.util.DeveloperSettings

class WirelessPairingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WirelessPairingNotifications.ACTION_INPUT -> handleInput(context, intent)
            WirelessPairingNotifications.ACTION_OPEN_WIRELESS -> {
                DeveloperSettings.openWirelessDebugging(context)
            }
            WirelessPairingNotifications.ACTION_REENTER -> {
                WirelessPairingStore.clear(context)
                WirelessPairingNotifications.showPairInput(context)
                DeveloperSettings.openWirelessDebugging(context)
            }
            WirelessPairingNotifications.ACTION_RETRY_CONNECT -> {
                startService(context, WirelessPairingService.ACTION_RETRY_CONNECT)
            }
        }
    }

    private fun handleInput(context: Context, intent: Intent) {
        val field = intent.getStringExtra(WirelessPairingNotifications.EXTRA_FIELD)
            ?: return
        val value = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(field)
            ?.toString()
            ?.trim()
            .orEmpty()

        when (field) {
            WirelessPairingNotifications.KEY_PORT -> {
                val port = value.toIntOrNull()
                if (port == null || port !in 1..65_535) {
                    WirelessPairingNotifications.showPairInput(
                        context,
                        context.getString(R.string.pair_port_invalid),
                    )
                    return
                }
                WirelessPairingStore.savePort(context, port.toString())
            }

            WirelessPairingNotifications.KEY_CODE -> {
                if (!value.matches(Regex("[0-9]{6}"))) {
                    WirelessPairingNotifications.showPairInput(
                        context,
                        context.getString(R.string.pair_code_invalid),
                    )
                    return
                }
                WirelessPairingStore.saveCode(context, value)
            }

            WirelessPairingNotifications.KEY_CONNECT_PORT -> {
                val port = value.toIntOrNull()
                if (port == null || port !in 1..65_535) {
                    WirelessPairingNotifications.showConnectPortNeeded(context)
                    return
                }
                WirelessAdbPortStore.save(context, port)
                WirelessPairingNotifications.showProgress(
                    context,
                    context.getString(R.string.helper_connecting),
                )
                startService(context, WirelessPairingService.ACTION_RETRY_CONNECT)
                return
            }
        }

        val port = WirelessPairingStore.port(context)
        val code = WirelessPairingStore.code(context)
        if (port != null && code != null) {
            startService(
                context,
                WirelessPairingService.ACTION_START_PAIR,
                port,
                code,
            )
        } else {
            WirelessPairingNotifications.showPairInput(context)
        }
    }

    private fun startService(
        context: Context,
        action: String,
        port: String? = null,
        code: String? = null,
    ) {
        val service = Intent(context, WirelessPairingService::class.java).setAction(action)
        port?.let { service.putExtra(WirelessPairingNotifications.EXTRA_PORT, it) }
        code?.let { service.putExtra(WirelessPairingNotifications.EXTRA_CODE, it) }
        ContextCompat.startForegroundService(context, service)
    }
}
