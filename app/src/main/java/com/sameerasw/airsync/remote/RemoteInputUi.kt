package com.sameerasw.airsync.remote

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.sameerasw.airsync.multidesk.PairedDesktops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Remote's choices (design §6.9, SPACE.md §15.5), kept on the phone; mode and custom shortcuts per computer. */
class RemoteSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hib_remote", Context.MODE_PRIVATE)

    enum class Quality(val label: String) { AUTO("Auto"), SHARPER("Sharper"), SMOOTHER("Smoother") }

    /** SPACE.md §15.5: Touchpad (default) or Direct. */
    enum class ControlMode { TOUCHPAD, DIRECT }

    var pictureOn by mutableStateOf(prefs.getBoolean("picture_on", true))
        private set
    var quality by mutableStateOf(runCatching { Quality.valueOf(prefs.getString("quality", null) ?: "AUTO") }.getOrDefault(Quality.AUTO))
        private set
    var naturalScroll by mutableStateOf(prefs.getBoolean("natural_scroll", true))
        private set
    /** 0.6 … 1.6 × the touchpad's 1:1 base (acceleration on top). */
    var pointerSpeed by mutableFloatStateOf(prefs.getFloat("pointer_speed", 1f))
        private set

    private val modes = mutableStateMapOf<String, ControlMode>()
    private val combos = mutableStateMapOf<String, List<CustomCombo>>()

    fun changePicture(on: Boolean) { pictureOn = on; prefs.edit().putBoolean("picture_on", on).apply() }
    fun changeQuality(q: Quality) { quality = q; prefs.edit().putString("quality", q.name).apply() }
    fun changeNatural(on: Boolean) { naturalScroll = on; prefs.edit().putBoolean("natural_scroll", on).apply() }
    fun changeSpeed(s: Float) { pointerSpeed = s.coerceIn(0.6f, 1.6f); prefs.edit().putFloat("pointer_speed", pointerSpeed).apply() }

    fun mode(computer: String): ControlMode {
        val k = PairedDesktops.normalizeName(computer)
        modes[k]?.let { return it }
        return runCatching { ControlMode.valueOf(prefs.getString("mode_$k", null) ?: "TOUCHPAD") }
            .getOrDefault(ControlMode.TOUCHPAD)
    }

    fun changeMode(computer: String, mode: ControlMode) {
        val k = PairedDesktops.normalizeName(computer)
        modes[k] = mode
        prefs.edit().putString("mode_$k", mode.name).apply()
    }

    fun customCombos(computer: String): List<CustomCombo> {
        val k = PairedDesktops.normalizeName(computer)
        combos[k]?.let { return it }
        return CustomCombo.decode(prefs.getString("combos_$k", null))
    }

    fun changeCustomCombos(computer: String, list: List<CustomCombo>) {
        val k = PairedDesktops.normalizeName(computer)
        combos[k] = list
        prefs.edit().putString("combos_$k", CustomCombo.encode(list)).apply()
    }

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
 * Sends pointer and key traffic to one computer (SPACE.md §15.5): moves, absolute moves and scrolls go out at once
 * when the link is idle and at most every 8 ms (120 Hz) while the finger moves, summed or replaced within a frame
 * ([InputCoalescer]) so nothing stale is ever queued. Clicks, button edges and keys go at once, after any pending
 * move so the order is kept. Main thread only.
 */
class RemoteInputSender(private val scope: CoroutineScope, private val send: (String) -> Unit) {
    private val coalescer = InputCoalescer(FRAME_MS)
    private var job: Job? = null

    private fun now() = SystemClock.uptimeMillis()

    fun move(dx: Float, dy: Float) { if (coalescer.addMove(dx, dy, now())) flush() else schedule() }
    fun moveAbs(x: Float, y: Float, display: String) { if (coalescer.setAbs(x, y, display, now())) flush() else schedule() }
    fun scroll(dx: Float, dy: Float) { if (coalescer.addScroll(dx, dy, now())) flush() else schedule() }

    /** A full click; [around] = sticky-modifier `key` downs before and ups after (⌘-click). */
    fun click(button: String, around: Pair<List<String>, List<String>> = emptyList<String>() to emptyList()) {
        flush()
        around.first.forEach(send)
        send(RemoteInput.button(button, true))
        scope.launch {
            delay(CLICK_MS)
            send(RemoteInput.button(button, false))
            around.second.forEach(send)
        }
    }

    fun button(button: String, down: Boolean) { flush(); send(RemoteInput.button(button, down)) }

    /** Any other message (keys, text), in order after pending moves. */
    fun raw(json: String) { flush(); send(json) }
    fun raw(list: List<String>) { if (list.isEmpty()) return; flush(); list.forEach(send) }

    /** Drops what is pending without sending it (the computer changed). */
    fun discard() {
        job?.cancel()
        job = null
        coalescer.drain(now())
    }

    fun flush() {
        job?.cancel()
        job = null
        coalescer.drain(now()).forEach(send)
    }

    private fun schedule() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(coalescer.waitMs(now()).coerceAtLeast(1L))
            job = null
            coalescer.drain(now()).forEach(send)
        }
    }

    companion object {
        const val FRAME_MS = 8L
        const val CLICK_MS = 30L
    }
}

/**
 * Feeds every pointer event over this element (in dp) to [recogniser] and hands the results to [onAction]. The
 * recogniser's timers (long-press, double-tap-hold) fire while the fingers rest. [onDown] runs as a gesture
 * starts (stops a scroll fling).
 */
@Composable
fun Modifier.padInput(
    key: Any?,
    recogniser: PointerRecogniser,
    onDown: () -> Unit = {},
    onAction: (PadAction) -> Unit
): Modifier {
    val action by rememberUpdatedState(onAction)
    val start by rememberUpdatedState(onDown)
    return padInputRaw(key, recogniser, { start() }, { action(it) })
}

private fun Modifier.padInputRaw(
    key: Any?,
    recogniser: PointerRecogniser,
    onDown: () -> Unit,
    onAction: (PadAction) -> Unit
): Modifier = pointerInput(key, recogniser) {
    val d = density
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        onDown()
        // A gesture cut short last time is closed first.
        recogniser.cancel().forEach(onAction)
        recogniser.onEvent(first.uptimeMillis, listOf(TouchPoint(first.id.value, first.position.x / d, first.position.y / d, true)))
            .forEach(onAction)
        first.consume()
        var lastT = first.uptimeMillis
        while (true) {
            val due = recogniser.nextTimerAt()
            val event = if (due == null) awaitPointerEvent(PointerEventPass.Main)
            else withTimeoutOrNull((due - lastT).coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Main) }
            if (event == null) {
                recogniser.onTimer(due!!).forEach(onAction)
                lastT = due
                continue
            }
            val t = event.changes.maxOfOrNull { it.uptimeMillis } ?: lastT
            lastT = t
            val points = event.changes.map { TouchPoint(it.id.value, it.position.x / d, it.position.y / d, it.pressed) }
            recogniser.onEvent(t, points).forEach(onAction)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}
