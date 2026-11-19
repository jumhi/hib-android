package com.sameerasw.airsync.findmy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gets the phone's location for Find My: a fresh fix (LocationManager.getCurrentLocation, API 30+)
 * with a 20 s timeout, falling back to the best last-known location.
 */
object FindMyLocation {
    private const val TIMEOUT_MS = 20_000L
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun hasFine(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Builds the `findLocation` payload and passes it to [onResult] (on a background thread). */
    @SuppressLint("MissingPermission")
    fun report(context: Context, onResult: (JSONObject) -> Unit) {
        val ctx = context.applicationContext
        if (!hasFine(ctx) && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            onResult(baseInfo(ctx).put("error", "Location permission isn't granted to H.I.B on the phone"))
            return
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && hasFine(ctx) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            else -> null
        }
        val done = AtomicBoolean(false)
        val cancel = CancellationSignal()

        fun finish(location: Location?) {
            if (!done.compareAndSet(false, true)) return
            cancel.cancel()
            val best = location ?: lastKnown(lm)
            val payload = baseInfo(ctx)
            if (best != null) {
                payload.put("lat", best.latitude)
                    .put("lon", best.longitude)
                    .put("accuracyM", best.accuracy.toDouble())
                    .put("altitude", best.altitude)
                    .put("speed", best.speed.toDouble())
                    .put("bearing", best.bearing.toDouble())
                    .put("timestamp", best.time)
                    .put("provider", best.provider ?: provider ?: "unknown")
            } else {
                payload.put("error", if (provider == null) "Location is turned off on the phone" else "The phone couldn't get a location fix")
            }
            executor.execute { onResult(payload) }
        }

        if (provider == null) {
            finish(null)
            return
        }
        main.postDelayed({ finish(null) }, TIMEOUT_MS)
        runCatching {
            lm.getCurrentLocation(provider, cancel, executor) { location -> finish(location) }
        }.onFailure { finish(null) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? = runCatching {
        lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()

    private fun baseInfo(context: Context): JSONObject {
        val payload = JSONObject().put("timestamp", System.currentTimeMillis())
        runCatching {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0 && scale > 0) payload.put("batteryPct", level * 100 / scale)
                payload.put("isCharging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
        }
        networkName(context)?.let { payload.put("networkName", it) }
        return payload
    }

    @Suppress("DEPRECATION")
    private fun networkName(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "Mobile data" else null
        }
        val info = (caps.transportInfo as? WifiInfo)
            ?: (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('"') && it != "<unknown ssid>" }
    }.getOrNull()
}
