package com.sameerasw.airsync.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.utils.CryptoUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phone Remote: the live picture of one computer (SPACE.md §15.3). The screen tells it what it wants
 * ([want]: computer, display, view, quality, and whether the picture is visible at all) and hands it the surface;
 * it opens `/screen` only while all of that holds, reconnects after drops, and closes the moment the picture
 * leaves the screen (backgrounded, page changed, computer switched, picture off). Main thread only.
 */
class RemoteStream(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    enum class Phase { IDLE, CONNECTING, LIVE, PAUSED, CLOSED }

    data class Ui(
        val phase: Phase = Phase.IDLE,
        /** For [Phase.PAUSED]: locked | macPermission | stopped. */
        val pausedReason: String? = null,
        /** For [Phase.CLOSED]: the close code ([ScreenStreamClient.CODE_UNREACHABLE] etc.). */
        val closeCode: Int = 0,
        val route: String = ScreenStreamClient.ROUTE_LAN,
        val rttMs: Int = 0,
        val fps: Int = 0,
        val w: Int = 0,
        val h: Int = 0,
        val codec: String = CODEC_H264,
        val display: String = "",
        /** A picture has been drawn since the last (re)open. */
        val hasPicture: Boolean = false
    ) {
        val relayed: Boolean get() = route == ScreenStreamClient.ROUTE_TAILSCALE && rttMs > RELAYED_RTT_MS
    }

    data class Cursor(val x: Float = 0f, val y: Float = 0f, val visible: Boolean = false)

    data class Want(
        val computer: String,
        val display: String,
        val maxW: Int,
        val maxH: Int,
        val fps: Int
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _cursor = MutableStateFlow(Cursor())
    val cursor: StateFlow<Cursor> = _cursor.asStateFlow()

    private var surface: Surface? = null
    private var want: Want? = null
    private var view: ViewRect = ViewRect.FULL
    private var codec = CODEC_H264
    /** The computer [codec] was learned for (a JPEG fallback sticks to that computer). */
    private var codecFor: String? = null
    private var client: ScreenStreamClient? = null
    private var decoder: H264Decoder? = null
    private var retry: Runnable? = null
    private var retryDelay = RETRY_MIN_MS
    private var refusedUntil = 0L
    private var picturesSinceOpen = 0

    fun setSurface(s: Surface?) {
        if (s === surface) return
        surface = s
        restart()
    }

    /** Null: no picture wanted (off, page hidden, phone backgrounded, computer locked or not connected). */
    fun want(w: Want?) {
        if (w == want) return
        val prev = want
        want = w
        // Only the display changed on a live session: switch in place (new screenOpened + keyframe).
        if (w != null && prev != null && client != null && w.copy(display = prev.display) == prev && _ui.value.phase == Phase.LIVE) {
            client?.sendJson(RemoteJson.screenDisplay(w.display))
            decoder?.resync()
            return
        }
        // The view follows the screen's zoom ([setView]); the screen resets it for a new computer or display.
        if (w != null && codecFor?.let { RemoteHub.key(it) } != RemoteHub.key(w.computer)) {
            codec = CODEC_H264
            codecFor = w.computer
        }
        restart()
    }

    /** The zoomed region changed (the caller throttles). */
    fun setView(v: ViewRect) {
        if (v == view) return
        view = v
        client?.sendJson(RemoteJson.screenView(v))
    }

    fun requestKeyframe() {
        client?.sendJson(RemoteJson.screenKeyframe())
    }

    /** "Try again" on a refused / stopped card. */
    fun retryNow() {
        refusedUntil = 0L
        retryDelay = RETRY_MIN_MS
        restart()
    }

    fun dispose() {
        want = null
        close()
    }

    private fun restart() {
        close()
        val w = want ?: run { _ui.value = Ui(); return }
        val s = surface ?: return
        if (System.currentTimeMillis() < refusedUntil) return
        val ep = DesktopHub.linkEndpoint(w.computer)
        if (ep == null) {
            _ui.value = Ui(phase = Phase.CLOSED, closeCode = ScreenStreamClient.CODE_UNREACHABLE)
            return
        }
        val key = runCatching { CryptoUtil.decodeKey(ep.key) }.getOrNull() ?: return
        val dials = TailscaleAddrs.plan(listOfNotNull(ep.ip), RemoteAddrStore.get(app, w.computer))
        picturesSinceOpen = 0
        _ui.value = Ui(phase = Phase.CONNECTING, display = w.display, codec = codec)
        val dec = H264Decoder(s, decoderListener)
        decoder = dec
        val c = ScreenStreamClient(
            httpClient, dials, ep.port, key,
            ScreenStreamClient.OpenParams(w.display, w.maxW, w.maxH, w.fps, codec, view),
            streamListener(dec)
        )
        c.decodeMsProvider = { dec.decodeMs }
        client = c
        c.start()
    }

    private fun close() {
        retry?.let { main.removeCallbacks(it) }
        retry = null
        client?.stop()
        client = null
        decoder?.release()
        decoder = null
    }

    private fun scheduleRetry(ms: Long) {
        retry?.let { main.removeCallbacks(it) }
        val r = Runnable { retry = null; if (want != null) restart() }
        retry = r
        main.postDelayed(r, ms)
    }

    private val decoderListener = object : H264Decoder.Listener {
        override fun onNeedKeyframe() { main.post { requestKeyframe() } }

        override fun onUnsupported() {
            main.post {
                if (codec == CODEC_JPEG) return@post
                Log.w(TAG, "Falling back to JPEG pictures")
                codec = CODEC_JPEG
                restart()
            }
        }

        override fun onRendered() {
            client?.noteRendered()
            if (picturesSinceOpen++ == 0) main.post { _ui.update { it.copy(hasPicture = true) } }
        }
    }

    private fun streamListener(dec: H264Decoder) = object : ScreenStreamClient.Listener {
        override fun onOpened(opened: RemoteJson.Opened, route: String) {
            dec.setSize(opened.w, opened.h)
            main.post {
                if (decoder !== dec) return@post
                retryDelay = RETRY_MIN_MS
                _ui.update {
                    it.copy(phase = Phase.LIVE, pausedReason = null, route = route, w = opened.w, h = opened.h,
                        codec = opened.codec, display = opened.display)
                }
            }
        }

        override fun onFrame(frame: ScreenFrame) {
            if (frame.kind != ScreenFrame.KIND_CONFIG) {
                val c = Cursor(frame.cursorX, frame.cursorY, frame.cursorVisible)
                if (c != _cursor.value) _cursor.value = c
            }
            dec.submit(frame)
        }

        override fun onPaused(reason: String) {
            main.post {
            if (decoder !== dec) return@post
            _ui.update { it.copy(phase = Phase.PAUSED, pausedReason = reason) }
            }
        }

        override fun onResumed() {
            main.post {
            if (decoder !== dec) return@post
            dec.resync()
            _ui.update { it.copy(phase = Phase.LIVE, pausedReason = null) }
            }
        }

        override fun onClosed(code: Int, reason: String) {
            main.post {
            if (decoder !== dec) return@post
            close()
            _ui.update { it.copy(phase = Phase.CLOSED, closeCode = code, hasPicture = false) }
            when (code) {
                ScreenStreamClient.CODE_BAD_KEY -> {}
                ScreenStreamClient.CODE_STOPPED -> refusedUntil = System.currentTimeMillis() + STOPPED_REFUSAL_MS
                ScreenStreamClient.CODE_NOT_PERMITTED, ScreenStreamClient.CODE_BUSY -> scheduleRetry(BUSY_RETRY_MS)
                else -> {
                    scheduleRetry(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX_MS)
                }
            }
            }
        }

        override fun onRtt(rttMs: Int) {
            val fps = client?.lastFps ?: 0
            main.post { _ui.update { it.copy(rttMs = rttMs, fps = fps) } }
        }
    }

    companion object {
        private const val TAG = "RemoteStream"
        const val CODEC_H264 = "h264"
        const val CODEC_JPEG = "jpeg"
        const val RELAYED_RTT_MS = 80
        private const val RETRY_MIN_MS = 1_500L
        private const val RETRY_MAX_MS = 10_000L
        private const val BUSY_RETRY_MS = 10_000L
        private const val STOPPED_REFUSAL_MS = 60_000L

        private val httpClient by lazy { ScreenStreamClient.newClient() }
    }
}
