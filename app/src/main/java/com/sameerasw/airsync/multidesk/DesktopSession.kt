package com.sameerasw.airsync.multidesk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.CryptoUtil
import com.sameerasw.airsync.utils.DeviceInfoUtil
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.WallpaperUtil
import com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

/** Anything the phone can send to: the legacy primary connection or an extra computer session. */
interface DesktopLink {
    val id: String
    val name: String
    val type: String?
    val model: String?
    val platform: String get() = PairedDesktops.platformOf(type, model)
    fun isReady(): Boolean
    fun send(json: String): Boolean
}

/**
 * H.I.B multi-desktop — one extra computer the phone stays connected to alongside the primary one
 * (which still lives in WebSocketUtil, untouched). Own socket, own pairing key, own handshake and its
 * own copy of the primary's reconnect strategy: proactive retry with ×1.5 backoff capped at 10 s
 * (30 s after 10 failures), only on Wi-Fi/VPN/Ethernet, plus an immediate retry when discovery
 * (mDNS/UDP) sees the computer's name again.
 */
class DesktopSession(
    private val appContext: Context,
    initial: PairedDesktop,
    private val client: OkHttpClient
) : DesktopLink {

    enum class State { IDLE, CONNECTING, CONNECTED, AUTH_FAILED, STOPPED }

    @Volatile var desktop: PairedDesktop = initial
        private set
    @Volatile var state: State = State.IDLE
        private set
    @Volatile var connectedIp: String? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var macType: String? = initial.type
    @Volatile private var macModel: String? = initial.model

    override val id: String get() = desktop.name
    override val name: String get() = desktop.name
    override val type: String? get() = macType
    override val model: String? get() = macModel

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var discoveryJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var key: SecretKey? = runCatching { CryptoUtil.decodeKey(initial.key) }.getOrNull()
    private val handshakeDone = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pendingAttempt: CompletableDeferred<Boolean>? = null
    @Volatile private var extraIps: List<String> = emptyList()

    override fun isReady(): Boolean = state == State.CONNECTED && socket != null

    override fun send(json: String): Boolean {
        val ws = socket ?: return false
        if (state != State.CONNECTED && state != State.CONNECTING) return false
        val k = key ?: return false
        val encrypted = CryptoUtil.encryptMessage(json, k) ?: return false
        return ws.send(encrypted)
    }

    /** Re-pairing (new key/IPs) or a rename from the store. */
    fun update(d: PairedDesktop) {
        val keyChanged = d.key != desktop.key
        desktop = d
        if (d.type != null) macType = d.type
        if (d.model != null) macModel = d.model
        if (keyChanged) {
            key = runCatching { CryptoUtil.decodeKey(d.key) }.getOrNull()
            if (state == State.AUTH_FAILED) state = State.IDLE
            if (state == State.CONNECTED) closeSocket(1000, "Re-paired")
        }
        poke()
    }

    fun start() {
        if (loopJob?.isActive == true) { poke(); return }
        if (state == State.STOPPED || state == State.AUTH_FAILED) state = State.IDLE
        loopJob = scope.launch { reconnectLoop() }
        discoveryJob = scope.launch { watchDiscovery() }
    }

    /** Ask for an immediate attempt (wake-up request, user tap, network change). */
    fun poke(ips: List<String> = emptyList()) {
        if (ips.isNotEmpty()) extraIps = ips
        wake.trySend(Unit)
    }

    fun stop(sendBye: Boolean) {
        loopJob?.cancel(); loopJob = null
        discoveryJob?.cancel(); discoveryJob = null
        if (sendBye && isReady()) send("{\"type\":\"userDisconnected\",\"data\":{}}")
        closeSocket(1000, if (sendBye) "Manual disconnection" else "Disconnected")
        state = State.STOPPED
        DesktopHub.onSessionChanged(this)
    }

    fun dispose() {
        stop(sendBye = false)
        scope.cancel()
    }

    private fun closeSocket(code: Int, reason: String) {
        val ws = socket
        socket = null
        handshakeDone.set(false)
        connectedIp = null
        runCatching { ws?.close(code, reason) }
    }

    // MARK: - reconnect strategy (mirrors WebSocketUtil.tryStartAutoReconnect)

    private suspend fun reconnectLoop() {
        val ds = DataStoreManager.getInstance(appContext)
        var backoffMs = 2_000L
        var failedStreak = 0
        while (scope.isActive) {
            if (state == State.AUTH_FAILED) {
                // Wrong key: wait until the user re-pairs this computer (update() pokes us).
                wake.receive()
                continue
            }
            if (state == State.CONNECTED) {
                backoffMs = 2_000L; failedStreak = 0
                wake.receive() // woken by onClosed/onFailure
                continue
            }
            val autoEnabled = runCatching { ds.getAutoReconnectEnabled().first() }.getOrDefault(true)
            val paused = runCatching { ds.isAppPaused().first() }.getOrDefault(false)
            val expand = runCatching { ds.getExpandNetworkingEnabled().first() }.getOrDefault(false)
            if (!paused && (autoEnabled || failedStreak == 0) && (hasLocalNetwork() || expand)) {
                val ok = attempt()
                if (ok) continue
                failedStreak++
            }
            withTimeoutOrNull(backoffMs) { wake.receive() }
            val cap = if (failedStreak > 10) 30_000L else 10_000L
            backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(cap)
        }
    }

    private suspend fun watchDiscovery() {
        DiscoveryOrchestrator.discoveredDevices.collect { list ->
            if (state == State.CONNECTED || state == State.CONNECTING || state == State.AUTH_FAILED) return@collect
            val match = list.firstOrNull { PairedDesktops.sameName(it.name, desktop.name) } ?: return@collect
            Log.d(TAG, "Discovery saw ${desktop.name}; reconnecting")
            poke(match.ips.toList())
        }
    }

    private fun hasLocalNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Tries every known IP in parallel; the first socket to open wins. Suspends until macInfo or failure. */
    private suspend fun attempt(): Boolean {
        val ips = PairedDesktops.mergeIps(extraIps, desktop.ips)
        extraIps = emptyList()
        if (ips.isEmpty() || key == null) return false
        state = State.CONNECTING
        DesktopHub.onSessionChanged(this)
        val result = CompletableDeferred<Boolean>()
        pendingAttempt = result
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val won = AtomicBoolean(false)

        ips.forEach { ip ->
            val host = if (ip.contains(":")) "[${ip.substringBefore("%").removeSurrounding("[", "]")}]" else ip
            val request = runCatching { Request.Builder().url("ws://$host:${desktop.port}/socket").build() }.getOrNull()
            if (request == null) {
                if (failures.incrementAndGet() >= ips.size) result.complete(false)
                return@forEach
            }
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!won.compareAndSet(false, true) || result.isCompleted) {
                        webSocket.close(1000, "Already connected elsewhere")
                        return
                    }
                    socket = webSocket
                    connectedIp = ip
                    handshakeDone.set(false)
                    scope.launch { sendDeviceInfo(webSocket, ip, includeWallpaper = false) }
                    scope.launch {
                        delay(HANDSHAKE_TIMEOUT_MS)
                        if (socket === webSocket && !handshakeDone.get()) {
                            Log.w(TAG, "Handshake with ${desktop.name} timed out")
                            lastError = "No answer from ${desktop.name}"
                            webSocket.close(4001, "Handshake timeout")
                            onSocketGone(webSocket)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket !== webSocket) return
                    val k = key ?: return
                    val plain = CryptoUtil.decryptMessage(text, k)
                    if (plain == null) {
                        Log.e(TAG, "Pairing key mismatch for ${desktop.name}")
                        lastError = "Pairing expired — scan ${desktop.name}'s QR code again"
                        state = State.AUTH_FAILED
                        webSocket.close(4003, "Pairing key mismatch")
                        socket = null
                        result.complete(false)
                        DesktopHub.onSessionChanged(this@DesktopSession)
                        return
                    }
                    if (!handshakeDone.get()) {
                        val isMacInfo = DesktopRouting.messageType(plain) == "macInfo"
                        if (!isMacInfo) return
                        handshakeDone.set(true)
                        val data = runCatching { JSONObject(plain).optJSONObject("data") }.getOrNull() ?: JSONObject()
                        data.optString("type", data.optString("categoryType")).ifBlank { null }?.let { macType = it }
                        data.optString("model", data.optString("modelIdentifier")).ifBlank { null }?.let { macModel = it }
                        state = State.CONNECTED
                        lastError = null
                        result.complete(true)
                        DesktopHub.onSessionConnected(this@DesktopSession, data)
                        scope.launch {
                            delay(1500)
                            if (socket === webSocket) sendDeviceInfo(webSocket, ip, includeWallpaper = true)
                        }
                        return
                    }
                    DesktopHub.dispatchInbound(appContext, this@DesktopSession, plain)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (socket === webSocket) {
                        if (reason.isNotBlank() && code != 1000) lastError = reason
                        onSocketGone(webSocket)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) {
                        lastError = t.message
                        onSocketGone(webSocket)
                    } else if (!won.get() && failures.incrementAndGet() >= ips.size) {
                        lastError = "Can't reach ${desktop.name}"
                        result.complete(false)
                    }
                }
            })
        }

        val ok = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) { result.await() } ?: false
        pendingAttempt = null
        if (!ok && state == State.CONNECTING) {
            closeSocket(1000, "Attempt timed out")
            state = State.IDLE
            DesktopHub.onSessionChanged(this)
        }
        return ok
    }

    private fun onSocketGone(webSocket: WebSocket) {
        if (socket !== webSocket && socket != null) return
        socket = null
        handshakeDone.set(false)
        connectedIp = null
        pendingAttempt?.complete(false)
        if (state != State.AUTH_FAILED && state != State.STOPPED) state = State.IDLE
        DesktopHub.onSessionChanged(this)
        wake.trySend(Unit)
    }

    private suspend fun sendDeviceInfo(webSocket: WebSocket, targetIp: String, includeWallpaper: Boolean) {
        try {
            val ds = DataStoreManager.getInstance(appContext)
            val deviceName = ds.getDeviceName().first().ifBlank { DeviceInfoUtil.getDeviceName(appContext) }
            val version = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull() ?: "4.1.0"
            val adbPorts = runCatching {
                com.sameerasw.airsync.AdbDiscoveryHolder.getDiscoveredServices().map { it.port.toString() }
            }.getOrDefault(emptyList())
            val wallpaper = if (includeWallpaper) runCatching { WallpaperUtil.getWallpaperAsBase64(appContext) }.getOrNull() else null
            val json = JsonUtil.createDeviceInfoJson(
                id = DeviceInfoUtil.getDeviceId(appContext),
                name = deviceName,
                ipAddress = DeviceInfoUtil.getWifiIpAddress(appContext) ?: "Unknown",
                port = desktop.port,
                version = version,
                wallpaperBase64 = wallpaper,
                adbPorts = adbPorts,
                bleAuthToken = com.sameerasw.airsync.data.ble.BleTransportBridge.deriveAuthToken(desktop.key),
                targetIpAddress = targetIp
            )
            val k = key ?: return
            CryptoUtil.encryptMessage(json, k)?.let { webSocket.send(it) }
        } catch (e: Exception) {
            Log.w(TAG, "device info to ${desktop.name} failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DesktopSession"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val ATTEMPT_TIMEOUT_MS = 15_000L

        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .build()
    }
}
