package com.sameerasw.airsync.findmy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sameerasw.airsync.service.MirrorAttachmentAccessibilityService
import com.sameerasw.airsync.utils.PhoneRinger
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The Mac's reply to "Where's my Mac?". */
data class MacLocationReport(
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val networkName: String?,
    val macName: String?,
    val timestamp: Long,
    val error: String?
)

/**
 * Routes Find My messages, whether they arrived over the nearby WebSocket link or the internet relay.
 * Mac → phone: findRing, findLocate, findLock, findMessage, findPing, findRelayConfig, findRelayWake.
 * Replies to the phone's own requests: findMacLocation, findMacRingState.
 */
object FindMyHandler {
    private const val TAG = "HIBFindMy"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var ringWatch: Job? = null

    private val _macLocation = MutableStateFlow<MacLocationReport?>(null)
    val macLocation = _macLocation.asStateFlow()
    private val _macRinging = MutableStateFlow(false)
    val macRinging = _macRinging.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    fun handle(context: Context, type: String, data: JSONObject, viaRelay: Boolean) {
        val ctx = context.applicationContext
        Log.d(TAG, "Find My $type (${if (viaRelay) "relay" else "nearby"})")
        when (type) {
            "findRing" -> {
                if (data.optString("action") == "stop") {
                    PhoneRinger.stop(ctx)
                } else {
                    PhoneRinger.start(ctx)
                    if (viaRelay) watchRingForRelay(ctx)
                }
            }
            "findLocate" -> FindMyLocation.report(ctx) { payload ->
                reply(ctx, "findLocation", payload, viaRelay)
            }
            "findLock" -> {
                val ok = MirrorAttachmentAccessibilityService.lockScreen()
                reply(ctx, "findLockResult",
                    JSONObject().put("ok", ok).put("method", if (ok) "accessibility" else "none"), viaRelay)
            }
            "findMessage" -> {
                val ok = FindMyMessage.show(ctx, data.optString("text"))
                reply(ctx, "findMessageResult", JSONObject().put("ok", ok), viaRelay)
            }
            "findPing" -> reply(ctx, "findPong", JSONObject().put("id", data.optString("id")), viaRelay = true)
            "findRelayConfig" -> FindMyRelayClient.applyConfig(ctx, data)
            "findRelayWake" -> FindMyRelayClient.wake(ctx, data.optInt("seconds", 90))
            "findMacLocation" -> {
                val lat = if (data.has("lat")) data.optDouble("lat") else null
                val lon = if (data.has("lon")) data.optDouble("lon") else null
                _macLocation.value = MacLocationReport(
                    lat = lat,
                    lon = lon,
                    accuracyM = if (data.has("accuracyM")) data.optDouble("accuracyM") else null,
                    networkName = data.optString("networkName").ifBlank { null },
                    macName = data.optString("macName").ifBlank { null },
                    timestamp = data.optLong("timestamp", System.currentTimeMillis()),
                    error = data.optString("error").ifBlank { null }
                )
                _status.value = null
            }
            "findMacRingState" -> _macRinging.value = data.optBoolean("ringing")
        }
    }

    /** Over the relay the normal ringState report can't reach the Mac, so forward it ourselves. */
    private fun watchRingForRelay(context: Context) {
        ringWatch?.cancel()
        ringWatch = scope.launch {
            sendRelayOrLink(context, "findRingState", JSONObject().put("ringing", true))
            PhoneRinger.isRinging.drop(1).first { !it }
            if (!WebSocketUtil.isWifiConnected()) {
                FindMyRelayClient.publish(context, "findRingState", JSONObject().put("ringing", false))
            }
        }
    }

    private fun sendRelayOrLink(context: Context, type: String, data: JSONObject) {
        if (!WebSocketUtil.isWifiConnected()) FindMyRelayClient.publish(context, type, data)
    }

    private fun reply(context: Context, type: String, data: JSONObject, viaRelay: Boolean) {
        if (type != "findPong" && WebSocketUtil.isWifiConnected()) {
            val message = JSONObject().put("type", type).put("data", data).toString()
            if (WebSocketUtil.sendMessage(message)) return
        }
        if (viaRelay || type == "findPong" || !WebSocketUtil.isWifiConnected()) {
            FindMyRelayClient.publish(context, type, data)
        }
    }

    // region Phone → Mac

    /** Sends a Find-my-Mac request over the nearby link if connected, else over the relay. */
    fun sendToMac(context: Context, type: String, data: JSONObject = JSONObject()) {
        val ctx = context.applicationContext
        if (WebSocketUtil.isWifiConnected()) {
            val message = JSONObject().put("type", type).put("data", data).toString()
            if (WebSocketUtil.sendMessage(message)) {
                _status.value = "Sent to your Mac (nearby)"
                return
            }
        }
        if (!FindMyRelayClient.isAvailable(ctx)) {
            _status.value = "Not paired yet — connect to your Mac once to set up the internet relay"
            return
        }
        _status.value = "Sending over the internet…"
        FindMyRelayClient.publish(ctx, type, data) { ok ->
            main.post {
                _status.value = if (ok) "Sent over the internet — waiting for your Mac" else "Couldn't reach the relay. Check the internet connection."
            }
        }
    }

    fun ringMac(context: Context, start: Boolean) {
        _macRinging.value = start
        sendToMac(context, "findRingMac", JSONObject().put("action", if (start) "start" else "stop"))
    }

    fun locateMac(context: Context) {
        sendToMac(context, "findLocateMac")
    }

    // endregion
}
