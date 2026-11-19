package com.sameerasw.airsync.scenes

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.utils.SyncManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Reports phone-side Scene triggers to the Mac while connected:
 *  - headphones / Bluetooth audio connected or disconnected → phoneEvent {event:"headset", connected, name}
 *  - default network changes → phoneEvent {event:"wifi", transport:"wifi"|"cellular"|"none", ssid?, ssidAvailable}
 *  - charger plugged/unplugged → forces an immediate `status` sync (battery/charging already travel in `status`).
 * The SSID needs Location permission; without it only "wifi"/"cellular" is reported.
 */
object SceneEventMonitor {
    private const val TAG = "HIBSceneEvents"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var started = false
    private var registered = false
    private var appContext: Context? = null

    private var lastHeadset: Boolean? = null
    private var lastNetworkKey: String? = null

    private var audioCallback: AudioDeviceCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var powerReceiver: BroadcastReceiver? = null

    private val headsetTypes: Set<Int> by lazy {
        val s = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) s.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        s
    }

    /** Idempotent: follows the WebSocket connection and (un)registers listeners with it. */
    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        appContext = context.applicationContext
        scope.launch {
            WebSocketUtil.connectionState.collect { connected ->
                val ctx = appContext ?: return@collect
                if (connected) register(ctx) else unregister(ctx)
            }
        }
    }

    private fun register(context: Context) {
        if (registered) return
        registered = true
        lastHeadset = null
        lastNetworkKey = null
        try { registerAudio(context) } catch (e: Exception) { Log.e(TAG, "audio callback: ${e.message}") }
        try { registerNetwork(context) } catch (e: Exception) { Log.e(TAG, "network callback: ${e.message}") }
        try { registerPower(context) } catch (e: Exception) { Log.e(TAG, "power receiver: ${e.message}") }
        Log.d(TAG, "Scene event reporting started")
    }

    private fun unregister(context: Context) {
        if (!registered) return
        registered = false
        try {
            audioCallback?.let { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(it) }
        } catch (e: Exception) {}
        try {
            networkCallback?.let { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
        try { powerReceiver?.let { context.unregisterReceiver(it) } } catch (e: Exception) {}
        audioCallback = null
        networkCallback = null
        powerReceiver = null
        ScenesRemote.reset()
        Log.d(TAG, "Scene event reporting stopped")
    }

    // MARK: - Headset

    private fun registerAudio(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = reportHeadset(am)
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = reportHeadset(am)
        }
        audioCallback = cb
        am.registerAudioDeviceCallback(cb, main)
        reportHeadset(am)
    }

    private fun reportHeadset(am: AudioManager) {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in headsetTypes }
        val connected = devices.isNotEmpty()
        if (connected == lastHeadset) return
        lastHeadset = connected
        val name = devices.firstOrNull()?.productName?.toString().orEmpty()
        ScenesRemote.sendJson(
            "phoneEvent",
            JSONObject().put("event", "headset").put("connected", connected).put("name", name)
        )
    }

    // MARK: - Network

    private fun hasLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun registerNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = reportNetwork(context, caps)
                override fun onLost(network: Network) = reportNetwork(context, null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = reportNetwork(context, caps)
                override fun onLost(network: Network) = reportNetwork(context, null)
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb, main)
    }

    @Suppress("DEPRECATION")
    private fun reportNetwork(context: Context, caps: NetworkCapabilities?) {
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "none"
        }
        var ssid: String? = null
        if (transport == "wifi" && hasLocation(context)) {
            val info = (caps?.transportInfo as? WifiInfo)
                ?: (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('"') && it != "<unknown ssid>" }
        }
        val key = "$transport|${ssid.orEmpty()}"
        if (key == lastNetworkKey) return
        lastNetworkKey = key
        val data = JSONObject()
            .put("event", "wifi")
            .put("transport", transport)
            .put("ssidAvailable", ssid != null)
        if (ssid != null) data.put("ssid", ssid)
        ScenesRemote.sendJson("phoneEvent", data)
    }

    // MARK: - Power

    private fun registerPower(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // Battery state lags the broadcast slightly; push a fresh `status` shortly after.
                main.postDelayed({ SyncManager.checkAndSyncDeviceStatus(ctx.applicationContext, forceSync = true) }, 1200L)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        powerReceiver = receiver
    }
}
