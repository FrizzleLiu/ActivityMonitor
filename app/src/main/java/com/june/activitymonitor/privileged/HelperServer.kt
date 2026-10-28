package com.june.activitymonitor.privileged

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The privileged half of the app.
 *
 * It is NOT started by [com.june.activitymonitor.App]; instead the user launches it once from a
 * computer with `app_process`, which makes this class run with the `shell` (uid 2000) identity —
 * exactly the same privilege `adb shell` has. That lets it read every process' `/proc` entry and
 * run `am force-stop`.
 *
 * It listens on a loopback-only TCP port and executes the shell commands the UI process sends it.
 * A loopback TCP socket is used (rather than a Unix domain socket) because SELinux does not allow
 * an ordinary app to connect to a `shell` owned Unix socket; TCP loopback is the same channel
 * wireless-debugging tools use.
 *
 * Security: the port is reachable by every app, so each client must present the one time `--token=`
 * that the app bakes into the bootstrap command.
 */
object HelperServer {

    private const val TAG = "AMHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.firstOrNull { it.startsWith("--token=") }?.substringAfter("--token=").orEmpty()
        Log.i(TAG, "helper starting (token=${token.isNotEmpty()})")

        val server = try {
            ServerSocket(HelperConfig.PORT, 8, InetAddress.getByName("127.0.0.1"))
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed", t)
            return
        }
        Log.i(TAG, "listening on 127.0.0.1:${HelperConfig.PORT}")

        while (true) {
            val client = try {
                server.accept()
            } catch (t: Throwable) {
                Log.e(TAG, "accept failed", t)
                break
            }
            Thread { handle(client, token) }.apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket, token: String) {
        try {
            client.use { socket ->
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                val clientToken = input.readUTF()
                if (token.isNotEmpty() && clientToken != token) {
                    Log.w(TAG, "rejected client: bad token")
                    return
                }

                val command = input.readUTF()
                val result = execute(command)
                val bytes = result.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handle failed", t)
        }
    }

    private fun execute(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    } catch (t: Throwable) {
        "error: ${t.message}"
    }
}
