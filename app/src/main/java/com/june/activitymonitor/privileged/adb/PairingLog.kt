package com.june.activitymonitor.privileged.adb

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small persistent log for diagnosing Wireless debugging pairing without a live adb session. */
object PairingLog {

    const val FILE_NAME = "wireless_pairing.log"
    private const val MAX_BYTES = 256 * 1024L

    @Synchronized
    fun record(context: Context, message: String, throwable: Throwable? = null) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) file.delete()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(Date())
            file.appendText(
                buildString {
                    append(timestamp)
                    append(' ')
                    append(message)
                    append('\n')
                    throwable?.let {
                        append(it.stackTraceToString())
                        append('\n')
                    }
                },
            )
        }
    }

    fun read(context: Context): String = runCatching {
        File(context.filesDir, FILE_NAME).takeIf(File::exists)?.readText().orEmpty()
    }.getOrDefault("")
}
