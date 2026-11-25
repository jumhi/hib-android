package com.sameerasw.airsync.clip

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * SPACE.md §16.3: Android lets an app read the clipboard only while it has input focus. When a copy
 * happens in another app, H.I.B briefly takes focus with an invisible 1×1 focusable overlay window
 * (needs "Display over other apps", granted over ADB with `appops set … SYSTEM_ALERT_WINDOW allow`),
 * reads the clip, and removes the window — at most [TIMEOUT_MS].
 */
object ClipFocusReader {
    const val TIMEOUT_MS = 300L
    private val main = Handler(Looper.getMainLooper())
    private var window: View? = null

    fun canOverlay(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** Calls [onRead] on the main thread with the clip, or null when focus never came in time. */
    fun read(context: Context, onRead: (ClipReader.Snapshot?) -> Unit) {
        main.post { readOnMain(context.applicationContext, onRead) }
    }

    private fun readOnMain(ctx: Context, onRead: (ClipReader.Snapshot?) -> Unit) {
        if (window != null || !canOverlay(ctx)) {
            onRead(null)
            return
        }
        val wm = ctx.getSystemService(WindowManager::class.java) ?: run { onRead(null); return }
        var done = false
        lateinit var view: View
        fun finish(snapshot: ClipReader.Snapshot?) {
            if (done) return
            done = true
            main.removeCallbacksAndMessages(view)
            runCatching { wm.removeViewImmediate(view) }
            window = null
            onRead(snapshot)
        }
        view = object : View(ctx) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) finish(runCatching { ClipReader.snapshot(ctx) }.getOrNull())
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE); touches outside the 1×1 pixel go to the app below;
            // ALT_FOCUSABLE_IM keeps the keyboard out of it.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            title = "H.I.B clipboard"
        }
        val added = runCatching { wm.addView(view, params) }.isSuccess
        if (!added) {
            onRead(null)
            return
        }
        window = view
        main.postAtTime({ finish(null) }, view, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS)
    }
}
