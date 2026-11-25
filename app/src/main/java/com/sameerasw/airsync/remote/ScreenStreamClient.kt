package com.sameerasw.airsync.remote

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

/**
 * Phone Remote (SPACE.md §15.3): one `/screen` session with one computer.
 *
 * Dials the computer's current address at once and its remembered Tailscale addresses 1 s later (first to open
 * wins), sends `screenOpen` first, then a `screenPing` every second and `screenStats` every two. Text frames are
 * encrypted like `/socket`; binary frames carry the raw AES-GCM output of a 22-byte header + payload.
 * Silence (no pong for [SILENCE_MS]) closes the session so the caller can say the computer can't be reached.
 */
class ScreenStreamClient(
    private val client: OkHttpClient,
    private val dials: List<TailscaleAddrs.Dial>,
    private val port: Int,
    private val key: SecretKey,
    private val open: OpenParams,
    private val listener: Listener
) {
    data class OpenParams(
        val display: String,
        val maxW: Int,
        val maxH: Int,
        val fps: Int,
        val codec: String,
        val view: ViewRect
    )

    interface Listener {
        fun onOpened(opened: RemoteJson.Opened, route: String)
        fun onFrame(frame: ScreenFrame)
        fun onPaused(reason: String)
        fun onResumed()
        /** [code]: the close code, or [CODE_UNREACHABLE] / [CODE_SILENT] when the socket failed or went quiet. */
        fun onClosed(code: Int, reason: String)
        fun onRtt(rttMs: Int)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val won = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val failures = AtomicInteger(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastPongAt = 0L
    @Volatile var route: String = ROUTE_LAN
        private set
    private var tickJob: Job? = null
    private val rendered = AtomicInteger(0)
    @Volatile private var lastRtt = 0
    @Volatile var decodeMsProvider: () -> Int = { 0 }

    fun start() {
        if (dials.isEmpty()) { finish(CODE_UNREACHABLE, "No address"); return }
        dials.forEach { d ->
            scope.launch {
                if (d.delayMs > 0) delay(d.delayMs)
                if (won.get() || closed.get()) return@launch
                dial(d.ip)
            }
        }
        scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!won.get()) finish(CODE_UNREACHABLE, "Timed out")
        }
    }

    /** Frames that reached the screen (for the fps in `screenStats` and the readout). */
    fun noteRendered() { rendered.incrementAndGet() }

    fun sendJson(json: String): Boolean {
        val ws = socket ?: return false
        return runCatching { ws.send(RemoteCrypto.sealText(json, key)) }.getOrDefault(false)
    }

    fun stop() {
        if (closed.getAndSet(true)) return
        runCatching { socket?.close(1000, "Closed") }
        socket = null
        scope.cancel()
    }

    private fun dial(ip: String) {
        val host = if (ip.contains(":")) "[${ip.substringBefore("%").removeSurrounding("[", "]")}]" else ip
        val request = runCatching { Request.Builder().url("ws://$host:$port/screen").build() }.getOrNull()
        if (request == null) { onDialFailed(); return }
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get() || !won.compareAndSet(false, true)) {
                    webSocket.close(1000, "Already connected elsewhere")
                    return
                }
                socket = webSocket
                route = if (TailscaleAddrs.isTailscale(ip)) ROUTE_TAILSCALE else ROUTE_LAN
                val first = RemoteJson.screenOpen(
                    open.display, open.maxW, open.maxH, open.fps, open.codec, open.view,
                    if (route == ROUTE_TAILSCALE) ROUTE_TAILSCALE else null
                )
                webSocket.send(RemoteCrypto.sealText(first, key))
                lastPongAt = SystemClock.elapsedRealtime()
                startTicks(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                val plain = RemoteCrypto.openText(text, key) ?: return
                when (RemoteJson.type(plain)) {
                    "screenOpened" -> RemoteJson.opened(plain)?.let { listener.onOpened(it, route) }
                    "screenPong" -> RemoteJson.pongT(plain)?.let { t ->
                        val now = SystemClock.elapsedRealtime()
                        lastPongAt = now
                        val rtt = (now - t).toInt().coerceAtLeast(0)
                        lastRtt = if (lastRtt == 0) rtt else (lastRtt * 3 + rtt) / 4
                        listener.onRtt(lastRtt)
                    }
                    "screenPaused" -> listener.onPaused(RemoteJson.pausedReason(plain))
                    "screenResumed" -> listener.onResumed()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (socket !== webSocket) return
                val plain = RemoteCrypto.open(bytes.toByteArray(), key) ?: return
                lastPongAt = SystemClock.elapsedRealtime()
                ScreenFrame.parse(plain)?.let { listener.onFrame(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (socket === webSocket) finish(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) finish(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) finish(CODE_UNREACHABLE, t.javaClass.simpleName)
                else if (!won.get()) onDialFailed()
            }
        })
    }

    private fun onDialFailed() {
        if (failures.incrementAndGet() >= dials.size && !won.get()) finish(CODE_UNREACHABLE, "Can't reach")
    }

    private fun startTicks(ws: WebSocket) {
        tickJob?.cancel()
        tickJob = scope.launch {
            var n = 0
            var lastStatsAt = SystemClock.elapsedRealtime()
            while (isActive && socket === ws) {
                delay(PING_MS)
                val now = SystemClock.elapsedRealtime()
                if (now - lastPongAt > SILENCE_MS) {
                    Log.w(TAG, "No answer for ${SILENCE_MS / 1000} s; closing")
                    runCatching { ws.close(1000, "Silent") }
                    finish(CODE_SILENT, "Silent")
                    return@launch
                }
                sendJson(RemoteJson.screenPing(now))
                if (++n % 2 == 0) {
                    val secs = ((now - lastStatsAt) / 1000.0).coerceAtLeast(0.5)
                    lastStatsAt = now
                    val fps = (rendered.getAndSet(0) / secs).toInt()
                    sendJson(RemoteJson.screenStats(fps, lastRtt, decodeMsProvider()))
                    listener.onRtt(lastRtt)
                    lastFps = fps
                }
            }
        }
    }

    /** Pictures per second over the last stats period. */
    @Volatile var lastFps: Int = 0
        private set

    private fun finish(code: Int, reason: String) {
        if (closed.getAndSet(true)) return
        socket = null
        Log.d(TAG, "screen closed ($code)")
        listener.onClosed(code, reason)
        scope.cancel()
    }

    companion object {
        private const val TAG = "RemoteStream"
        const val ROUTE_LAN = "lan"
        const val ROUTE_TAILSCALE = "tailscale"
        const val PING_MS = 1_000L
        const val SILENCE_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000L

        const val CODE_BAD_KEY = 4003
        const val CODE_NOT_PERMITTED = 4010
        const val CODE_STOPPED = 4012
        const val CODE_BUSY = 4013
        const val CODE_UNREACHABLE = -1
        const val CODE_SILENT = -2

        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
