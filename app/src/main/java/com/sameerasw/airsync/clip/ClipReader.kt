package com.sameerasw.airsync.clip

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Reads the phone's clipboard for the sync component. Android only answers while H.I.B has input
 * focus (its own window in front, or the 1×1 overlay of [ClipFocusReader]); otherwise it returns
 * nothing and logs "Denying clipboard access to …".
 */
object ClipReader {
    private const val TAG = "HIBClip"
    /** ClipDescription extra holding the id of a clip H.I.B applied (SPACE.md §16.2 "applying is not copying"). */
    const val TAG_EXTRA = "com.sameerasw.airsync.clipId"
    private const val MAX_SOURCE_BYTES = 40L * 1024 * 1024

    class Snapshot(
        val text: String?,
        val imageUri: Uri?,
        val mime: String?,
        val tag: String?,
        val stamp: Long?
    )

    /**
     * Only the description (tag + change timestamp): reading it doesn't make Android show
     * "H.I.B pasted from your clipboard", so H.I.B looks here before reading the clip itself.
     */
    fun description(context: Context): Pair<String?, Long?>? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val desc = runCatching { cm.primaryClipDescription }.getOrNull() ?: return null
        val tag = runCatching { desc.extras?.getString(TAG_EXTRA) }.getOrNull()
        return tag to desc.timestamp.takeIf { it > 0 }
    }

    /** Cheap, on the thread that has focus. Null when Android refused the read or the clipboard is empty. */
    fun snapshot(context: Context): Snapshot? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val desc = clip.description
        val tag = runCatching { desc.extras?.getString(TAG_EXTRA) }.getOrNull()
        val stamp = desc.timestamp.takeIf { it > 0 }
        val item = clip.getItemAt(0)
        val text = item.text?.toString()
        if (!text.isNullOrEmpty()) return Snapshot(text, null, null, tag, stamp)
        val uri = item.uri
        if (uri != null) {
            val mime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
                ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("image/") }
            if (mime != null) return Snapshot(null, uri, mime, tag, stamp)
        }
        return Snapshot(null, null, null, tag, stamp)
    }

    /** True for images H.I.B itself put on the clipboard (they live behind its own FileProvider). */
    fun isOwnImage(context: Context, s: Snapshot): Boolean =
        s.imageUri?.authority == "${context.packageName}.clipboard-files"

    /**
     * Off the main thread: the snapshot as a [LocalClip]. Images are sent as PNG (re-encoded unless
     * they already are PNG); one over [ClipSyncCore.MAX_PNG_BYTES] is not carried (one log line).
     */
    fun toLocal(context: Context, s: Snapshot): LocalClip? {
        if (s.text != null) return LocalClip(s.text, null, s.tag, s.stamp)
        val uri = s.imageUri ?: return null
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SOURCE_BYTES) return@use null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull()
        if (raw == null) {
            Log.d(TAG, "[clip] image unreadable")
            return null
        }
        val png = if (s.mime.equals("image/png", ignoreCase = true)) raw else reencodePng(raw) ?: return null
        if (png.size > ClipSyncCore.MAX_PNG_BYTES) {
            Log.d(TAG, "[clip] ignored(size) id=${ClipIds.short(ClipIds.random())} from=phone via=phone")
            return null
        }
        return LocalClip(null, Base64.getEncoder().encodeToString(png), s.tag, s.stamp)
    }

    private fun reencodePng(bytes: ByteArray): ByteArray? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        try {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }.getOrNull()
}
