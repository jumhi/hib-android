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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * H.I.B — Flick: the wire side (the computer and screen it goes to are picked on the Flick screen).
 *
 * 1. [sendFlickStart] fires the instant a card is thrown, so the computer can launch its card before a
 *    single byte of the file has moved.
 * 2. [startFileTransfer] then streams the file with the existing file-transfer protocol
 *    (fileTransferInit → fileChunk… with fileChunkAck flow control → fileTransferComplete), tagging the
 *    init with `flickId` so the computer can drive the flying card's progress ring.
 *
 * Files are streamed in 64 KB chunks with a bounded in-flight window, so memory stays flat for any size.
 *
 * Bundles (SPACE.md §14): every item's `flickStart` goes out at the throw (tagged `bundle`), then the files
 * wait in one queue and stream [MAX_PARALLEL] at a time. Streaming them all at once over the one socket
 * is what broke multi-sends: a dozen windows of chunks queued behind each other, acks came back later than
 * the fixed 2.5 s retransmit timer, every stream re-sent its whole window, and the socket's 16 MB send
 * queue overflowed (OkHttp then closes the link) — so nothing arrived. Now a chunk is only re-sent when its
 * stream has had no ack at all for [STALL_MS] (the link is TCP; a slow ack is not a lost chunk).
 */
object FlickSender {
    private const val TAG = "FlickSender"
    private const val CHUNK_SIZE = 64 * 1024
    private const val WINDOW = 12
    private const val ACK_TIMEOUT_MS = 2500L
    /** No ack at all for this long → re-send the oldest unacked chunks. */
    private const val STALL_MS = 8000L
    private const val MAX_ATTEMPTS = 4
    /** Files streamed at the same time; the rest of a bundle waits its turn. */
    private const val MAX_PARALLEL = 2
    private const val KEEP_FINISHED_MS = 2 * 60 * 1000L
    const val MAX_TEXT_CHARS = FlickWire.MAX_TEXT_CHARS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ackChannels = ConcurrentHashMap<String, Channel<Int>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val queue = Semaphore(MAX_PARALLEL)

    private val _transfers = MutableStateFlow<Map<String, FlickTransfer>>(emptyMap())
    /** Every file stream Flick started recently (by flick id): queued, sending (bytes acked), done or failed. */
    val transfers: StateFlow<Map<String, FlickTransfer>> = _transfers.asStateFlow()

    private fun setTransfer(id: String, change: (FlickTransfer?) -> FlickTransfer?) {
        _transfers.update { map ->
            val now = System.currentTimeMillis()
            val next = change(map[id])
            val kept = map.filterValues { !it.finished || now - it.at < KEEP_FINISHED_MS }.toMutableMap()
            if (next == null) kept.remove(id) else kept[id] = next.copy(at = now)
            kept
        }
    }

    private val _landed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Emits a flick id when the computer reports that card has landed (`flickLanded`). */
    val landed: SharedFlow<String> = _landed

    /**
     * H.I.B multi-desktop: which computer Flick sends to (desktop name). Null = the target rule's pick
     * (the computer in use, else the last one used, else the main one).
     */
    @Volatile var target: String? = null

    fun canSend(): Boolean = WebSocketUtil.isWifiConnected() ||
        com.sameerasw.airsync.multidesk.DesktopHub.anySecondaryConnected()

    private fun targetReady(to: String?): Boolean {
        val link = com.sameerasw.airsync.multidesk.DesktopHub.findLink(to)
        return link?.isReady() ?: canSend()
    }

    private fun send(to: String?, json: String): Boolean {
        if (to == null) {
            // No explicit target: the target rule (in use > last used > primary).
            val pick = com.sameerasw.airsync.multidesk.DesktopHub.targetPick()?.id
            val link = com.sameerasw.airsync.multidesk.DesktopHub.findLink(pick)
                ?: com.sameerasw.airsync.multidesk.DesktopHub.connectedLinks().firstOrNull()
            return link?.send(json) ?: WebSocketUtil.sendPrimaryOrBle(json)
        }
        return com.sameerasw.airsync.multidesk.DesktopHub.sendTo(to, json)
    }

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
        text: String?,
        to: String? = target,
        /** The screen the phone points at (SPACE.md §7.4); null or "" lets the computer choose. */
        screen: String? = null,
        /** Set for every item of a bundle thrown together (SPACE.md §14). */
        bundle: FlickBundle? = null
    ): Boolean {
        val msg = FlickWire.flickStart(
            id = id, kind = kind, name = name, mime = mime, size = size,
            exitXRatio = exitXRatio, velocity = velocity, angle = angle,
            thumbnailBase64 = thumbnailBase64, text = text, screen = screen,
            aim = FlickAim.current(), bundle = bundle
        )
        return send(to, msg)
    }

    /**
     * Opens [uri] right away (so the share grant can't lapse once the overlay closes), then queues it and
     * streams it to the computer in the background. Suspends only until the stream is open; returns false
     * if it can't be read. Progress shows in [transfers].
     */
    suspend fun startFileTransfer(context: Context, flickId: String, uri: Uri, name: String, mime: String, tagKey: String = "flickId", to: String? = target): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            var size = querySize(appContext, uri)
            var tempFile: File? = null
            val input: InputStream = (try {
                if (size > 0) {
                    resolver.openInputStream(uri)
                } else {
                    // Unknown length: spool to cache once (constant memory) so the computer can verify the size.
                    val tmp = File(appContext.cacheDir, "flick_$flickId")
                    resolver.openInputStream(uri)?.use { src -> tmp.outputStream().use { src.copyTo(it, 256 * 1024) } }
                    size = tmp.length()
                    tempFile = tmp
                    tmp.inputStream()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Can't open $uri: ${e.message}")
                null
            }) ?: run {
                setTransfer(flickId) { FlickTransfer(flickId, size, state = FlickTransfer.State.FAILED) }
                return@withContext false
            }

            setTransfer(flickId) { FlickTransfer(flickId, size, state = FlickTransfer.State.QUEUED) }
            scope.launch {
                val ok = try {
                    queue.withPermit {
                        setTransfer(flickId) { FlickTransfer(flickId, size, state = FlickTransfer.State.SENDING) }
                        input.use { streamFile(flickId, it, name, mime, size, tagKey, to) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Flick transfer failed: ${e.message}")
                    false
                } finally {
                    runCatching { input.close() }
                    tempFile?.delete()
                }
                setTransfer(flickId) {
                    (it ?: FlickTransfer(flickId, size)).copy(
                        sent = if (ok) size else it?.sent ?: 0L,
                        state = if (ok) FlickTransfer.State.DONE else FlickTransfer.State.FAILED
                    )
                }
                if (!ok) {
                    sendCancel(flickId, to)
                    toast(appContext, "Flick: couldn't send $name" + (to?.let { " to $it" } ?: ""))
                }
            }
            true
        }

    private suspend fun streamFile(id: String, input: InputStream, name: String, mime: String, size: Long, tagKey: String = "flickId", to: String? = null): Boolean {
        if (!targetReady(to)) return false
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
                    put(tagKey, id) // "flickId" for Flick; "searchFetchId" for H.I.B phone search fetches
                })
            }
            if (!send(to, init.toString())) return false

            val totalChunks = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
            // index -> (encoded message, attempts, sentAt)
            val inFlight = LinkedHashMap<Int, Triple<String, Int, Long>>()
            val buffer = ByteArray(CHUNK_SIZE)
            var nextIndex = 0
            var acked = 0
            var lastAckAt = System.currentTimeMillis()
            var lastReported = -1

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
                    if (!targetReady(to) || !send(to, chunk)) return false
                    inFlight[nextIndex] = Triple(chunk, 1, System.currentTimeMillis())
                    nextIndex++
                }

                // Wait for an ack (or time out and retransmit the oldest stragglers).
                val index = withTimeoutOrNull(ACK_TIMEOUT_MS) { acks.receive() }
                if (index != null) {
                    lastAckAt = System.currentTimeMillis()
                    if (inFlight.remove(index) != null) {
                        acked++
                        // Progress for the Flick screen, in whole-percent steps.
                        val pct = (acked * 100L / totalChunks.coerceAtLeast(1)).toInt()
                        if (pct != lastReported) {
                            lastReported = pct
                            val sentBytes = (acked.toLong() * CHUNK_SIZE).coerceAtMost(size)
                            setTransfer(id) { (it ?: FlickTransfer(id, size)).copy(sent = sentBytes, state = FlickTransfer.State.SENDING) }
                        }
                    }
                    continue
                }
                if (!targetReady(to)) return false
                // Acks are slow but still coming: the link is busy, not lossy (it's TCP). Only a stream
                // that has heard nothing for STALL_MS re-sends its oldest chunks.
                val now = System.currentTimeMillis()
                if (now - lastAckAt < STALL_MS) continue
                for ((idx, entry) in inFlight.entries.toList()) {
                    if (now - entry.third < STALL_MS) continue
                    if (entry.second >= MAX_ATTEMPTS) return false
                    send(to, entry.first)
                    inFlight[idx] = Triple(entry.first, entry.second + 1, now)
                }
                lastAckAt = now
            }

            val complete = JSONObject().apply {
                put("type", "fileTransferComplete")
                put("data", JSONObject().apply {
                    put("id", id)
                    put("name", name)
                    put("size", size)
                })
            }
            return send(to, complete.toString())
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

    private fun sendCancel(id: String, to: String? = null) {
        try {
            val msg = JSONObject().apply {
                put("type", "fileTransferCancel")
                put("data", JSONObject().put("id", id))
            }
            send(to, msg.toString())
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
