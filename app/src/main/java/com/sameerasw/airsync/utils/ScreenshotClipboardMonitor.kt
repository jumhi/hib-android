package com.sameerasw.airsync.utils

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first

/**
 * Watches for newly-created screenshots while H.I.B is connected and mirrors the newest
 * screenshot onto the Mac pasteboard. This avoids Android's background clipboard restriction
 * while preserving the zero-extra-step screenshot -> paste experience.
 */
object ScreenshotClipboardMonitor {
    private const val TAG = "HIBScreenshotClipboard"
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024 // stays under OkHttp's 16 MiB queue after base64 + encryption

    private val running = AtomicBoolean(false)
    private var observer: ContentObserver? = null
    private var workerThread: HandlerThread? = null
    private var newestHandledId: Long = -1L
    private var startedAtSeconds: Long = 0L

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        startedAtSeconds = System.currentTimeMillis() / 1000L
        workerThread = HandlerThread("HIB-Screenshot-Clipboard").also { it.start() }
        val handler = Handler(workerThread!!.looper)
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ syncNewestScreenshot(appContext) }, 450L)
            }
        }
        appContext.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer!!
        )
        Log.d(TAG, "Screenshot universal clipboard monitor started")
    }

    fun stop(context: Context) {
        if (!running.compareAndSet(true, false)) return
        observer?.let { runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) } }
        observer = null
        workerThread?.quitSafely()
        workerThread = null
        newestHandledId = -1L
    }

    private fun syncNewestScreenshot(context: Context) {
        if (!running.get() || !WebSocketUtil.isAnyDesktopConnected()) return
        // Respect the clipboard-sync switch immediately, not only when the service restarts.
        val syncEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                com.sameerasw.airsync.data.local.DataStoreManager(context).getClipboardSyncEnabled().first()
            }
        }.getOrDefault(false)
        if (!syncEnabled) return
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "Screenshot.png"
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "image/png"
                val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)) ?: ""
                val added = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                val looksLikeScreenshot = path.contains("screenshot", ignoreCase = true) ||
                    name.contains("screenshot", ignoreCase = true) ||
                    name.startsWith("Screenshot_", ignoreCase = true)

                if (!looksLikeScreenshot || id == newestHandledId || added < startedAtSeconds - 2L) return
                newestHandledId = id
                if (size <= 0L || size > MAX_IMAGE_BYTES) {
                    Log.w(TAG, "Skipped screenshot outside clipboard size limit: $size")
                    return
                }

                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                val payload = JSONObject()
                    .put("type", "clipboardImageUpdate")
                    .put("data", JSONObject()
                        .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("mime", mime)
                        .put("name", name)
                    )
                    .toString()
                if (WebSocketUtil.sendMessage(payload)) {
                    Log.d(TAG, "Screenshot placed on Mac clipboard: $name")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Screenshot access unavailable; enable Wallpaper & File Access", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync screenshot", e)
        }
    }
}
