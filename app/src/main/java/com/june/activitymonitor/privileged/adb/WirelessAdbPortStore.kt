package com.june.activitymonitor.privileged.adb

import android.content.Context

/** Caches the Wireless-debugging connect port reported by the system or entered by the user. */
object WirelessAdbPortStore {

    private const val PREFS = "wireless_adb_connect_port"
    private const val KEY_PORT = "port"

    fun port(context: Context): Int? = prefs(context)
        .getInt(KEY_PORT, -1)
        .takeIf { it in 1..65_535 }

    fun save(context: Context, port: Int) {
        if (port !in 1..65_535) return
        prefs(context).edit().putInt(KEY_PORT, port).apply()
        PairingLog.record(context, "wireless connect port cached=$port")
    }

    fun remove(context: Context, port: Int) {
        if (this.port(context) != port) return
        prefs(context).edit().remove(KEY_PORT).apply()
        PairingLog.record(context, "wireless connect port removed=$port")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE,
    )
}
