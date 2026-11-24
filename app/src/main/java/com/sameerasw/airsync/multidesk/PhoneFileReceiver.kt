package com.sameerasw.airsync.multidesk

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.sameerasw.airsync.utils.NotificationUtil
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * H.I.B — desktop → phone files (PROTOCOL.md §6.9). A Mac or Windows PC sends
 * fileTransferInit → fileChunk… → fileTransferComplete; the phone writes each chunk at
 * `index * chunkSize` into a private cache file, acks every chunk to the sender, verifies size and
 * SHA-256 on completion, then publishes the file to Downloads/H.I.B and posts a notification with a
 * preview, Open and Share. A transfer tagged `flickId` is a Flick from that computer (SPACE.md §3.4):
 * its notification is a heads-up "Flick from <from>".
 *
 * Chunks are handled synchronously on the sender's socket thread, so writes for one transfer are
 * ordered and memory stays flat (one 64 KB chunk at a time).
 */
object PhoneFileReceiver {
    private const val TAG = "PhoneFileReceiver"
    const val FOLDER = "H.I.B"
    private const val MAX_SIZE = 8L * 1024 * 1024 * 1024 // 8 GB sanity cap

    private class Incoming(
        val id: String,
        val senderId: String,
        val senderName: String,
        val name: String,
        val size: Long,
        val mime: String,
        val chunkSize: Int,
        val checksum: String?,
        val isClipboard: Boolean,
        /** Set when a computer flicked this file to the phone (pairs with its `flickStart`). */
        val flickId: String?,
        val file: File,
        val raf: RandomAccessFile,
        val notifId: Int,
        var received: Long = 0L,
        var lastNotify: Long = 0L,
        val received0: MutableSet<Int> = HashSet()
    )

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val main = Handler(Looper.getMainLooper())

    fun isReceiving(id: String): Boolean = incoming.containsKey(id)

    fun onInit(context: Context, from: DesktopLink, data: JSONObject) {
        val id = data.optString("id")
        if (id.isEmpty()) return
        val name = sanitizeName(data.optString("name").ifBlank { "file" })
        val size = data.optLong("size", -1L)
        val chunkSize = data.optInt("chunkSize", 64 * 1024).coerceIn(1024, 4 * 1024 * 1024)
        if (size < 0 || size > MAX_SIZE) {
            from.send(cancelJson(id, "badSize"))
            return
        }
        val dir = File(context.cacheDir, "hib_incoming").apply { mkdirs() }
        val file = File(dir, "in_${id.filter { it.isLetterOrDigit() || it == '-' }.take(64)}")
        try {
            incoming.remove(id)?.let { closeQuietly(it); it.file.delete() }
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0)
            val flickId = data.optString("flickId").ifBlank { null }
            val senderName = IncomingFlicks.meta(flickId)?.from
                ?: data.optString("relayFrom").ifBlank { null }
                ?: from.name
            val state = Incoming(
                id = id,
                senderId = from.id,
                senderName = senderName,
                name = name,
                size = size,
                mime = data.optString("mime").ifBlank { guessMime(name) },
                chunkSize = chunkSize,
                checksum = data.optString("checksum").ifBlank { null }?.lowercase(),
                isClipboard = data.optBoolean("isClipboard", false),
                flickId = flickId,
                file = file,
                raf = raf,
                notifId = ("rx$id").hashCode()
            )
            incoming[id] = state
            Log.d(TAG, "Receiving $name ($size bytes) from ${from.name}")
            notifyProgress(context, state, force = true)
        } catch (e: Exception) {
            Log.e(TAG, "Can't start receive: ${e.message}")
            from.send(cancelJson(id, "storage"))
        }
    }

    fun onChunk(context: Context, from: DesktopLink, data: JSONObject) {
        val id = data.optString("id")
        val index = data.optInt("index", -1)
        // Always ack (even unknown ids) so a sender never stalls on us — same rule as the desktops.
        val state = incoming[id]
        if (state != null && index >= 0) {
            try {
                val bytes = Base64.decode(data.optString("chunk"), Base64.DEFAULT)
                synchronized(state) {
                    state.raf.seek(index.toLong() * state.chunkSize)
                    state.raf.write(bytes)
                    if (state.received0.add(index)) state.received += bytes.size
                }
                notifyProgress(context, state, force = false)
            } catch (e: Exception) {
                Log.e(TAG, "Chunk $index write failed: ${e.message}")
                return
            }
        }
        from.send(JSONObject().put("type", "fileChunkAck")
            .put("data", JSONObject().put("id", id).put("index", index)).toString())
    }

    fun onComplete(context: Context, from: DesktopLink, data: JSONObject) {
        val id = data.optString("id")
        val state = incoming.remove(id) ?: return
        val expected = data.optString("checksum").ifBlank { null }?.lowercase() ?: state.checksum
        Thread {
            var ok = false
            var uri: Uri? = null
            var picture: android.graphics.Bitmap? = null
            val meta = IncomingFlicks.meta(state.flickId)
            try {
                closeQuietly(state)
                val actualSize = state.file.length()
                if (actualSize != state.size) {
                    Log.w(TAG, "Size mismatch for ${state.name}: $actualSize != ${state.size}")
                } else {
                    val sha = sha256(state.file)
                    if (expected != null && expected != sha) {
                        Log.w(TAG, "Checksum mismatch for ${state.name}")
                    } else {
                        // "Send clipboard" from the Mac: an image goes straight onto the phone's clipboard too.
                        if (state.isClipboard && state.mime.startsWith("image/") && state.size < 20L * 1024 * 1024) {
                            runCatching {
                                com.sameerasw.airsync.utils.ClipboardUtil.setClipboardImage(
                                    context, state.file.readBytes(), state.mime, state.name
                                )
                            }
                        }
                        picture = IncomingFlicks.preview(state.file, state.mime, meta?.thumbnail)
                        uri = publish(context, state)
                        ok = uri != null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Finishing ${state.name} failed: ${e.message}")
            } finally {
                state.file.delete()
            }
            from.send(JSONObject().put("type", "transferVerified")
                .put("data", JSONObject().put("id", id).put("verified", ok)).toString())
            IncomingFlicks.forget(state.flickId)
            // A file of a bundle (SPACE.md §14): the bundle shows one notification for all of them.
            val flickId = state.flickId
            val bundled = flickId != null && runCatching {
                IncomingFlicks.bundleFinished(
                    context, flickId, ok && uri != null,
                    uri?.let { IncomingFlicks.shareableUri(context, it) }, picture
                )
            }.getOrDefault(false)
            if (bundled) {
                runCatching { NotificationManagerCompat.from(context).cancel(state.notifId) }
            } else {
                showDone(context, state, ok, uri, picture, meta?.from ?: state.senderName)
            }
        }.start()
    }

    fun onCancel(context: Context, data: JSONObject): Boolean {
        val id = data.optString("id")
        val state = incoming.remove(id) ?: return false
        closeQuietly(state)
        state.file.delete()
        IncomingFlicks.forget(state.flickId)
        runCatching { NotificationManagerCompat.from(context).cancel(state.notifId) }
        val flickId = state.flickId
        if (flickId != null && IncomingFlicks.bundleFinished(context, flickId, false, null, null)) return true
        toast(context, "${state.senderName} cancelled ${state.name}")
        return true
    }

    /** Called when a computer disconnects: drop its half-received files. */
    fun onSenderGone(context: Context, desktopId: String) {
        incoming.values.filter { it.senderId == desktopId }.forEach { s ->
            incoming.remove(s.id)
            closeQuietly(s)
            s.file.delete()
            runCatching { NotificationManagerCompat.from(context).cancel(s.notifId) }
        }
        runCatching { IncomingFlicks.senderGone(context, desktopId) }
    }

    /** Cancel from the phone's notification. */
    fun cancelFromPhone(context: Context, id: String): Boolean {
        val state = incoming.remove(id) ?: return false
        closeQuietly(state)
        state.file.delete()
        runCatching { NotificationManagerCompat.from(context).cancel(state.notifId) }
        DesktopHub.sendTo(state.senderId, cancelJson(id, "cancelledOnPhone"))
        return true
    }

    // MARK: - helpers

    private fun publish(context: Context, s: Incoming): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, s.name)
            put(MediaStore.Downloads.MIME_TYPE, s.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                s.file.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IllegalStateException("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(context: Context, s: Incoming, force: Boolean) {
        // Files of a bundle share one progress notification (SPACE.md §14).
        val flickId = s.flickId
        if (flickId != null && IncomingFlicks.inBundle(flickId)) {
            IncomingFlicks.bundleProgress(context, flickId, s.received)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - s.lastNotify < 700) return
        s.lastNotify = now
        val pct = if (s.size > 0) ((s.received * 100) / s.size).toInt().coerceIn(0, 100) else 0
        runCatching {
            NotificationUtil.showFileProgress(
                context, s.notifId, "${s.name} from ${s.senderName}", pct, "hibrx:${s.id}", isSending = false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDone(context: Context, s: Incoming, ok: Boolean, uri: Uri?, picture: android.graphics.Bitmap?, from: String) {
        if (ok && uri != null) {
            runCatching { NotificationManagerCompat.from(context).cancel(s.notifId) }
            val title = when {
                s.flickId != null -> "Flick from $from"
                s.isClipboard -> "Copied from $from"
                else -> "From $from"
            }
            runCatching {
                FlickNotifications.showFile(
                    context, s.notifId + 1, title, s.name, s.mime,
                    IncomingFlicks.shareableUri(context, uri), picture
                )
            }.onFailure {
                Log.w(TAG, "Rich notification failed (${it.javaClass.simpleName}); using the plain one")
                runCatching { NotificationUtil.showFileComplete(context, s.notifId, s.name, true, isSending = false, contentUri = uri) }
            }
        } else {
            runCatching {
                NotificationUtil.showFileComplete(context, s.notifId, s.name, false, isSending = false, contentUri = null)
            }
        }
        toast(context, if (ok) "${s.name} from $from is in Download/$FOLDER" else "Couldn't receive ${s.name} from $from")
    }

    private fun toast(context: Context, text: String) {
        if (!com.sameerasw.airsync.AirSyncApp.isAppForeground()) return
        main.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun closeQuietly(s: Incoming) {
        runCatching { synchronized(s) { s.raf.close() } }
    }

    fun cancelJson(id: String, reason: String): String =
        JSONObject().put("type", "fileTransferCancel")
            .put("data", JSONObject().put("id", id).put("reason", reason)).toString()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().trimStart('.')
        return base.ifBlank { "file" }.take(180)
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}
