package com.sameerasw.airsync.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.home.CardStatus
import com.sameerasw.airsync.home.DeviceCardUi
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.utils.MacModelMapper
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * H.I.B "Computer in use" widget (the original widget, refreshed): a picture of the computer you're using
 * — any of them, not only the first one paired — with its status, battery and what it plays. A tap opens
 * its quick actions; when it's offline or paused, a tap reconnects it. There is no disconnect here.
 */
class AirSyncWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "AirSyncWidget"
        const val ACTION_RECONNECT = "com.sameerasw.airsync.widget.ACTION_RECONNECT"

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(context, AirSyncWidgetProvider::class.java)) }.getOrNull() ?: return
            ids.forEach { update(context, mgr, it) }
        }

        /** The computer this widget shows: the one in use (target rule), else the first card (same order as Home). */
        fun shown(cards: List<DeviceCardUi>, target: String?): DeviceCardUi? =
            target?.let { t -> cards.firstOrNull { PairedDesktops.sameName(it.name, t) } } ?: cards.firstOrNull()

        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            try {
                val v = RemoteViews(context.packageName, R.layout.widget_airsync)
                HibWidgets.background(v)
                val card = shown(HibWidgets.cards(), HibWidgets.target())
                if (card == null) {
                    v.setTextViewText(R.id.widget_device_name, "H.I.B")
                    v.setTextViewText(R.id.widget_secondary_line, "Open H.I.B to add a computer")
                    v.setViewVisibility(R.id.widget_battery_container, View.GONE)
                    v.setViewVisibility(R.id.widget_media_info, View.GONE)
                    v.setViewVisibility(R.id.widget_tap_hint, View.GONE)
                    HibWidgets.tint(context, v, R.id.widget_status_dot, R.color.hib_widget_dot_off)
                    v.setOnClickPendingIntent(R.id.widget_container, HibWidgets.openApp(context, id * 16))
                    mgr.updateAppWidget(id, v)
                    return
                }

                val record = DesktopHub.pairedRecord(card.name)
                val image = if (card.platform == "windows") R.drawable.hib_wic_desktop
                else MacModelMapper.getPreviewRes(card.name, record?.model, record?.type)
                v.setImageViewResource(R.id.widget_device_image, image)
                v.setFloat(R.id.widget_device_image, "setAlpha", if (card.isConnected) 1f else 0.5f)
                v.setTextViewText(R.id.widget_device_name, card.name)
                v.setTextViewText(R.id.widget_secondary_line, card.subtitle)
                HibWidgets.tint(context, v, R.id.widget_status_dot, HibWidgets.dotColor(WidgetModels.dot(card.status)))

                if (card.battery != null) {
                    v.setViewVisibility(R.id.widget_battery_container, View.VISIBLE)
                    v.setTextViewText(R.id.widget_battery_text, "${card.battery}%")
                } else {
                    v.setViewVisibility(R.id.widget_battery_container, View.GONE)
                }
                val playing = card.nowPlaying ?: if (card.playingPhoneAudio) "Playing this phone's audio" else null
                v.setViewVisibility(R.id.widget_media_info, if (playing != null) View.VISIBLE else View.GONE)
                v.setTextViewText(R.id.widget_media_info, playing?.let { "♪ $it" } ?: "")

                val canReconnect = card.status == CardStatus.OFFLINE || (card.status == CardStatus.PAUSED && !HibWidgets.appPaused)
                v.setViewVisibility(R.id.widget_tap_hint, if (canReconnect) View.VISIBLE else View.GONE)
                val tap = if (canReconnect) {
                    HibWidgets.broadcast(
                        context, AirSyncWidgetProvider::class.java, ACTION_RECONNECT, id * 16 + 1,
                        HibWidgets.EXTRA_COMPUTER to card.name
                    )
                } else HibWidgets.openApp(context, id * 16 + 2, card.name)
                v.setOnClickPendingIntent(R.id.widget_container, tap)
                v.setContentDescription(R.id.widget_container, "${card.name}, ${card.subtitle}")
                mgr.updateAppWidget(id, v)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget $id: ${e.message}")
            }
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_RECONNECT || !HibWidgets.isOurs(context, intent)) return
        val name = intent.getStringExtra(HibWidgets.EXTRA_COMPUTER) ?: return
        val app = context.applicationContext
        val primary = DesktopHub.primaryName
        if (primary != null && PairedDesktops.sameName(primary, name)) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ds = DataStoreManager.getInstance(app)
                    ds.setUserManuallyDisconnected(false)
                    val last = ds.getLastConnectedDevice().first()
                    if (last != null) kotlinx.coroutines.withContext(Dispatchers.Main) {
                        WebSocketUtil.connect(
                            context = app,
                            ipAddress = last.ipAddress,
                            port = last.port.toIntOrNull() ?: 6996,
                            symmetricKey = last.symmetricKey,
                            manualAttempt = true,
                            onConnectionStatus = { HibWidgets.updateAll(app) },
                            onHandshakeTimeout = { HibWidgets.updateAll(app) }
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Widget reconnect failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
        } else {
            DesktopHub.setEnabled(app, name, true)
            DesktopHub.reconnectNow(name)
        }
        HibWidgets.updateAll(app)
    }
}
