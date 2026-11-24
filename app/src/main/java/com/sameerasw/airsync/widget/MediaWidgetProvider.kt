package com.sameerasw.airsync.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.RemoteViews
import android.widget.Toast
import com.sameerasw.airsync.R
import com.sameerasw.airsync.home.DeviceActions
import com.sameerasw.airsync.multidesk.ComputerLiveStore
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.PhoneAudioRemote

/**
 * H.I.B "Media & Play on" widget: what the computer you're using plays (or whichever computer is playing),
 * with previous / play-pause / next for *that* computer, and a Play on… toggle that sends this phone's audio
 * to the computer in use (or stops it).
 */
class MediaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in setOf(ACTION_MEDIA, ACTION_PLAY_ON) && !HibWidgets.isOurs(context, intent)) return
        when (intent.action) {
            ACTION_MEDIA -> {
                val computer = intent.getStringExtra(HibWidgets.EXTRA_COMPUTER) ?: return
                val action = intent.getStringExtra(HibWidgets.EXTRA_ACTION) ?: return
                if (!DeviceActions.remoteControl(computer, action)) {
                    Toast.makeText(context, "Couldn't reach $computer", Toast.LENGTH_SHORT).show()
                }
            }
            ACTION_PLAY_ON -> {
                val to = intent.getStringExtra(HibWidgets.EXTRA_COMPUTER)
                val ok = if (to == null) PhoneAudioRemote.stop() else PhoneAudioRemote.playOn(to)
                if (!ok) Toast.makeText(context, if (to == null) "Couldn't stop Play on" else "Couldn't reach $to", Toast.LENGTH_SHORT).show()
                HibWidgets.updateAll(context)
            }
        }
    }

    companion object {
        const val ACTION_MEDIA = "com.sameerasw.airsync.widget.MEDIA"
        const val ACTION_PLAY_ON = "com.sameerasw.airsync.widget.PLAY_ON"

        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val (_, h) = HibWidgets.sizeDp(mgr, id, 250, 110)
            val compact = h < 100
            val v = RemoteViews(context.packageName, if (compact) R.layout.widget_hib_media_compact else R.layout.widget_hib_media)
            val state = WidgetModels.media(
                DesktopHub.connectedIds(),
                ComputerLiveStore.live.value,
                HibWidgets.target(),
                PhoneAudioRemote.playingOn.value
            )
            HibWidgets.background(v)
            v.setTextViewText(R.id.hw_title, state.title)
            v.setTextViewText(R.id.hw_sub, state.subtitle)
            v.setOnClickPendingIntent(R.id.hw_content, HibWidgets.openApp(context, id * 16))

            // Album art: the computer's small cover, else a music note.
            val art = state.computer?.let { artFor(it) }
            if (art != null) {
                v.setImageViewBitmap(R.id.hw_art, art)
                v.setViewVisibility(R.id.hw_art, android.view.View.VISIBLE)
                v.setViewVisibility(R.id.hw_art_icon, android.view.View.GONE)
            } else {
                v.setViewVisibility(R.id.hw_art, android.view.View.GONE)
                v.setViewVisibility(R.id.hw_art_icon, android.view.View.VISIBLE)
            }

            v.setImageViewResource(R.id.hw_play, if (state.isPlaying) R.drawable.hib_wic_pause else R.drawable.hib_wic_play)
            v.setContentDescription(R.id.hw_play, if (state.isPlaying) "Pause" else "Play")
            val alpha = if (state.canControl) 255 else 90
            listOf(R.id.hw_play, R.id.hw_next).forEach { v.setInt(it, "setImageAlpha", alpha) }
            if (!compact) v.setInt(R.id.hw_prev, "setImageAlpha", alpha)
            state.computer?.takeIf { state.canControl }?.let { who ->
                v.setOnClickPendingIntent(R.id.hw_play, media(context, id, 1, who, "media_play_pause"))
                v.setOnClickPendingIntent(R.id.hw_next, media(context, id, 2, who, "media_next"))
                if (!compact) v.setOnClickPendingIntent(R.id.hw_prev, media(context, id, 3, who, "media_prev"))
            }

            if (!compact) {
                v.setTextViewText(R.id.hw_playon_label, state.playOnLabel)
                v.setInt(R.id.hw_playon, "setBackgroundResource", if (state.playOnActive) R.drawable.hib_widget_pill_accent else R.drawable.hib_widget_pill)
                val fg = if (state.playOnActive) R.color.hib_widget_on_accent else R.color.hib_widget_on_container
                HibWidgets.textColor(context, v, R.id.hw_playon_label, fg)
                HibWidgets.tint(context, v, R.id.hw_playon_icon, fg)
                v.setContentDescription(R.id.hw_playon, if (state.playOnActive) "${state.playOnLabel}. Tap to stop." else state.playOnLabel)
                if (state.playOnActive || state.playOnTarget != null) {
                    v.setOnClickPendingIntent(
                        R.id.hw_playon,
                        HibWidgets.broadcast(
                            context, MediaWidgetProvider::class.java, ACTION_PLAY_ON, id * 16 + 4,
                            HibWidgets.EXTRA_COMPUTER to state.playOnTarget
                        )
                    )
                }
            }
            mgr.updateAppWidget(id, v)
        }

        private fun media(context: Context, id: Int, n: Int, computer: String, action: String) =
            HibWidgets.broadcast(
                context, MediaWidgetProvider::class.java, ACTION_MEDIA, id * 16 + n,
                HibWidgets.EXTRA_COMPUTER to computer, HibWidgets.EXTRA_ACTION to action
            )

        /** A small cover for [computer]: the primary connection's art, else the computer's own small art. */
        private fun artFor(computer: String): Bitmap? {
            val primary = DesktopHub.primaryName
            val big = if (primary != null && PairedDesktops.sameName(primary, computer)) MacDeviceStatusManager.albumArt.value else null
            if (big != null) return scaled(big)
            val b64 = ComputerLiveStore.of(computer)?.artLite ?: return null
            return runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

        private fun scaled(b: Bitmap): Bitmap {
            val max = 192
            if (b.width <= max && b.height <= max) return b
            val s = max.toFloat() / maxOf(b.width, b.height)
            return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
        }
    }
}
