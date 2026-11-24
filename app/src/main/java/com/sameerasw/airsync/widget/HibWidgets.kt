package com.sameerasw.airsync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.home.DeviceCardUi
import com.sameerasw.airsync.home.DeviceCards
import com.sameerasw.airsync.home.HomeFocus
import com.sameerasw.airsync.multidesk.ComputerLiveStore
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.utils.PhoneAudioRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.I.B home-screen widgets: keeps every widget (Devices, Media, Desk Deck, Clipboard, Computer in use) in
 * step with the computers, and the small toolkit they share (background, colours, intents, sizes).
 * Every widget treats the computers equally; actions go to the computer in use (the target rule), or to the
 * computer a row names.
 */
object HibWidgets {
    private const val TAG = "HibWidgets"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    @Volatile var transparency: Float = 1f
        private set
    @Volatile var appPaused: Boolean = false
        private set
    @Volatile var primaryPausedByUser: Boolean = false
        private set

    val providers: List<Class<out AppWidgetProvider>> = listOf(
        DevicesWidgetProvider::class.java,
        MediaWidgetProvider::class.java,
        DeckWidgetProvider::class.java,
        ClipboardWidgetProvider::class.java,
        AirSyncWidgetProvider::class.java
    )

    /** Called once per process (AirSyncApp): widgets follow the live state from here on. */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val ds = DataStoreManager.getInstance(app)
        scope.launch { ds.getWidgetTransparency().collect { transparency = it; updateAll(app) } }
        scope.launch {
            combine(ds.isAppPaused(), ds.getUserManuallyDisconnected()) { a, b -> a to b }.collect { (a, b) ->
                appPaused = a
                primaryPausedByUser = b
                updateAll(app)
            }
        }
        scope.launch {
            merge(
                DesktopHub.desktops.map { },
                DesktopHub.inUseId.map { },
                ComputerLiveStore.live.map { },
                ComputerLiveStore.lastClip.map { },
                PhoneAudioRemote.playingOn.map { }
            ).debounce(400).collect { updateAll(app) }
        }
    }

    /** Re-draws every H.I.B widget that is on a home screen. Cheap when there are none. */
    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        for (cls in providers) {
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, cls)) }.getOrNull() ?: continue
            if (ids.isEmpty()) continue
            try {
                when (cls) {
                    DevicesWidgetProvider::class.java -> ids.forEach { DevicesWidgetProvider.update(context, mgr, it) }
                    MediaWidgetProvider::class.java -> ids.forEach { MediaWidgetProvider.update(context, mgr, it) }
                    DeckWidgetProvider::class.java -> ids.forEach { DeckWidgetProvider.update(context, mgr, it) }
                    ClipboardWidgetProvider::class.java -> ids.forEach { ClipboardWidgetProvider.update(context, mgr, it) }
                    AirSyncWidgetProvider::class.java -> AirSyncWidgetProvider.updateAllWidgets(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't update ${cls.simpleName}: ${e.message}")
            }
        }
    }

    /** Every computer as Home shows it. */
    fun cards(): List<DeviceCardUi> = DeviceCards.build(
        DesktopHub.desktops.value,
        ComputerLiveStore.live.value,
        PhoneAudioRemote.playingOn.value,
        appPaused,
        primaryPausedByUser
    )

    /** The computer in use (target rule), or null when none is connected. */
    fun target(): String? = runCatching { DesktopHub.targetPick()?.id }.getOrNull()

    /** Asks the launcher to place [cls] on the home screen (Android shows its own confirmation). */
    fun requestPin(context: Context, cls: Class<out AppWidgetProvider>): Boolean {
        val mgr = AppWidgetManager.getInstance(context) ?: return false
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return runCatching { mgr.requestPinAppWidget(ComponentName(context, cls), null, null) }.getOrDefault(false)
    }

    // MARK: - toolkit

    /** Width (portrait) and height (portrait) the launcher gives [id], in dp. */
    fun sizeDp(mgr: AppWidgetManager, id: Int, defaultW: Int, defaultH: Int): Pair<Int, Int> {
        val o: Bundle = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull() ?: return defaultW to defaultH
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: defaultW
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: defaultH
        return w to h
    }

    /** The shared rounded background, honouring Settings → Widgets → transparency. */
    fun background(views: RemoteViews) {
        views.setInt(R.id.hw_bg, "setImageAlpha", (transparency * 255).toInt().coerceIn(0, 255))
    }

    fun textColor(context: Context, views: RemoteViews, id: Int, @ColorRes color: Int) {
        if (Build.VERSION.SDK_INT >= 31) views.setColorStateList(id, "setTextColor", color)
        else views.setTextColor(id, ContextCompat.getColor(context, color))
    }

    fun tint(context: Context, views: RemoteViews, id: Int, @ColorRes color: Int) {
        if (Build.VERSION.SDK_INT >= 31) views.setColorStateList(id, "setImageTintList", color)
        else views.setInt(id, "setColorFilter", ContextCompat.getColor(context, color))
    }

    @ColorRes
    fun dotColor(dot: WidgetDot): Int = when (dot) {
        WidgetDot.IN_USE -> R.color.hib_widget_dot_in_use
        WidgetDot.CONNECTED -> R.color.hib_widget_dot_connected
        WidgetDot.BUSY -> R.color.hib_widget_dot_busy
        WidgetDot.OFF -> R.color.hib_widget_dot_off
        WidgetDot.ALERT -> R.color.hib_widget_dot_alert
    }

    @DrawableRes
    fun platformIcon(platform: String): Int = if (platform == "windows") R.drawable.hib_wic_desktop else R.drawable.hib_wic_laptop

    @DrawableRes
    fun deckIcon(icon: String): Int = when (icon) {
        "lock" -> R.drawable.hib_wic_lock
        "play_pause" -> R.drawable.hib_wic_play
        "vol_down" -> R.drawable.hib_wic_vol_down
        "vol_up" -> R.drawable.hib_wic_vol_up
        "mute" -> R.drawable.hib_wic_mute
        "screenshot" -> R.drawable.hib_wic_camera
        "next" -> R.drawable.hib_wic_next
        "previous" -> R.drawable.hib_wic_previous
        "mic_mute" -> R.drawable.hib_wic_mic_off
        "screensaver" -> R.drawable.rounded_screenshot_monitor_24
        "show_desktop" -> R.drawable.hib_wic_show_desktop
        "mission_control" -> R.drawable.hib_wic_windows
        "brightness_down" -> R.drawable.rounded_brightness_5_24
        "brightness_up" -> R.drawable.rounded_brightness_7_24
        "phone_audio" -> R.drawable.rounded_music_cast_24
        else -> R.drawable.hib_wic_deck
    }

    /** Opens H.I.B (Home); with [computer], straight to that computer's quick actions. */
    fun openApp(context: Context, requestCode: Int, computer: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (computer != null) {
                putExtra(HomeFocus.EXTRA_COMPUTER, computer)
                data = Uri.parse("hib-widget://computer/" + Uri.encode(computer))
            }
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * A per-install secret on every widget button's broadcast. The widget receivers are exported (the
     * launcher must see them), so a button action is only acted on when it carries this token.
     */
    fun token(context: Context): String {
        val prefs = context.getSharedPreferences("hib_widgets", Context.MODE_PRIVATE)
        prefs.getString("token", null)?.let { return it }
        val t = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("token", t).apply()
        return t
    }

    /** True when [intent] came from one of our own widget buttons. */
    fun isOurs(context: Context, intent: Intent): Boolean = intent.getStringExtra(EXTRA_TOKEN) == token(context)

    /** A broadcast back to [cls] with [action] and string extras; unique per [requestCode]. */
    fun broadcast(context: Context, cls: Class<*>, action: String, requestCode: Int, vararg extras: Pair<String, String?>): PendingIntent {
        val intent = Intent(context, cls).apply {
            this.action = action
            putExtra(EXTRA_TOKEN, token(context))
            extras.forEach { (k, v) -> if (v != null) putExtra(k, v) }
            data = Uri.parse("hib-widget://$action/$requestCode")
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val EXTRA_ACTION = "com.sameerasw.airsync.widget.EXTRA_ACTION"
    const val EXTRA_COMPUTER = "com.sameerasw.airsync.widget.EXTRA_COMPUTER"
    private const val EXTRA_TOKEN = "com.sameerasw.airsync.widget.EXTRA_TOKEN"
}
