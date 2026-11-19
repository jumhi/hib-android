package com.sameerasw.airsync.findmy

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * H.I.B Find My internet relay (ntfy). While the phone is NOT connected to the Mac over WebSocket,
 * keeps one long-lived streaming GET open (inside AirSyncService's foreground service) with
 * exponential backoff up to 5 minutes. A JobScheduler job polls every 15 minutes as a fallback.
 */
object FindMyRelayClient {
    private const val TAG = "HIBFindMyRelay"
    const val DEFAULT_SERVER = "https://ntfy.sh"
    private const val PREF_ENABLED = "relayEnabled"
    private const val PREF_SERVER = "relayServer"
    private const val POLL_JOB_ID = 0x4B1F

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var streamJob: Job? = null
    @Volatile private var currentCall: Call? = null
    @Volatile private var wakeUntil = 0L

    private val _streaming = MutableStateFlow(false)
    val streaming = _streaming.asStateFlow()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS) // ntfy sends keepalives every ~45 s
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val connectionListener: (Boolean) -> Unit = { _ -> evaluate() }

    // region settings

    fun isEnabled(context: Context) = FindMyCrypto.prefs(context).getBoolean(PREF_ENABLED, true)

    fun server(context: Context): String {
        val raw = FindMyCrypto.prefs(context).getString(PREF_SERVER, null)?.trim().orEmpty()
        val value = raw.ifEmpty { DEFAULT_SERVER }.trimEnd('/')
        return if (value.startsWith("https://") || value.startsWith("http://")) value else DEFAULT_SERVER
    }

    /** `findRelayConfig` from the Mac (sent over the nearby link). */
    fun applyConfig(context: Context, data: JSONObject) {
        FindMyCrypto.prefs(context).edit()
            .putBoolean(PREF_ENABLED, data.optBoolean("enabled", true))
            .putString(PREF_SERVER, data.optString("server", DEFAULT_SERVER))
            .apply()
        restart()
    }

    // endregion

    /** Called from AirSyncService.onCreate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        WebSocketUtil.unregisterConnectionStatusListener(connectionListener)
        WebSocketUtil.registerConnectionStatusListener(connectionListener)
        schedulePollJob(context)
        // Keys may have changed (re-pair); refresh them, then decide.
        scope.launch {
            FindMyCrypto.refresh(context)
            evaluate()
        }
    }

    /** Called from AirSyncService.onDestroy. */
    fun detach() {
        WebSocketUtil.unregisterConnectionStatusListener(connectionListener)
        stopStream()
    }

    /** `findRelayWake` from the Mac: listen on the relay for a while even though we're connected. */
    fun wake(context: Context, seconds: Int) {
        if (appContext == null) appContext = context.applicationContext
        wakeUntil = System.currentTimeMillis() + seconds.coerceIn(10, 300) * 1000L
        evaluate()
        scope.launch {
            delay(seconds.coerceIn(10, 300) * 1000L + 500)
            evaluate()
        }
    }

    private fun restart() {
        stopStream()
        evaluate()
    }

    private fun shouldStream(context: Context): Boolean {
        if (!isEnabled(context)) return false
        if (FindMyCrypto.saved(context) == null) return false
        val connectedNearby = WebSocketUtil.isWifiConnected()
        return !connectedNearby || System.currentTimeMillis() < wakeUntil
    }

    @Synchronized
    private fun evaluate() {
        val context = appContext ?: return
        if (shouldStream(context)) {
            if (streamJob?.isActive != true) startStream(context)
        } else {
            if (WebSocketUtil.isWifiConnected()) {
                // Connected again: the pairing key might be new. Keep derived keys current.
                scope.launch { FindMyCrypto.refresh(context) }
            }
            stopStream()
        }
    }

    private fun startStream(context: Context) {
        streamJob = scope.launch {
            var backoffMs = 2_000L
            while (isActive && shouldStream(context)) {
                val keys = FindMyCrypto.saved(context) ?: break
                val url = "${server(context)}/${keys.topic}/json?since=5m"
                val call = http.newCall(Request.Builder().url(url).get().build())
                currentCall = call
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                        _streaming.value = true
                        backoffMs = 2_000L
                        val source = response.body?.source() ?: return@use
                        while (isActive) {
                            val line = source.readUtf8Line() ?: break
                            handleLine(context, line, keys)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) Log.d(TAG, "Relay stream ended: ${e.message}")
                } finally {
                    _streaming.value = false
                    currentCall = null
                }
                if (!isActive || !shouldStream(context)) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(300_000L)
            }
            _streaming.value = false
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        currentCall?.cancel()
        currentCall = null
        _streaming.value = false
    }

    /** Parses one ntfy JSON line; decrypts, validates and dispatches it. */
    fun handleLine(context: Context, line: String, keys: FindMyKeys) {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (obj.optString("event") != "message") return
        val envelope = FindMyCrypto.open(obj.optString("message"), keys) ?: return
        if (envelope.from != "mac") return
        if (!FindMyCrypto.accept(context, envelope.nonce)) return
        FindMyHandler.handle(context, envelope.type, envelope.data, viaRelay = true)
    }

    /** Publishes a sealed message. Safe to call from any thread. */
    fun publish(context: Context, type: String, data: JSONObject, onDone: ((Boolean) -> Unit)? = null) {
        val appCtx = context.applicationContext
        scope.launch {
            val ok = runCatching {
                if (!isEnabled(appCtx)) return@runCatching false
                val keys = FindMyCrypto.saved(appCtx) ?: FindMyCrypto.refresh(appCtx) ?: return@runCatching false
                val body = FindMyCrypto.seal(type, data, keys) ?: return@runCatching false
                val request = Request.Builder()
                    .url("${server(appCtx)}/${keys.topic}")
                    .post(body.toRequestBody("text/plain".toMediaType()))
                    .build()
                http.newCall(request).execute().use { it.isSuccessful }
            }.getOrElse {
                Log.w(TAG, "Publish failed: ${it.message}")
                false
            }
            onDone?.invoke(ok)
        }
    }

    fun isAvailable(context: Context) = isEnabled(context) && FindMyCrypto.saved(context) != null

    fun schedulePollJob(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(POLL_JOB_ID) != null) return
            val job = JobInfo.Builder(POLL_JOB_ID, ComponentName(context, FindMyPollJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(false)
                .build()
            scheduler.schedule(job)
        }.onFailure { Log.w(TAG, "Couldn't schedule relay poll: ${it.message}") }
    }

    /** One-shot poll (`poll=1`) used by the periodic job when the stream isn't running. */
    fun pollOnce(context: Context): Boolean {
        if (_streaming.value) return true
        if (!isEnabled(context)) return true
        val keys = FindMyCrypto.saved(context) ?: return true
        return runCatching {
            val request = Request.Builder().url("${server(context)}/${keys.topic}/json?poll=1&since=5m").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                response.body?.string()?.lineSequence()?.forEach { line ->
                    if (line.isNotBlank()) handleLine(context, line, keys)
                }
                true
            }
        }.getOrDefault(false)
    }
}
