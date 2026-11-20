@file:Suppress("DEPRECATION")
package com.sameerasw.airsync.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Discovers ADB wireless services on the network using mDNS (NSD), like `adb mdns services`.
 *
 * [serviceType] is `_adb-tls-connect._tcp` (Wireless debugging is on; the port `adb connect` uses) or
 * `_adb-tls-pairing._tcp` (a "Pair device" screen is open on a phone). NSD also sees other phones on the
 * network; [com.sameerasw.airsync.multidesk.AdbOwnService] picks this phone's own service.
 *
 * Resolves run one at a time (older NsdManager versions fail concurrent resolves with
 * FAILURE_ALREADY_ACTIVE), a re-announced service is resolved again so a new port replaces the old one,
 * and [onChange] runs whenever the list changes.
 */
class AdbMdnsDiscovery(
    context: Context,
    val serviceType: String = CONNECT_SERVICE,
    private val onChange: (() -> Unit)? = null
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val TAG = "AdbMdnsDiscovery"
    private val isPairing = serviceType.startsWith(PAIRING_SERVICE.trimEnd('.'))
    private val lock = Any()
    private val discoveredServices = mutableListOf<AdbServiceInfo>()
    /** Names NSD currently reports as present (found and not lost). */
    private val present = HashSet<String>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var generation = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var isDiscovering = false
    private val handler = Handler(Looper.getMainLooper())

    data class AdbServiceInfo(
        val serviceName: String,
        val hostAddress: String,
        val port: Int,
        /** Every address the service resolved to (API 34+ can report several). */
        val hostAddresses: List<String> = listOf(hostAddress).filter { it != "unknown" }
    )

    private fun label(name: String) = if (isPairing) "pairing service" else name

    private fun createDiscoveryListener(gen: Int): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "mDNS Discovery Started for service type: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (gen != generation) return
                    present += serviceInfo.serviceName
                }
                Log.d(TAG, "Service found: ${label(serviceInfo.serviceName)}")
                // Always (re)resolve: a service announced again under the same name may have a new port.
                enqueueResolve(serviceInfo, gen)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${label(serviceInfo.serviceName)}")
                val changed = synchronized(lock) {
                    if (gen != generation) return
                    present -= serviceInfo.serviceName
                    resolveQueue.removeAll { it.serviceName == serviceInfo.serviceName }
                    discoveredServices.removeAll { it.serviceName == serviceInfo.serviceName }
                }
                if (changed) notifyChange()
            }

            override fun onDiscoveryStopped(regType: String) {
                Log.d(TAG, "Discovery stopped for service type: $regType")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed for $regType: Error code $errorCode")
                synchronized(lock) {
                    if (gen == generation) {
                        isDiscovering = false
                        discoveryListener = null
                    }
                }
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed for $regType: Error code $errorCode")
            }
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo, gen: Int) {
        synchronized(lock) {
            if (gen != generation) return
            resolveQueue.removeAll { it.serviceName == info.serviceName }
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext(gen)
    }

    private fun resolveNext(gen: Int) {
        val next = synchronized(lock) {
            if (gen != generation) return
            val n = resolveQueue.removeFirstOrNull()
            if (n == null) resolving = false
            n
        } ?: return
        resolve(next, gen, attempt = 0)
    }

    private fun resolve(info: NsdServiceInfo, gen: Int, attempt: Int) {
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${label(info.serviceName)}: Error code $errorCode")
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 3) {
                        handler.postDelayed({ resolve(info, gen, attempt + 1) }, 250L * (attempt + 1))
                    } else {
                        resolveNext(gen)
                    }
                }

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    onResolved(info.serviceName, resolvedInfo, gen)
                    resolveNext(gen)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Resolve threw: ${e.javaClass.simpleName}")
            resolveNext(gen)
        }
    }

    /** [name] is the name NSD found (some versions escape the resolved one differently). */
    private fun onResolved(name: String, resolvedInfo: NsdServiceInfo, gen: Int) {
        val hosts = buildList {
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching { resolvedInfo.hostAddresses }.getOrNull()?.forEach { a -> a.hostAddress?.let { add(it) } }
            }
            resolvedInfo.host?.hostAddress?.let { if (it !in this) add(it) }
        }
        val port = resolvedInfo.port
        val info = AdbServiceInfo(
            serviceName = name,
            hostAddress = hosts.firstOrNull() ?: "unknown",
            port = port,
            hostAddresses = hosts
        )
        val changed = synchronized(lock) {
            if (gen != generation || name !in present) return
            val old = discoveredServices.indexOfFirst { it.serviceName == name }
            when {
                old < 0 -> { discoveredServices.add(info); true }
                discoveredServices[old] != info -> {
                    // Newest last: whoever picks "the newest" sees the re-announced service.
                    discoveredServices.removeAt(old)
                    discoveredServices.add(info)
                    true
                }
                else -> false
            }
        }
        if (isPairing) Log.i(TAG, "ADB pairing service on port $port")
        else Log.i(TAG, "ADB Device Found: ${info.hostAddress}:$port ($name)")
        if (changed) notifyChange()
    }

    private fun notifyChange() {
        try {
            onChange?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "onChange failed: ${e.message}")
        }
    }

    /**
     * Start mDNS discovery for [serviceType].
     */
    fun startDiscovery() {
        val listener: NsdManager.DiscoveryListener
        synchronized(lock) {
            if (isDiscovering) {
                Log.w(TAG, "Discovery already in progress")
                return
            }
            generation++
            discoveredServices.clear()
            present.clear()
            resolveQueue.clear()
            resolving = false
            listener = createDiscoveryListener(generation)
            discoveryListener = listener
            isDiscovering = true
        }
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            Log.d(TAG, "Started ADB wireless discovery ($serviceType)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            synchronized(lock) {
                isDiscovering = false
                discoveryListener = null
            }
        }
    }

    /**
     * Stop mDNS discovery and forget what it found.
     */
    fun stopDiscovery() {
        val listener: NsdManager.DiscoveryListener
        val hadServices: Boolean
        synchronized(lock) {
            listener = discoveryListener ?: return
            discoveryListener = null
            isDiscovering = false
            generation++
            hadServices = discoveredServices.isNotEmpty()
            discoveredServices.clear()
            present.clear()
            resolveQueue.clear()
            resolving = false
        }
        try {
            nsdManager.stopServiceDiscovery(listener)
            Log.d(TAG, "Stopped ADB wireless discovery ($serviceType)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop discovery", e)
        }
        if (hadServices) notifyChange()
    }

    fun isRunning(): Boolean = isDiscovering

    /**
     * Get the list of discovered ADB services (oldest first).
     */
    fun getDiscoveredServices(): List<AdbServiceInfo> = synchronized(lock) { discoveredServices.toList() }

    /**
     * Log all discovered ADB ports to logcat
     */
    fun logDiscoveredPorts() {
        val list = getDiscoveredServices()
        if (list.isEmpty()) {
            Log.i(TAG, "No ADB wireless services discovered")
        } else {
            Log.i(TAG, "=== Discovered ADB Wireless Services ===")
            list.forEach { service ->
                Log.i(
                    TAG,
                    "Service: ${label(service.serviceName)} | Host: ${service.hostAddress} | Port: ${service.port}"
                )
            }
            Log.i(TAG, "=========================================")
        }
    }

    companion object {
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp."
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
    }
}
