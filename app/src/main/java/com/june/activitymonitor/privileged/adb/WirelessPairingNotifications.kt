package com.june.activitymonitor.privileged.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.june.activitymonitor.R
import com.june.activitymonitor.ui.MainActivity

/** Persistent pairing input and progress notifications. */
object WirelessPairingNotifications {

    const val CHANNEL_ID = "wireless_pairing"
    const val NOTIFICATION_ID = 4101

    const val KEY_PORT = "pairing_port"
    const val KEY_CODE = "pairing_code"
    const val KEY_CONNECT_PORT = "wireless_connect_port"
    const val EXTRA_FIELD = "pairing_field"
    const val EXTRA_PORT = "pairing_port_value"
    const val EXTRA_CODE = "pairing_code_value"

    const val ACTION_INPUT = "com.june.activitymonitor.action.PAIRING_INPUT"
    const val ACTION_OPEN_WIRELESS = "com.june.activitymonitor.action.OPEN_WIRELESS"
    const val ACTION_REENTER = "com.june.activitymonitor.action.PAIRING_REENTER"
    const val ACTION_RETRY_CONNECT = "com.june.activitymonitor.action.PAIRING_RETRY_CONNECT"

    fun showFreshPairInput(context: Context) {
        WirelessPairingStore.clear(context)
        showPairInput(context)
    }

    fun showPairInput(context: Context, error: String? = null) {
        ensureChannel(context)
        val port = WirelessPairingStore.port(context)
        val code = WirelessPairingStore.code(context)
        val status = when {
            port != null && code != null ->
                context.getString(R.string.pair_notification_both_ready)
            port != null ->
                context.getString(R.string.pair_notification_port_ready, port)
            code != null ->
                context.getString(R.string.pair_notification_code_ready)
            else ->
                context.getString(R.string.pair_notification_waiting_fields)
        }
        val text = error?.let { "$it\n$status" } ?: status

        val notification = baseBuilder(context)
            .setContentTitle(context.getString(R.string.pair_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(
                remoteInputAction(
                    context,
                    KEY_PORT,
                    context.getString(
                        if (port == null) R.string.pair_notification_action_port
                        else R.string.pair_notification_action_change_port,
                    ),
                    requestCode = 11,
                ),
            )
            .addAction(
                remoteInputAction(
                    context,
                    KEY_CODE,
                    context.getString(
                        if (code == null) R.string.pair_notification_action_code
                        else R.string.pair_notification_action_change_code,
                    ),
                    requestCode = 12,
                ),
            )
            .addAction(
                action(
                    context,
                    R.string.pair_notification_action_open_wireless,
                    Intent(context, WirelessPairingReceiver::class.java)
                        .setAction(ACTION_OPEN_WIRELESS),
                    requestCode = 13,
                ),
            )
            .build()
        notify(context, notification)
    }

    fun progressNotification(context: Context, text: String): Notification =
        baseBuilder(context)
            .setContentTitle(context.getString(R.string.pair_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent(context))
            .build()

    fun showProgress(context: Context, text: String) {
        ensureChannel(context)
        notify(context, progressNotification(context, text))
    }

    fun showConnectPortNeeded(context: Context) {
        ensureChannel(context)
        val cachedPort = WirelessAdbPortStore.port(context)
        val text = if (cachedPort == null) {
            context.getString(R.string.pair_notification_connect_port_needed)
        } else {
            context.getString(R.string.pair_notification_connect_port_retry, cachedPort)
        }
        val notification = baseBuilder(context)
            .setContentTitle(context.getString(R.string.pair_notification_connect_port_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .addAction(
                remoteInputAction(
                    context,
                    KEY_CONNECT_PORT,
                    context.getString(R.string.pair_notification_action_connect_port),
                    requestCode = 18,
                ),
            )
            .addAction(
                action(
                    context,
                    R.string.pair_notification_action_open_wireless,
                    Intent(context, WirelessPairingReceiver::class.java)
                        .setAction(ACTION_OPEN_WIRELESS),
                    requestCode = 19,
                ),
            )
            .build()
        notify(context, notification)
    }

    fun showSuccess(context: Context) {
        ensureChannel(context)
        val notification = baseBuilder(context)
            .setContentTitle(context.getString(R.string.pair_notification_success_title))
            .setContentText(context.getString(R.string.pair_notification_success))
            .setStyle(bigTextStyle(context, R.string.pair_notification_success))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, notification)
    }

    fun showPairingFailure(context: Context) {
        showFailure(
            context,
            context.getString(R.string.pair_notification_pair_failed_title),
            context.getString(R.string.pair_notification_pair_failed),
            retryConnect = false,
        )
    }

    fun showConnectFailure(context: Context) {
        showFailure(
            context,
            context.getString(R.string.pair_notification_connect_failed_title),
            context.getString(R.string.pair_notification_connect_failed),
            retryConnect = true,
        )
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun showFailure(
        context: Context,
        title: String,
        text: String,
        retryConnect: Boolean,
    ) {
        ensureChannel(context)
        val builder = baseBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(openAppIntent(context))
            .addAction(
                action(
                    context,
                    R.string.pair_notification_reenter,
                    Intent(context, WirelessPairingReceiver::class.java)
                        .setAction(ACTION_REENTER),
                    requestCode = 14,
                ),
            )

        if (retryConnect) {
            builder.addAction(
                0,
                context.getString(R.string.pair_notification_retry_connect),
                PendingIntent.getService(
                    context,
                    15,
                    Intent(context, WirelessPairingService::class.java)
                        .setAction(WirelessPairingService.ACTION_RETRY_CONNECT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        builder.addAction(
            action(
                context,
                R.string.pair_notification_action_open_wireless,
                Intent(context, WirelessPairingReceiver::class.java)
                    .setAction(ACTION_OPEN_WIRELESS),
                requestCode = 16,
            ),
        )
        notify(context, builder.build())
    }

    private fun remoteInputAction(
        context: Context,
        key: String,
        label: String,
        requestCode: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, WirelessPairingReceiver::class.java)
            .setAction(ACTION_INPUT)
            .putExtra(EXTRA_FIELD, key)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(key)
            .setLabel(label)
            .setAllowFreeFormInput(true)
            .build()
        return NotificationCompat.Action.Builder(0, label, pendingIntent)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun action(
        context: Context,
        labelRes: Int,
        intent: Intent,
        requestCode: Int,
    ): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, context.getString(labelRes), pendingIntent)
            .build()
    }

    private fun baseBuilder(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        17,
        Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun bigTextStyle(context: Context, textRes: Int): NotificationCompat.BigTextStyle =
        NotificationCompat.BigTextStyle().bigText(context.getString(textRes))

    private fun notify(context: Context, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pair_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.pair_notification_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}

object WirelessPairingStore {
    private const val PREFS = "wireless_pairing_input"
    private const val KEY_PORT = "port"
    private const val KEY_CODE = "code"
    private const val KEY_PAIRED = "paired"

    fun port(context: Context): String? = prefs(context).getString(KEY_PORT, null)

    fun code(context: Context): String? = prefs(context).getString(KEY_CODE, null)

    fun isPaired(context: Context): Boolean = prefs(context).getBoolean(KEY_PAIRED, false)

    fun markPaired(context: Context) {
        prefs(context).edit().putBoolean(KEY_PAIRED, true).apply()
    }

    fun savePort(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PORT, value).apply()
    }

    fun saveCode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CODE, value).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PORT).remove(KEY_CODE).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )
}
