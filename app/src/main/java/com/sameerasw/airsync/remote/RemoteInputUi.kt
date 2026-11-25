package com.sameerasw.airsync.remote

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The Remote's ⋯ menu choices (design §6.9), kept on the phone. */
class RemoteSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hib_remote", Context.MODE_PRIVATE)

    enum class Quality(val label: String) { AUTO("Auto"), SHARPER("Sharper"), SMOOTHER("Smoother") }

    var pictureOn by mutableStateOf(prefs.getBoolean("picture_on", true))
        private set
    var quality by mutableStateOf(runCatching { Quality.valueOf(prefs.getString("quality", null) ?: "AUTO") }.getOrDefault(Quality.AUTO))
        private set
    var naturalScroll by mutableStateOf(prefs.getBoolean("natural_scroll", true))
        private set
    /** 0.6 … 1.6 × the trackpad's usual ×1.8. */
    var pointerSpeed by mutableFloatStateOf(prefs.getFloat("pointer_speed", 1f))
        private set

    fun changePicture(on: Boolean) { pictureOn = on; prefs.edit().putBoolean("picture_on", on).apply() }
    fun changeQuality(q: Quality) { quality = q; prefs.edit().putString("quality", q.name).apply() }
    fun changeNatural(on: Boolean) { naturalScroll = on; prefs.edit().putBoolean("natural_scroll", on).apply() }
    fun changeSpeed(s: Float) { pointerSpeed = s.coerceIn(0.6f, 1.6f); prefs.edit().putFloat("pointer_speed", pointerSpeed).apply() }

    /** maxW, maxH, fps for `screenOpen`, from this phone's screen. */
    fun streamSize(context: Context): Triple<Int, Int, Int> {
        val m = context.resources.displayMetrics
        val long = maxOf(m.widthPixels, m.heightPixels).coerceAtLeast(1280)
        val short = minOf(m.widthPixels, m.heightPixels).coerceAtLeast(720)
        return when (quality) {
            Quality.AUTO -> Triple(long, short, 60)
            Quality.SHARPER -> Triple(long, short, 30)
            Quality.SMOOTHER -> Triple(long * 3 / 4, short * 3 / 4, 60)
        }
    }
}

/**
 * Sends the touchpad's actions to one computer: moves and scrolls are summed and sent every 10 ms (100 Hz, as the
 * old trackpad did); clicks and button edges go at once, after any pending move so the order is kept.
 */
class RemoteInputSender(private val scope: CoroutineScope, private val send: (String) -> Unit) {
    private val lock = Any()
    private var mx = 0f
    private var my = 0f
    private var sx = 0f
    private var sy = 0f
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(10)
                flush()
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    fun act(a: RemoteAction) {
        when (a) {
            is RemoteAction.Move -> synchronized(lock) { mx += a.dx; my += a.dy }
            is RemoteAction.Scroll -> synchronized(lock) { sx += a.dx; sy += a.dy }
            is RemoteAction.Click -> {
                flush()
                send(RemoteInput.button(a.button, true))
                scope.launch { delay(50); send(RemoteInput.button(a.button, false)) }
            }
            is RemoteAction.Button -> { flush(); RemoteInput.messages(a).forEach(send) }
            is RemoteAction.Zoom, is RemoteAction.Pan -> {}
        }
    }

    fun flush() {
        val (m, s) = synchronized(lock) {
            val m = if (mx != 0f || my != 0f) RemoteAction.Move(mx, my) else null
            val s = if (sx != 0f || sy != 0f) RemoteAction.Scroll(sx, sy) else null
            mx = 0f; my = 0f; sx = 0f; sy = 0f
            m to s
        }
        m?.let { RemoteInput.messages(it).forEach(send) }
        s?.let { RemoteInput.messages(it).forEach(send) }
    }
}

/** Feeds every pointer event over this element to [gestures] and hands the results to [onAction]. */
fun Modifier.remoteTouchpad(gestures: RemoteGestures, onAction: (RemoteAction) -> Unit): Modifier =
    pointerInput(gestures) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            // A gesture cut short last time (view left mid-drag) is closed first.
            gestures.cancel().forEach(onAction)
            gestures.onEvent(first.uptimeMillis, listOf(TouchPoint(first.id.value, first.position.x, first.position.y, true)))
                .forEach(onAction)
            first.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val t = event.changes.maxOfOrNull { it.uptimeMillis } ?: 0L
                val points = event.changes.map { TouchPoint(it.id.value, it.position.x, it.position.y, it.pressed) }
                gestures.onEvent(t, points).forEach(onAction)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
