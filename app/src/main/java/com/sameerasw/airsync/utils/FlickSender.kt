package com.sameerasw.airsync.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * H.I.B — Flick to Mac: the wire side.
 *
 * 1. [sendFlickStart] fires the instant a card leaves the top of the phone's screen, so the Mac can
 *    launch its card before a single byte of the file has moved.
 * 2. [startFileTransfer] then streams the file with the Mac's existing file-transfer protocol
 *    (fileTransferInit → fileChunk… with fileChunkAck flow control → fileTransferComplete), tagging the
 *    init with `flickId` so the Mac can drive the flying card's progress ring.
 *
 * Files are streamed in 64 KB chunks with a bounded in-flight window, so memory stays flat for any size.
 */
object FlickSender {
    private const val TAG = "FlickSender"
    private const val CHUNK_SIZE = 64 * 1024
    private const val WINDOW = 12
    private const val ACK_TIMEOUT_MS = 2500L
    private const val MAX_ATTEMPTS = 4
    const val MAX_TEXT_CHARS = 100_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ackChannels = ConcurrentHashMap<String, Channel<Int>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _landed = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits a flick id when the Mac reports that card has landed (`flickLanded`). */
    val landed: SharedFlow<String> = _landed

    fun canSend(): Boolean = WebSocketUtil.isWifiConnected()

    // MARK: incoming (called from WebSocketMessageHandler on the socket thread)

    fun onChunkAck(data: JSONObject) {
        val id = data.optString("id")
        if (id.isEmpty()) return
        ackChannels[id]?.trySend(data.optInt("index", -1))
    }

    fun onFlickLanded(data: JSONObject) {
        val id = data.optString("id")
        if (id.isNotEmpty()) _landed.tryEmit(id)
    }

    // MARK: outgoing

    fun sendFlickStart(
        id: String,
        kind: String,
        name: String,
        mime: String,
        size: Long,
        exitXRatio: Float,
        velocity: Float,
        angle: Float,
        thumbnailBase64: String?,
        text: String?
    ): Boolean {
        val data = JSONObject().apply {
            put("id", id)
            put("kind", kind)
            put("name", name)
            put("mime", mime)
            put("size", size)
            put("exitXRatio", exitXRatio.toDouble())
            put("velocity", velocity.toDouble())
            put("angle", angle.toDouble())
            if (!thumbnailBase64.isNullOrEmpty()) put("thumbnail", thumbnailBase64)
            if (text != null) put("text", text.take(MAX_TEXT_CHARS))
            // Where the phone is pointing, so a Mac with several screens can pick one.
            FlickAim.current()?.let { (heading, elevation) ->
                put("heading", heading.toDouble())
                put("elevation", elevation.toDouble())
            }
        }
        val msg = JSONObject().apply {
            put("type", "flickStart")
            put("data", data)
        }
        return WebSocketUtil.sendMessage(msg.toString())
    }

    /**
     * Opens [uri] right away (so the share grant can't lapse once the overlay closes), then streams it
     * to the Mac in the background. Suspends only until the stream is open; returns false if it can't be read.
     */
    suspend fun startFileTransfer(context: Context, flickId: String, uri: Uri, name: String, mime: String, tagKey: String = "flickId"): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            var size = querySize(appContext, uri)
            var tempFile: File? = null
            val input: InputStream = (try {
                if (size > 0) {
                    resolver.openInputStream(uri)
                } else {
                    // Unknown length: spool to cache once (constant memory) so the Mac can verify the size.
                    val tmp = File(appContext.cacheDir, "flick_$flickId")
                    resolver.openInputStream(uri)?.use { src -> tmp.outputStream().use { src.copyTo(it, 256 * 1024) } }
                    size = tmp.length()
                    tempFile = tmp
                    tmp.inputStream()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Can't open $uri: ${e.message}")
                null
            }) ?: return@withContext false

            scope.launch {
                val ok = try {
                    input.use { streamFile(flickId, it, name, mime, size, tagKey) }
                } catch (e: Exception) {
                    Log.e(TAG, "Flick transfer failed: ${e.message}")
                    false
                } finally {
                    tempFile?.delete()
                }
                if (!ok) {
                    sendCancel(flickId)
                    toast(appContext, "Flick to Mac: couldn't send $name")
                }
            }
            true
        }

    private suspend fun streamFile(id: String, input: InputStream, name: String, mime: String, size: Long, tagKey: String = "flickId"): Boolean {
        if (!WebSocketUtil.isWifiConnected()) return false
        val acks = Channel<Int>(Channel.UNLIMITED)
        ackChannels[id] = acks
        try {
            val init = JSONObject().apply {
                put("type", "fileTransferInit")
                put("data", JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("size", size)
                    put("mime", mime)
                    put("chunkSize", CHUNK_SIZE)
                    put("isClipboard", false)
                    put(tagKey, id) // "flickId" for Flick to Mac; "searchFetchId" for H.I.B phone search fetches
                })
            }
            if (!WebSocketUtil.sendMessage(init.toString())) return false

            val totalChunks = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
            // index -> (encoded message, attempts, sentAt)
            val inFlight = LinkedHashMap<Int, Triple<String, Int, Long>>()
            val buffer = ByteArray(CHUNK_SIZE)
            var nextIndex = 0
            var acked = 0

            while (acked < totalChunks) {
                // Fill the window.
                while (inFlight.size < WINDOW && nextIndex < totalChunks) {
                    val read = readFully(input, buffer)
                    if (read <= 0) return false
                    val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    val chunk = JSONObject().apply {
                        put("type", "fileChunk")
                        put("data", JSONObject().apply {
                            put("id", id)
                            put("index", nextIndex)
                            put("chunk", b64)
                        })
                    }.toString()
                    if (!WebSocketUtil.isWifiConnected() || !WebSocketUtil.sendMessage(chunk)) return false
                    inFlight[nextIndex] = Triple(chunk, 1, System.currentTimeMillis())
                    nextIndex++
                }

                // Wait for an ack (or time out and retransmit the oldest stragglers).
                val index = withTimeoutOrNull(ACK_TIMEOUT_MS) { acks.receive() }
                if (index != null) {
                    if (inFlight.remove(index) != null) acked++
                    continue
                }
                if (!WebSocketUtil.isWifiConnected()) return false
                val now = System.currentTimeMillis()
                for ((idx, entry) in inFlight.entries.toList()) {
                    if (now - entry.third < ACK_TIMEOUT_MS) continue
                    if (entry.second >= MAX_ATTEMPTS) return false
                    WebSocketUtil.sendMessage(entry.first)
                    inFlight[idx] = Triple(entry.first, entry.second + 1, now)
                }
            }

            val complete = JSONObject().apply {
                put("type", "fileTransferComplete")
                put("data", JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("size", size)
                })
            }
            return WebSocketUtil.sendMessage(complete.toString())
        } finally {
            ackChannels.remove(id)
            acks.close()
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun sendCancel(id: String) {
        try {
            val msg = JSONObject().apply {
                put("type", "fileTransferCancel")
                put("data", JSONObject().put("id", id))
            }
            WebSocketUtil.sendMessage(msg.toString())
        } catch (_: Exception) {
        }
    }

    fun querySize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) {
                        val s = c.getLong(idx)
                        if (s > 0) return s
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (pfd.statSize > 0) return pfd.statSize
            }
        } catch (_: Exception) {
        }
        return -1
    }

    private fun toast(context: Context, text: String) {
        mainHandler.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}
