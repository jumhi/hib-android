package com.sameerasw.airsync.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.sameerasw.airsync.R
import com.sameerasw.airsync.multidesk.ComputerLiveStore
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.utils.JsonUtil

/**
 * H.I.B "Clipboard" widget: Send puts this phone's clipboard on the computer you're using; Get puts the text
 * a computer last copied on this phone. Shows that text and where it came from when there's room.
 */
class ClipboardWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    companion object {
        fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val (_, h) = HibWidgets.sizeDp(mgr, id, 180, 110)
            val v = RemoteViews(context.packageName, R.layout.widget_hib_clipboard)
            val state = WidgetModels.clipboard(HibWidgets.target(), ComputerLiveStore.lastClip.value, System.currentTimeMillis())
            HibWidgets.background(v)
            v.setTextViewText(R.id.hw_header_text, state.header)
            v.setOnClickPendingIntent(R.id.hw_header, HibWidgets.openApp(context, id * 16))
            v.setContentDescription(R.id.hw_send, if (state.target != null) "Send clipboard to ${state.target}" else "Send clipboard")
            v.setContentDescription(R.id.hw_get, "Get the text a computer copied")
            v.setFloat(R.id.hw_send, "setAlpha", if (state.canSend) 1f else 0.5f)
            v.setFloat(R.id.hw_get, "setAlpha", if (state.canGet) 1f else 0.5f)
            v.setOnClickPendingIntent(R.id.hw_send, WidgetClipboardActivity.pendingIntent(context, id, WidgetClipboardActivity.SEND))
            v.setOnClickPendingIntent(R.id.hw_get, WidgetClipboardActivity.pendingIntent(context, id, WidgetClipboardActivity.GET))
            val roomForText = h >= 150 && state.lastClip != null
            v.setViewVisibility(R.id.hw_last, if (roomForText) View.VISIBLE else View.GONE)
            if (roomForText) {
                v.setTextViewText(R.id.hw_last_from, state.lastClipFrom)
                v.setTextViewText(R.id.hw_last_text, state.lastClip)
            }
            mgr.updateAppWidget(id, v)
        }
    }
}

/**
 * Invisible helper behind the Clipboard widget's buttons: Android lets an app read the clipboard only while
 * it is in front, so this opens for a moment, does the job, says so and closes.
 */
class WidgetClipboardActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        when (intent?.getStringExtra(EXTRA_MODE)) {
            SEND -> send()
            GET -> get()
        }
        HibWidgets.updateAll(this)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopHub.start(this)
    }

    private fun send() {
        val target = HibWidgets.target()
        if (target == null) {
            toast("No computer connected")
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        val text = runCatching {
            val clip = cm?.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).coerceToText(this)?.toString() }.joinToString("\n").trim()
        }.getOrNull()
        if (text.isNullOrEmpty()) {
            toast("The clipboard is empty")
            return
        }
        val ok = DesktopHub.sendTo(target, JsonUtil.createClipboardUpdateJson(text))
        toast(if (ok) "Sent to $target" else "Couldn't reach $target")
    }

    private fun get() {
        val clip = ComputerLiveStore.lastClip.value
        if (clip == null) {
            toast("Nothing copied on a computer yet")
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        val ok = runCatching { cm?.setPrimaryClip(ClipData.newPlainText("H.I.B", clip.text)); true }.getOrDefault(false)
        toast(if (ok) "Copied from ${clip.computer}" else "Couldn't copy")
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val SEND = "send"
        const val GET = "get"
        private const val EXTRA_MODE = "com.sameerasw.airsync.widget.EXTRA_CLIP_MODE"

        fun pendingIntent(context: Context, id: Int, mode: String): PendingIntent {
            val intent = Intent(context, WidgetClipboardActivity::class.java).apply {
                putExtra(EXTRA_MODE, mode)
                data = Uri.parse("hib-widget://clipboard/$mode/$id")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
            return PendingIntent.getActivity(
                context, id * 16 + if (mode == SEND) 1 else 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
