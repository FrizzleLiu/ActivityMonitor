package com.june.activitymonitor.privileged.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps a single TLS connect-port discovery alive for the lifetime of the app process. Android
 * stops advertising the connect service while the pairing dialog is open on some devices, so the
 * port must be discovered before pairing and retained until the post-pair connection succeeds.
 */
object WirelessConnectDiscovery {

    private const val TAG = "AMWirelessDiscovery"
    private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
    private const val RESTART_DELAY_MS = 3_000L
    private const val MAX_TARGETS = 8

    data class Target(
        val host: String,
        val port: Int,
        val serviceName: String,
        val expirationTime: Long?,
        val resolvedAt: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val targets = LinkedHashMap<Int, Target>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var started = false

    @Volatile
    private var restartScheduled = false

    @Volatile
    private var activeListener: NsdManager.DiscoveryListener? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        mainHandler.post { startOnMainThread() }
    }

    /** Re-issues the DNS-SD query, which is needed after Wireless debugging restarts. */
    fun restart(context: Context) {
        appContext = context.applicationContext
        mainHandler.post { restartOnMainThread() }
    }

    fun currentPort(): Int? = bestTarget()?.port

    fun awaitTarget(timeoutMs: Long): Target? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            bestTarget()?.let { return it }
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(150)
        } while (true)
        return null
    }

    fun invalidate(port: Int) {
        synchronized(lock) {
            val removed = targets.remove(port)
            if (removed != null) {
                val context = appContext
                if (context != null) {
                    PairingLog.record(context, "mdns target removed after failed connect port=$port")
                }
            }
        }
    }

    private fun startOnMainThread() {
        val context = appContext ?: return
        if (started) return

        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            log(context, "mdns discovery unavailable: NsdManager is null")
            return
        }

        started = true
        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (!isActive(listener)) return
                log(context, "mdns discovery started service=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isActive(listener)) return
                log(
                    context,
                    "mdns service found name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}",
                )
                resolve(context, manager, serviceInfo, serviceInfo.serviceName, 0, listener)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (!isActive(listener)) return
                // Keep the cached port. The service can disappear while the system pairing dialog
                // is open even though its ADB daemon remains reachable on loopback.
                log(context, "mdns service lost name=${serviceInfo.serviceName} (port retained)")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (!isActive(listener)) return
                started = false
                activeListener = null
                log(context, "mdns discovery stopped service=$serviceType; restarting")
                scheduleRestart(context)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!isActive(listener)) return
                started = false
                activeListener = null
                log(context, "mdns start failed service=$serviceType error=$errorCode")
                scheduleRestart(context)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!isActive(listener)) return
                started = false
                activeListener = null
                log(context, "mdns stop failed service=$serviceType error=$errorCode")
                scheduleRestart(context)
            }
        }
        activeListener = listener

        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            if (activeListener === listener) activeListener = null
            started = false
            Log.e(TAG, "Unable to start mDNS discovery", t)
            PairingLog.record(context, "mdns start exception", t)
            scheduleRestart(context)
        }
    }

    private fun resolve(
        context: Context,
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
        serviceName: String,
        attempt: Int,
        listener: NsdManager.DiscoveryListener,
    ) {
        manager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (!isActive(listener)) return
                    log(context, "mdns resolve failed name=$serviceName error=$errorCode")
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 3) {
                        mainHandler.postDelayed(
                            {
                                if (isActive(listener)) {
                                    resolve(
                                        context,
                                        manager,
                                        serviceInfo,
                                        serviceName,
                                        attempt + 1,
                                        listener,
                                    )
                                }
                            },
                            300L,
                        )
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (!isActive(listener)) return
                    val port = info.port
                    if (port <= 0 || port > 65_535) {
                        log(context, "mdns resolved invalid port name=$serviceName port=$port")
                        return
                    }
                    val candidate = Target(
                        host = info.host?.hostAddress.orEmpty(),
                        port = port,
                        serviceName = serviceName,
                        expirationTime = parseExpirationTime(info),
                        resolvedAt = System.currentTimeMillis(),
                    )
                    synchronized(lock) {
                        val current = targets[port]
                        if (current == null || isNewer(candidate, current)) {
                            targets[port] = candidate
                            pruneOldTargets()
                        }
                    }
                    WirelessAdbPortStore.save(context, port)
                    log(
                        context,
                        "mdns resolved name=$serviceName port=$port host=${candidate.host} " +
                            "expiration=${candidate.expirationTime}",
                    )
                    log(context, "mdns best target=${bestTarget()?.port ?: -1}")
                }
            },
        )
    }

    private fun restartOnMainThread() {
        val context = appContext ?: return
        val oldListener = activeListener
        activeListener = null
        started = false
        if (oldListener != null) {
            runCatching {
                val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                manager?.stopServiceDiscovery(oldListener)
            }.onFailure {
                log(context, "mdns stop before restart failed: ${it.message}")
            }
        }
        scheduleRestart(context)
    }

    private fun isActive(listener: NsdManager.DiscoveryListener): Boolean =
        activeListener === listener

    private fun scheduleRestart(context: Context) {
        if (restartScheduled) return
        restartScheduled = true
        mainHandler.postDelayed(
            {
                restartScheduled = false
                startOnMainThread()
            },
            RESTART_DELAY_MS,
        )
    }

    private fun bestTarget(): Target? = synchronized(lock) {
        targets.values.maxWithOrNull(
            compareBy<Target> { it.expirationTime ?: Long.MIN_VALUE }
                .thenBy { serviceVersion(it.serviceName) }
                .thenBy { it.resolvedAt },
        )
    }

    private fun isNewer(candidate: Target, current: Target): Boolean {
        val candidateExpiration = candidate.expirationTime
        val currentExpiration = current.expirationTime
        if (candidateExpiration != null && currentExpiration != null &&
            candidateExpiration != currentExpiration
        ) {
            return candidateExpiration > currentExpiration
        }
        if (candidateExpiration != null && currentExpiration == null) return true
        if (candidateExpiration == null && currentExpiration != null) return false

        val candidateVersion = serviceVersion(candidate.serviceName)
        val currentVersion = serviceVersion(current.serviceName)
        if (candidateVersion != currentVersion) return candidateVersion > currentVersion
        return candidate.resolvedAt >= current.resolvedAt
    }

    private fun pruneOldTargets() {
        while (targets.size > MAX_TARGETS) {
            val oldest = targets.values.minByOrNull(Target::resolvedAt) ?: return
            targets.remove(oldest.port)
        }
    }

    private fun serviceVersion(serviceName: String): Int =
        SERVICE_VERSION_REGEX.find(serviceName)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    private fun parseExpirationTime(info: NsdServiceInfo): Long? {
        val raw = info.attributes.entries
            .firstOrNull { it.key.equals("expirationTime", ignoreCase = true) }
            ?.value
            ?.toString(StandardCharsets.UTF_8)
            ?.trim()
            ?: EXPIRATION_REGEX.find(info.toString())?.groupValues?.get(1)?.trim()
            ?: return null
        val unquoted = raw.trim('"', '\'')
        unquoted.toLongOrNull()?.let { value ->
            return if (value < 10_000_000_000L) value * 1_000L else value
        }
        return runCatching {
            ISO_DATE_FORMAT.get()?.parse(unquoted)?.time
        }.getOrNull()
    }

    private fun log(context: Context, message: String) {
        Log.d(TAG, message)
        PairingLog.record(context, message)
    }

    private val SERVICE_VERSION_REGEX = Regex("""\((\d+)\)""")
    private val EXPIRATION_REGEX = Regex("""expirationTime[=:]\s*([^,\s}\]]+)""")
    private val ISO_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
