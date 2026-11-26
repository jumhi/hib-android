package com.sameerasw.airsync.remote

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phone Remote (SPACE.md §15.3): turns the `/screen` frames into pictures on the current surface ([setSurface]).
 *  - H.264: hardware MediaCodec in async mode on its own thread, rendering each output as soon as it is ready
 *    (no presentation-time pacing: lowest latency). A config frame (SPS+PPS) (re)configures the decoder; until a
 *    keyframe arrives after a (re)start or a drop, delta frames are skipped.
 *  - JPEG fallback (kind 4): decoded with BitmapFactory and drawn into the same surface.
 * When the phone falls behind it drops what is queued and asks for a keyframe ([Listener.onNeedKeyframe]).
 * The surface can change under a running session (a new picture view): the codec restarts on the new one from the
 * last config and waits for a keyframe; with no surface, frames are skipped (the config is kept).
 */
class H264Decoder(private val listener: Listener) {

    interface Listener {
        /** The decoder lost sync (dropped frames or an error): ask the computer for a keyframe. */
        fun onNeedKeyframe()
        /** H.264 can't be decoded on this phone: reopen with `codec:"jpeg"`. */
        fun onUnsupported()
        /** A picture reached the screen. */
        fun onRendered()
    }

    private val thread = HandlerThread("hib-remote-decode").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    /** Decoder thread only. */
    private var surface: Surface? = null
    private var config: ByteArray? = null
    private var width = 1920
    private var height = 1080
    private var waitingForKey = true
    private var failures = 0
    private var released = false
    private val pending = ArrayDeque<ScreenFrame>()
    private val freeInputs = ArrayDeque<Int>()
    private val queuedAt = HashMap<Long, Long>()

    /** Average decode time of recent frames, in ms (for `screenStats`). */
    @Volatile var decodeMs: Int = 0
        private set

    /** The encoded size from `screenOpened` (the decoder needs a size to start; the stream's SPS wins). */
    fun setSize(w: Int, h: Int) = handler.post {
        if (w > 0 && h > 0) { width = w; height = h }
    }

    /**
     * Draw into [s] from now on (main thread). Null blocks up to [DETACH_WAIT_MS] until the codec has let go of the
     * old surface, so the view can release it right after. A new surface restarts the codec from the last config;
     * the caller asks the computer for a keyframe.
     */
    fun setSurface(s: Surface?) {
        if (s == null) {
            val done = CountDownLatch(1)
            val posted = handler.post {
                surface = null
                stopCodec()
                pending.clear()
                waitingForKey = true
                done.countDown()
            }
            if (posted) runCatching { done.await(DETACH_WAIT_MS, TimeUnit.MILLISECONDS) }
            return
        }
        handler.post {
            if (released || s === surface) return@post
            surface = s
            waitingForKey = true
            pending.clear()
            if (config != null) startCodec() else stopCodec()
        }
    }

    /** Any thread. */
    fun submit(frame: ScreenFrame) {
        handler.post { if (!released) accept(frame) }
    }

    /** Forget the stream (new display / resumed after a pause): wait for config + keyframe again. */
    fun resync() = handler.post {
        pending.clear()
        waitingForKey = true
    }

    fun release() {
        handler.post {
            released = true
            stopCodec()
            pending.clear()
            thread.quitSafely()
        }
    }

    private fun accept(f: ScreenFrame) {
        when (f.kind) {
            ScreenFrame.KIND_JPEG -> drawJpeg(f.payload)
            ScreenFrame.KIND_CONFIG -> {
                if (codec == null || config?.contentEquals(f.payload) != true) {
                    config = f.payload
                    startCodec()
                }
                waitingForKey = true
            }
            ScreenFrame.KIND_KEYFRAME -> {
                // No surface (between views): skip; the next surface asks for its own keyframe.
                if (codec == null) { if (surface != null) listener.onNeedKeyframe(); return }
                // A keyframe makes everything queued before it useless.
                pending.removeAll { it.kind == ScreenFrame.KIND_FRAME }
                waitingForKey = false
                enqueue(f)
            }
            ScreenFrame.KIND_FRAME -> {
                if (waitingForKey || codec == null) return
                enqueue(f)
            }
        }
    }

    private fun enqueue(f: ScreenFrame) {
        pending.addLast(f)
        if (pending.size > MAX_PENDING) {
            // Behind: drop what waits and start again from a keyframe (newest wins).
            pending.removeAll { it.kind != ScreenFrame.KIND_CONFIG }
            waitingForKey = true
            listener.onNeedKeyframe()
            return
        }
        pump()
    }

    private fun pump() {
        val c = codec ?: return
        while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
            val f = pending.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val buf = c.getInputBuffer(index) ?: continue
                buf.clear()
                if (f.payload.size > buf.capacity()) {
                    c.queueInputBuffer(index, 0, 0, f.ptsMicros, 0)
                    waitingForKey = true
                    listener.onNeedKeyframe()
                    continue
                }
                buf.put(f.payload)
                val flags = when (f.kind) {
                    ScreenFrame.KIND_KEYFRAME -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    ScreenFrame.KIND_CONFIG -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    else -> 0
                }
                if (f.kind != ScreenFrame.KIND_CONFIG) queuedAt[f.ptsMicros] = SystemClock.elapsedRealtimeNanos()
                if (queuedAt.size > 64) queuedAt.keys.take(32).forEach { queuedAt.remove(it) }
                c.queueInputBuffer(index, 0, f.payload.size, f.ptsMicros, flags)
            } catch (e: Exception) {
                Log.w(TAG, "queue failed: ${e.javaClass.simpleName}")
                restartAfterError()
                return
            }
        }
    }

    private fun startCodec() {
        stopCodec()
        val cfg = config ?: return
        val out = surface ?: return
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        val (sps, pps) = splitConfig(cfg)
        val inlineConfig = sps == null || pps == null
        if (!inlineConfig) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps!!))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps!!))
        }
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
        // Frames of the old stream are no use to the new decoder.
        pending.clear()
        var created: MediaCodec? = null
        try {
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            val c = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MIME)
            created = c
            c.setCallback(callback, handler)
            try {
                c.configure(format, out, null, 0)
            } catch (e: Exception) {
                // Some decoders reject the low-latency keys: try once without them.
                format.removeKey(MediaFormat.KEY_LOW_LATENCY)
                format.removeKey(MediaFormat.KEY_PRIORITY)
                c.reset()
                c.setCallback(callback, handler)
                c.configure(format, out, null, 0)
            }
            c.start()
            codec = c
            if (inlineConfig) {
                pending.addFirst(ScreenFrame(ScreenFrame.KIND_CONFIG, 0, 0, 0f, 0f, false, cfg))
            }
            failures = 0
        } catch (e: Exception) {
            Log.w(TAG, "H.264 decoder unavailable: ${e.javaClass.simpleName}")
            if (codec == null) runCatching { created?.release() }
            stopCodec()
            listener.onUnsupported()
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        freeInputs.clear()
        queuedAt.clear()
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun restartAfterError() {
        // Errors while no surface is bound are the old view going away, not the phone's decoder.
        if (surface == null) { stopCodec(); return }
        failures++
        if (failures > 3) {
            stopCodec()
            listener.onUnsupported()
            return
        }
        startCodec()
        waitingForKey = true
        listener.onNeedKeyframe()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            freeInputs.addLast(index)
            pump()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            val render = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
            runCatching { c.releaseOutputBuffer(index, render) }
            if (render) {
                queuedAt.remove(info.presentationTimeUs)?.let { t ->
                    val ms = ((SystemClock.elapsedRealtimeNanos() - t) / 1_000_000L).toInt()
                    decodeMs = (decodeMs * 7 + ms) / 8
                }
                listener.onRendered()
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec) return
            Log.w(TAG, "decoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})")
            if (e.isTransient) return
            restartAfterError()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
    }

    private fun drawJpeg(bytes: ByteArray) {
        if (codec != null) stopCodec()
        val out = surface ?: return
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return
        try {
            val canvas = out.lockHardwareCanvas()
            try {
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), null)
            } finally {
                out.unlockCanvasAndPost(canvas)
            }
            listener.onRendered()
        } catch (e: Exception) {
            Log.w(TAG, "JPEG draw failed: ${e.javaClass.simpleName}")
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        private const val TAG = "RemoteDecoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val MAX_PENDING = 6
        private const val DETACH_WAIT_MS = 300L

        /** Splits an Annex-B config into SPS and PPS (each with its start code). Nulls when either is missing. */
        fun splitConfig(annexB: ByteArray): Pair<ByteArray?, ByteArray?> {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            AnnexB.units(annexB).forEach { (start, end) ->
                // `start` points at the start code; the NAL header follows it.
                val headerAt = start + AnnexB.startCodeLength(annexB, start)
                if (headerAt >= end) return@forEach
                when (annexB[headerAt].toInt() and 0x1F) {
                    7 -> if (sps == null) sps = annexB.copyOfRange(start, end)
                    8 -> if (pps == null) pps = annexB.copyOfRange(start, end)
                }
            }
            return sps to pps
        }
    }
}

/** Annex-B NAL unit boundaries (pure; tested on the JVM). */
object AnnexB {
    /** Each unit as [start, end), where start is the index of its start code. */
    fun units(b: ByteArray): List<Pair<Int, Int>> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                if (b[i + 2].toInt() == 1) { starts += i; i += 3; continue }
                if (i + 4 <= b.size && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) { starts += i; i += 4; continue }
            }
            i++
        }
        return starts.mapIndexed { k, s -> s to (if (k + 1 < starts.size) starts[k + 1] else b.size) }
    }

    fun startCodeLength(b: ByteArray, at: Int): Int =
        if (at + 3 < b.size && b[at + 2].toInt() == 0 && b[at + 3].toInt() == 1) 4 else 3
}
