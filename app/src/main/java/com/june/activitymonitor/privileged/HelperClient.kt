package com.june.activitymonitor.privileged

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The ordinary app process' side of the privileged channel: connects to [HelperServer] over
 * loopback TCP and runs a shell command there. Returns null when the helper is not running, the
 * token is wrong, or the command timed out, so callers can degrade gracefully.
 */
object HelperClient {

    private const val TAG = "AMHelperCli"
    private const val PACKAGE_NAME = "com.june.activitymonitor"
    private const val SERVER_CLASS = "com.june.activitymonitor.privileged.HelperServer"

    /**
     * The shell command that starts [HelperServer] as the adb shell user. `pm path` resolves the
     * APK path on device, and `setsid` detaches the server from the calling shell so it keeps
     * running after the command returns. Used both by the in-app wireless ADB client and, wrapped
     * for a computer, by [pcAdbCommand].
     */
    fun bootstrapCommand(): String =
        "CLASSPATH=\$(pm path $PACKAGE_NAME | sed \"s/^package://\") " +
            "setsid app_process /system/bin --nice-name=am_helper $SERVER_CLASS " +
            "--token=${HelperConfig.token()} >/dev/null 2>&1 &"

    /** The bootstrap command wrapped so the user can paste it into a computer terminal. */
    fun pcAdbCommand(): String = "adb shell '" + bootstrapCommand() + "'"

    /** True when the privileged helper is reachable. */
    fun isRunning(): Boolean = exec("echo ok", timeoutMs = 2_000)?.trim() == "ok"

    /**
     * Runs [command] with the helper's shell privilege and returns its combined stdout/stderr,
     * or null when the helper is not available / the command timed out.
     */
    fun exec(command: String, timeoutMs: Int = 12_000): String? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", HelperConfig.PORT), 2_000)
                socket.soTimeout = timeoutMs
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                output.writeUTF(HelperConfig.token())
                output.writeUTF(command)
                output.flush()
                val length = input.readInt()
                if (length < 0) return null
                val bytes = ByteArray(length)
                input.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed: $t")
            null
        }
    }
}
