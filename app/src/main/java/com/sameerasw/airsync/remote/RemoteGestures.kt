package com.sameerasw.airsync.remote

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One finger in a touch event, in picture pixels. */
data class TouchPoint(val id: Long, val x: Float, val y: Float, val pressed: Boolean)

/** What a gesture over the picture does (design §5 gestures table). */
sealed class RemoteAction {
    /** Relative cursor move (computer pixels-ish, like the old trackpad). */
    data class Move(val dx: Float, val dy: Float) : RemoteAction()
    /** A full click at the cursor (down, then up shortly after). */
    data class Click(val button: String) : RemoteAction()
    /** One button edge (drags: left down … moves … left up). */
    data class Button(val button: String, val down: Boolean) : RemoteAction()
    data class Scroll(val dx: Float, val dy: Float) : RemoteAction()
    /** Pinch: zoom the phone's view by [factor] around ([cx], [cy]) picture pixels. Never sent to the computer. */
    data class Zoom(val factor: Float, val cx: Float, val cy: Float) : RemoteAction()
    /** Three-finger slide: pan the zoomed view by picture pixels. Never sent to the computer. */
    data class Pan(val dx: Float, val dy: Float) : RemoteAction()
}

/**
 * The picture is the touchpad (design §5). Feed it every pointer event ([onEvent]); it returns what to do.
 *
 * | Gesture | Does |
 * |---|---|
 * | one finger slide | [RemoteAction.Move] (×[speed], gentle acceleration) |
 * | tap | left [RemoteAction.Click] |
 * | two-finger tap | right [RemoteAction.Click] |
 * | two-finger slide | [RemoteAction.Scroll] (sign follows [naturalScroll]) |
 * | double-tap and hold, then slide | left [RemoteAction.Button] down, moves, up on lift |
 * | pinch | [RemoteAction.Zoom] |
 * | three-finger slide | [RemoteAction.Pan] |
 *
 * Pure (no Android types) so the mapping table is unit-tested on the JVM.
 */
class RemoteGestures(
    var speed: Float = 1.8f,
    var naturalScroll: Boolean = true
) {
    private enum class Mode { NONE, MOVE, PENDING_DRAG, DRAG, SCROLL, PINCH, PAN }

    private var active = false
    /** The left button is down on the computer (a drag). */
    private var held = false
    private var mode = Mode.NONE
    private var startT = 0L
    private var maxPointers = 0
    private var moved = 0f
    private var lastCount = 0
    private var prev: Map<Long, Pair<Float, Float>> = emptyMap()
    private var startSpan = 0f
    private var prevSpan = 0f
    private var twoTravel = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f

    // The last one-finger tap's end, for double-tap-and-hold drags.
    private var lastTapT = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** True while a drag holds the left button (the caller releases it if the view goes away). */
    val isDragging: Boolean get() = held

    fun onEvent(t: Long, pointers: List<TouchPoint>): List<RemoteAction> {
        val down = pointers.filter { it.pressed }
        val out = mutableListOf<RemoteAction>()
        if (!active) {
            if (down.isEmpty()) return out
            begin(t, down)
            return out
        }
        if (down.isEmpty()) {
            finish(t, out)
            return out
        }
        if (down.size != lastCount) {
            // A finger joined or left: re-base so the centroid doesn't jump.
            maxPointers = max(maxPointers, down.size)
            lastCount = down.size
            prev = down.associate { it.id to (it.x to it.y) }
            if (down.size == 2) { startSpan = span(down); prevSpan = startSpan; twoTravel = 0f }
            if (down.size >= 2 && (mode == Mode.MOVE || mode == Mode.PENDING_DRAG || mode == Mode.NONE)) mode = Mode.NONE
            return out
        }
        val (dx, dy) = centroidDelta(down)
        prev = down.associate { it.id to (it.x to it.y) }
        val dist = hypot(dx, dy)
        when {
            down.size == 1 && maxPointers == 1 -> oneFinger(dx, dy, dist, out)
            down.size == 2 -> twoFingers(down, dx, dy, dist, out)
            down.size >= 3 -> {
                mode = Mode.PAN
                moved += dist
                if (dist > 0f) out += RemoteAction.Pan(dx, dy)
            }
            // One finger left over from a two-finger gesture: ignored until all lift.
        }
        return out
    }

    /** The view went away mid-gesture: release a held button, forget the gesture. */
    fun cancel(): List<RemoteAction> {
        val out = mutableListOf<RemoteAction>()
        if (held) out += RemoteAction.Button(LEFT, false)
        held = false
        active = false
        mode = Mode.NONE
        return out
    }

    private fun begin(t: Long, down: List<TouchPoint>) {
        active = true
        startT = t
        maxPointers = down.size
        lastCount = down.size
        moved = 0f
        pendingDx = 0f; pendingDy = 0f
        prev = down.associate { it.id to (it.x to it.y) }
        mode = Mode.NONE
        if (down.size == 1) {
            val p = down[0]
            val nearLastTap = hypot(p.x - lastTapX, p.y - lastTapY) <= DOUBLE_TAP_SLOP_PX
            mode = if (t - lastTapT <= DOUBLE_TAP_MS && nearLastTap) Mode.PENDING_DRAG else Mode.MOVE
        } else if (down.size == 2) {
            startSpan = span(down); prevSpan = startSpan; twoTravel = 0f
        }
    }

    private fun oneFinger(dx: Float, dy: Float, dist: Float, out: MutableList<RemoteAction>) {
        moved += dist
        when (mode) {
            Mode.PENDING_DRAG -> {
                pendingDx += dx; pendingDy += dy
                if (moved > TAP_SLOP_PX) {
                    mode = Mode.DRAG
                    held = true
                    out += RemoteAction.Button(LEFT, true)
                    out += move(pendingDx, pendingDy)
                    pendingDx = 0f; pendingDy = 0f
                }
            }
            Mode.MOVE, Mode.DRAG -> if (dist > 0.1f) out += move(dx, dy)
            else -> {}
        }
    }

    private fun twoFingers(down: List<TouchPoint>, dx: Float, dy: Float, dist: Float, out: MutableList<RemoteAction>) {
        moved += dist
        twoTravel += dist
        val s = span(down)
        if (mode != Mode.SCROLL && mode != Mode.PINCH) {
            mode = when {
                abs(s - startSpan) > PINCH_SLOP_PX && abs(s - startSpan) > twoTravel -> Mode.PINCH
                twoTravel > SCROLL_SLOP_PX -> Mode.SCROLL
                else -> mode
            }
            if (mode == Mode.PINCH) prevSpan = startSpan
        }
        when (mode) {
            Mode.SCROLL -> if (dist > 0.5f) {
                val sign = if (naturalScroll) 1f else -1f
                out += RemoteAction.Scroll(dx * SCROLL_GAIN * sign, dy * SCROLL_GAIN * sign)
            }
            Mode.PINCH -> {
                if (prevSpan > 1f && s > 1f && s != prevSpan) {
                    val (cx, cy) = centroid(down)
                    out += RemoteAction.Zoom(s / prevSpan, cx, cy)
                }
                prevSpan = s
            }
            else -> {}
        }
    }

    private fun finish(t: Long, out: MutableList<RemoteAction>) {
        val duration = t - startT
        when {
            held -> out += RemoteAction.Button(LEFT, false)
            maxPointers == 1 && moved < TAP_SLOP_PX -> {
                out += RemoteAction.Click(LEFT)
                // A double-tap's second tap doesn't start a third (a triple tap is two clicks + one).
                if (mode == Mode.PENDING_DRAG) lastTapT = Long.MIN_VALUE / 2
                else { lastTapT = t; prev.values.firstOrNull()?.let { (x, y) -> lastTapX = x; lastTapY = y } }
            }
            maxPointers == 2 && mode != Mode.PINCH && mode != Mode.SCROLL && moved < TWO_TAP_SLOP_PX &&
                duration <= TWO_TAP_MAX_MS -> out += RemoteAction.Click(RIGHT)
        }
        held = false
        active = false
        mode = Mode.NONE
    }

    private fun move(dx: Float, dy: Float): RemoteAction.Move {
        val d = hypot(dx, dy)
        val accel = 1f + min(max((d - ACCEL_FROM_PX) / ACCEL_SPAN_PX, 0f), ACCEL_MAX)
        return RemoteAction.Move(dx * speed * accel, dy * speed * accel)
    }

    private fun centroidDelta(down: List<TouchPoint>): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        down.forEach { p ->
            val q = prev[p.id] ?: return@forEach
            sx += p.x - q.first; sy += p.y - q.second; n++
        }
        return if (n == 0) 0f to 0f else (sx / n) to (sy / n)
    }

    private fun centroid(down: List<TouchPoint>): Pair<Float, Float> =
        (down.sumOf { it.x.toDouble() } / down.size).toFloat() to (down.sumOf { it.y.toDouble() } / down.size).toFloat()

    private fun span(down: List<TouchPoint>): Float =
        if (down.size < 2) 0f else hypot(down[0].x - down[1].x, down[0].y - down[1].y)

    companion object {
        const val LEFT = "left"
        const val RIGHT = "right"
        const val TAP_SLOP_PX = 10f
        const val TWO_TAP_SLOP_PX = 30f
        const val TWO_TAP_MAX_MS = 400L
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_SLOP_PX = 60f
        const val SCROLL_SLOP_PX = 12f
        const val PINCH_SLOP_PX = 24f
        const val SCROLL_GAIN = 2f
        const val ACCEL_FROM_PX = 6f
        const val ACCEL_SPAN_PX = 40f
        const val ACCEL_MAX = 0.6f
    }
}

/** Turns [RemoteAction]s into `remoteControl` messages (SPACE.md §15.2). Zoom and pan stay on the phone. */
object RemoteInput {
    fun messages(action: RemoteAction): List<String> = when (action) {
        is RemoteAction.Move -> listOf(RemoteJson.remoteControl("mouse_move") {
            addProperty("dx", action.dx.toDouble()); addProperty("dy", action.dy.toDouble())
        })
        is RemoteAction.Scroll -> listOf(RemoteJson.remoteControl("mouse_scroll") {
            addProperty("dx", action.dx.toDouble()); addProperty("dy", action.dy.toDouble())
        })
        is RemoteAction.Button -> listOf(button(action.button, action.down))
        is RemoteAction.Click -> listOf(button(action.button, true), button(action.button, false))
        is RemoteAction.Zoom, is RemoteAction.Pan -> emptyList()
    }

    fun button(button: String, down: Boolean): String = RemoteJson.remoteControl("mouse_click") {
        addProperty("button", button); addProperty("isDown", down)
    }

    fun type(text: String): String = RemoteJson.remoteControl("type") { addProperty("text", text) }

    fun key(keycode: Int, modifiers: List<String> = emptyList()): String = RemoteJson.remoteControl("keypress") {
        addProperty("keycode", keycode)
        add("modifiers", com.google.gson.JsonArray().apply { modifiers.forEach { add(it) } })
    }
}
