package com.sameerasw.airsync.remote

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What a gesture on the Remote does (SPACE.md §15.5). Positions and distances are in dp of the surface the finger
 * is on; [Move] and [Scroll] are already in the computer's units.
 */
sealed class PadAction {
    /** Relative cursor move, accelerated (computer points). */
    data class Move(val dx: Float, val dy: Float) : PadAction()
    /** Direct mode: put the cursor under this surface point (dp); the screen maps it to the display. */
    data class MoveTo(val x: Float, val y: Float) : PadAction()
    /** A full click at the cursor. */
    data class Click(val button: String) : PadAction()
    /** One button edge (drags). */
    data class Button(val button: String, val down: Boolean) : PadAction()
    data class Scroll(val dx: Float, val dy: Float) : PadAction()
    /** Fingers left while scrolling fast: keep scrolling at this speed (scroll units per ms), slowing down. */
    data class Fling(val vx: Float, val vy: Float) : PadAction()
    /** Zoom the phone's view (never sent). Centre in surface dp. */
    data class Zoom(val factor: Float, val cx: Float, val cy: Float) : PadAction()
    /** Pan the zoomed view by surface dp (never sent). */
    data class Pan(val dx: Float, val dy: Float) : PadAction()
    /** App switcher: the first step opens it (⌘/Alt held), each step is Tab (forward) or ⇧Tab. */
    data class Switch(val forward: Boolean) : PadAction()
    /** Fingers lifted: release ⌘/Alt, the picked app comes forward. */
    object SwitchEnd : PadAction()
    /** The touchpad-mode picture: make it big / small again. */
    object Expand : PadAction()
    object Shrink : PadAction()

    companion object {
        const val LEFT = "left"
        const val RIGHT = "right"
    }
}

/** A recogniser fed every pointer event (positions in dp). [onTimer] runs when [nextTimerAt] comes without an event. */
interface PointerRecogniser {
    fun onEvent(t: Long, pointers: List<TouchPoint>): List<PadAction>
    fun nextTimerAt(): Long? = null
    fun onTimer(t: Long): List<PadAction> = emptyList()
    /** The surface went away mid-gesture: release anything held. */
    fun cancel(): List<PadAction>
}

/**
 * Pointer acceleration for the touchpad: a slow finger moves the cursor 1:1 (one computer point per dp, so a
 * small target is easy), a fast flick goes up to [MAX_GAIN]× as far. Between [SLOW] and [FAST] (finger speed in
 * dp/ms) the gain rises along a smoothstep, so there is no sudden jump. [reach] raises only the top of the curve
 * for very wide displays (a 5120-wide G9), never the precise end.
 */
object PointerAccel {
    const val SLOW = 0.08f
    const val FAST = 1.6f
    const val MAX_GAIN = 6f
    const val MAX_REACH = 1.6f
    private const val MIN_DT_MS = 4L
    private const val MAX_DT_MS = 40L

    fun gain(v: Float, reach: Float = 1f): Float {
        if (v.isNaN() || v <= SLOW) return 1f
        val t = ((v - SLOW) / (FAST - SLOW)).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return 1f + (MAX_GAIN * reach.coerceIn(1f, MAX_REACH) - 1f) * s
    }

    /** The finger moved ([dx], [dy]) dp in [dtMs]: the cursor move, times the user's [speed]. */
    fun move(dx: Float, dy: Float, dtMs: Long, speed: Float, reach: Float = 1f): Pair<Float, Float> {
        val dt = dtMs.coerceIn(MIN_DT_MS, MAX_DT_MS).toFloat()
        val g = gain(hypot(dx, dy) / dt, reach) * speed
        return dx * g to dy * g
    }

    /** A wider display gets a longer reach (the square root, so it stays gentle). */
    fun reachFor(displayWidth: Int): Float =
        if (displayWidth <= 2560) 1f else sqrt(displayWidth / 2560f).coerceIn(1f, MAX_REACH)
}

/**
 * Touchpad mode (SPACE.md §15.5): the dedicated pad under the picture.
 *
 * | Gesture | Does |
 * |---|---|
 * | one finger slide | [PadAction.Move], accelerated ([PointerAccel]) |
 * | tap | left click |
 * | two-finger tap | right click |
 * | two-finger slide | [PadAction.Scroll], then [PadAction.Fling] if lifted while moving |
 * | double-tap and hold (slide or wait) | left button held until lift |
 * | three-finger slide left/right | [PadAction.Switch] per step, [PadAction.SwitchEnd] on lift |
 *
 * Moves inside the tap slop are held back (so a tap clicks exactly where the cursor was) and sent as soon as the
 * finger passes it. Pure, so the decisions are unit-tested.
 */
class TouchpadGestures(
    var speed: Float = 1f,
    var naturalScroll: Boolean = true,
    var reach: Float = 1f
) : PointerRecogniser {
    private enum class Mode { NONE, MOVE, PENDING_DRAG, DRAG, TWO, SCROLL, SWITCH }

    private var active = false
    private var held = false
    private var switching = false
    private var scrolled = false
    private var released = false
    private var mode = Mode.NONE
    private var startT = 0L
    private var lastT = 0L
    private var maxPointers = 0
    private var lastCount = 0
    private var travel = 0f
    private var twoTravel = 0f
    private var prev: Map<Long, Pair<Float, Float>> = emptyMap()
    private var heldDx = 0f
    private var heldDy = 0f
    private var heldDt = 0L
    private var switchAcc = 0f
    private var switchSteps = 0
    private var svx = 0f
    private var svy = 0f
    private var lastScrollT = 0L
    private var pendingSince = 0L

    private var lastTapT = NEVER
    private var lastTapX = 0f
    private var lastTapY = 0f

    override fun onEvent(t: Long, pointers: List<TouchPoint>): List<PadAction> {
        val down = pointers.filter { it.pressed }
        val out = mutableListOf<PadAction>()
        if (!active) {
            if (down.isNotEmpty()) begin(t, down)
            return out
        }
        if (down.isEmpty()) {
            finish(t, out)
            return out
        }
        if (down.size != lastCount) {
            regroup(down)
            lastT = t
            return out
        }
        val (dx, dy) = centroidDelta(prev, down)
        prev = positions(down)
        val dt = (t - lastT).coerceAtLeast(1L)
        lastT = t
        val dist = hypot(dx, dy)
        travel += dist
        when (mode) {
            Mode.MOVE -> if (down.size == 1) {
                if (released) out += move(dx, dy, dt)
                else {
                    heldDx += dx; heldDy += dy; heldDt += dt
                    if (travel > TAP_SLOP_DP || t - startT > TAP_MAX_MS) releaseHeld(out)
                }
            }
            Mode.PENDING_DRAG -> {
                heldDx += dx; heldDy += dy; heldDt += dt
                if (travel > TAP_SLOP_DP) {
                    startDrag(out)
                    releaseHeld(out)
                }
            }
            Mode.DRAG -> if (dist > 0f) out += move(dx, dy, dt)
            Mode.TWO -> if (down.size >= 2) {
                twoTravel += dist
                if (twoTravel > SCROLL_SLOP_DP) {
                    mode = Mode.SCROLL
                    scrolled = true
                    scroll(dx, dy, dt, t, out)
                }
            }
            Mode.SCROLL -> if (down.size >= 2 && dist > 0f) scroll(dx, dy, dt, t, out)
            Mode.SWITCH -> switchBy(dx, out)
            Mode.NONE -> {}
        }
        return out
    }

    override fun nextTimerAt(): Long? =
        if (active && mode == Mode.PENDING_DRAG && !held) pendingSince + DRAG_HOLD_MS else null

    override fun onTimer(t: Long): List<PadAction> {
        if (!active || mode != Mode.PENDING_DRAG || held) return emptyList()
        val out = mutableListOf<PadAction>()
        startDrag(out)
        releaseHeld(out)
        return out
    }

    override fun cancel(): List<PadAction> {
        val out = mutableListOf<PadAction>()
        if (held) out += PadAction.Button(PadAction.LEFT, false)
        if (switching) out += PadAction.SwitchEnd
        reset()
        return out
    }

    private fun begin(t: Long, down: List<TouchPoint>) {
        reset()
        active = true
        startT = t
        lastT = t
        maxPointers = down.size
        lastCount = down.size
        prev = positions(down)
        mode = when {
            down.size >= 3 -> Mode.SWITCH
            down.size == 2 -> Mode.TWO
            else -> {
                val p = down[0]
                val near = hypot(p.x - lastTapX, p.y - lastTapY) <= DOUBLE_TAP_SLOP_DP
                if (t - lastTapT <= DOUBLE_TAP_MS && near) {
                    pendingSince = t
                    Mode.PENDING_DRAG
                } else Mode.MOVE
            }
        }
    }

    private fun regroup(down: List<TouchPoint>) {
        maxPointers = max(maxPointers, down.size)
        lastCount = down.size
        prev = positions(down)
        when {
            mode == Mode.DRAG || mode == Mode.SWITCH -> {}
            down.size >= 3 -> { mode = Mode.SWITCH; switchAcc = 0f; dropHeld() }
            down.size == 2 && (mode == Mode.NONE || mode == Mode.MOVE || mode == Mode.PENDING_DRAG) -> {
                mode = Mode.TWO
                twoTravel = 0f
                dropHeld()
            }
            // One finger left over from two: ignored until all lift.
        }
    }

    private fun finish(t: Long, out: MutableList<PadAction>) {
        val duration = t - startT
        when {
            held -> out += PadAction.Button(PadAction.LEFT, false)
            switching -> out += PadAction.SwitchEnd
            scrolled -> if (t - lastScrollT <= FLING_WINDOW_MS && hypot(svx, svy) >= FLING_MIN) out += PadAction.Fling(svx, svy)
            maxPointers == 1 && travel < TAP_SLOP_DP && duration <= TAP_MAX_MS -> {
                out += PadAction.Click(PadAction.LEFT)
                // A double-tap's second tap doesn't arm a third.
                if (mode == Mode.PENDING_DRAG) lastTapT = NEVER
                else {
                    lastTapT = t
                    prev.values.firstOrNull()?.let { (x, y) -> lastTapX = x; lastTapY = y }
                }
            }
            maxPointers == 1 && mode == Mode.MOVE && !released -> releaseHeld(out)
            maxPointers == 2 && travel < TWO_TAP_SLOP_DP && duration <= TWO_TAP_MAX_MS ->
                out += PadAction.Click(PadAction.RIGHT)
        }
        reset()
    }

    private fun reset() {
        active = false; held = false; switching = false; scrolled = false; released = false
        mode = Mode.NONE
        travel = 0f; twoTravel = 0f
        dropHeld()
        switchAcc = 0f; switchSteps = 0
        svx = 0f; svy = 0f
    }

    private fun dropHeld() { heldDx = 0f; heldDy = 0f; heldDt = 0L }

    private fun releaseHeld(out: MutableList<PadAction>) {
        released = true
        if (heldDx != 0f || heldDy != 0f) out += move(heldDx, heldDy, heldDt.coerceAtLeast(1L))
        dropHeld()
    }

    private fun startDrag(out: MutableList<PadAction>) {
        mode = Mode.DRAG
        held = true
        out += PadAction.Button(PadAction.LEFT, true)
    }

    private fun move(dx: Float, dy: Float, dt: Long): PadAction.Move {
        val (mx, my) = PointerAccel.move(dx, dy, dt, speed, reach)
        return PadAction.Move(mx, my)
    }

    private fun scroll(dx: Float, dy: Float, dt: Long, t: Long, out: MutableList<PadAction>) {
        val sign = if (naturalScroll) 1f else -1f
        val sx = dx * SCROLL_GAIN * sign
        val sy = dy * SCROLL_GAIN * sign
        out += PadAction.Scroll(sx, sy)
        val d = dt.coerceIn(4L, 40L).toFloat()
        svx = 0.5f * svx + 0.5f * (sx / d)
        svy = 0.5f * svy + 0.5f * (sy / d)
        lastScrollT = t
    }

    private fun switchBy(dx: Float, out: MutableList<PadAction>) {
        switchAcc += dx
        while (true) {
            val need = if (switchSteps == 0) SWITCH_FIRST_DP else SWITCH_STEP_DP
            if (abs(switchAcc) < need) break
            val forward = switchAcc > 0f
            out += PadAction.Switch(forward)
            switching = true
            switchSteps++
            switchAcc -= if (forward) need else -need
        }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        const val TAP_SLOP_DP = 8f
        const val TAP_MAX_MS = 250L
        const val TWO_TAP_SLOP_DP = 20f
        const val TWO_TAP_MAX_MS = 300L
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_SLOP_DP = 40f
        const val DRAG_HOLD_MS = 250L
        const val SCROLL_SLOP_DP = 8f
        /** Computer scroll pixels per dp of finger travel. */
        const val SCROLL_GAIN = 4f
        const val FLING_WINDOW_MS = 100L
        const val FLING_MIN = 0.5f
        const val SWITCH_FIRST_DP = 30f
        const val SWITCH_STEP_DP = 60f
    }
}

/**
 * Direct mode (SPACE.md §15.5): the picture is the screen. Tap → cursor there and left click; long-press → right
 * click there; one-finger drag → left-drag by absolute moves; two-finger slide → scroll; pinch → zoom the view;
 * three-finger slide → pan the zoomed view.
 */
class DirectGestures(var naturalScroll: Boolean = true) : PointerRecogniser {
    private enum class Mode { NONE, PRESS, DRAG, DONE, TWO, SCROLL, PINCH, PAN }

    private var active = false
    private var held = false
    private var mode = Mode.NONE
    private var startT = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var dragId = -1L
    private var lastCount = 0
    private var prev: Map<Long, Pair<Float, Float>> = emptyMap()
    private var startSpan = 0f
    private var prevSpan = 0f
    private var twoTravel = 0f

    override fun onEvent(t: Long, pointers: List<TouchPoint>): List<PadAction> {
        val down = pointers.filter { it.pressed }
        val out = mutableListOf<PadAction>()
        if (!active) {
            if (down.isNotEmpty()) begin(t, down)
            return out
        }
        if (down.isEmpty()) {
            finish(t, out)
            return out
        }
        if (down.size != lastCount) {
            lastCount = down.size
            prev = positions(down)
            when {
                mode == Mode.DRAG || mode == Mode.DONE -> {}
                down.size >= 3 -> mode = Mode.PAN
                down.size == 2 && (mode == Mode.PRESS || mode == Mode.NONE) -> {
                    mode = Mode.TWO
                    startSpan = span(down); prevSpan = startSpan; twoTravel = 0f
                }
            }
            return out
        }
        val (dx, dy) = centroidDelta(prev, down)
        prev = positions(down)
        when (mode) {
            Mode.PRESS -> {
                val p = down[0]
                if (hypot(p.x - x0, p.y - y0) > DRAG_SLOP_DP) {
                    mode = Mode.DRAG
                    held = true
                    dragId = p.id
                    out += PadAction.MoveTo(x0, y0)
                    out += PadAction.Button(PadAction.LEFT, true)
                    out += PadAction.MoveTo(p.x, p.y)
                }
            }
            Mode.DRAG -> {
                val p = down.firstOrNull { it.id == dragId } ?: down[0]
                out += PadAction.MoveTo(p.x, p.y)
            }
            Mode.TWO, Mode.SCROLL, Mode.PINCH -> if (down.size == 2) twoFingers(down, dx, dy, out)
            Mode.PAN -> if (dx != 0f || dy != 0f) out += PadAction.Pan(dx, dy)
            Mode.NONE, Mode.DONE -> {}
        }
        return out
    }

    override fun nextTimerAt(): Long? = if (active && mode == Mode.PRESS) startT + LONG_PRESS_MS else null

    override fun onTimer(t: Long): List<PadAction> {
        if (!active || mode != Mode.PRESS) return emptyList()
        mode = Mode.DONE
        return listOf(PadAction.MoveTo(x0, y0), PadAction.Click(PadAction.RIGHT))
    }

    override fun cancel(): List<PadAction> {
        val out = mutableListOf<PadAction>()
        if (held) out += PadAction.Button(PadAction.LEFT, false)
        held = false; active = false; mode = Mode.NONE
        return out
    }

    private fun begin(t: Long, down: List<TouchPoint>) {
        active = true
        held = false
        startT = t
        lastCount = down.size
        prev = positions(down)
        when {
            down.size >= 3 -> mode = Mode.PAN
            down.size == 2 -> { mode = Mode.TWO; startSpan = span(down); prevSpan = startSpan; twoTravel = 0f }
            else -> { mode = Mode.PRESS; x0 = down[0].x; y0 = down[0].y }
        }
    }

    private fun twoFingers(down: List<TouchPoint>, dx: Float, dy: Float, out: MutableList<PadAction>) {
        val dist = hypot(dx, dy)
        twoTravel += dist
        val s = span(down)
        if (mode == Mode.TWO) {
            mode = when {
                abs(s - startSpan) > PINCH_SLOP_DP && abs(s - startSpan) > twoTravel -> Mode.PINCH
                twoTravel > SCROLL_SLOP_DP -> Mode.SCROLL
                else -> Mode.TWO
            }
            if (mode == Mode.PINCH) prevSpan = startSpan
        }
        when (mode) {
            Mode.SCROLL -> if (dist > 0f) {
                val sign = if (naturalScroll) 1f else -1f
                out += PadAction.Scroll(dx * TouchpadGestures.SCROLL_GAIN * sign, dy * TouchpadGestures.SCROLL_GAIN * sign)
            }
            Mode.PINCH -> {
                if (prevSpan > 1f && s > 1f && s != prevSpan) {
                    val (cx, cy) = centroid(down)
                    out += PadAction.Zoom(s / prevSpan, cx, cy)
                }
                prevSpan = s
            }
            else -> {}
        }
    }

    private fun finish(t: Long, out: MutableList<PadAction>) {
        when {
            held -> out += PadAction.Button(PadAction.LEFT, false)
            mode == Mode.PRESS && t - startT < LONG_PRESS_MS -> {
                out += PadAction.MoveTo(x0, y0)
                out += PadAction.Click(PadAction.LEFT)
            }
        }
        held = false
        active = false
        mode = Mode.NONE
    }

    companion object {
        const val DRAG_SLOP_DP = 10f
        const val LONG_PRESS_MS = 450L
        const val SCROLL_SLOP_DP = 12f
        const val PINCH_SLOP_DP = 24f
    }
}

/**
 * The picture above the touchpad (touchpad mode): tap or swipe up → [PadAction.Expand], swipe down →
 * [PadAction.Shrink], pinch → zoom the view, two-finger slide → pan it. Nothing here reaches the computer.
 */
class PictureGestures : PointerRecogniser {
    private var active = false
    private var startT = 0L
    private var x0 = 0f
    private var y0 = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0
    private var lastCount = 0
    private var prev: Map<Long, Pair<Float, Float>> = emptyMap()
    private var prevSpan = 0f

    override fun onEvent(t: Long, pointers: List<TouchPoint>): List<PadAction> {
        val down = pointers.filter { it.pressed }
        val out = mutableListOf<PadAction>()
        if (!active) {
            if (down.isEmpty()) return out
            active = true
            startT = t
            maxPointers = down.size
            lastCount = down.size
            prev = positions(down)
            prevSpan = span(down)
            x0 = down[0].x; y0 = down[0].y; lastX = x0; lastY = y0
            return out
        }
        if (down.isEmpty()) {
            pointers.singleOrNull()?.let { lastX = it.x; lastY = it.y }
            if (maxPointers == 1) {
                val dx = lastX - x0
                val dy = lastY - y0
                when {
                    abs(dy) > SWIPE_DP && abs(dy) > 2f * abs(dx) -> out += if (dy > 0f) PadAction.Shrink else PadAction.Expand
                    hypot(dx, dy) < TouchpadGestures.TAP_SLOP_DP && t - startT <= TAP_MAX_MS -> out += PadAction.Expand
                }
            }
            active = false
            return out
        }
        if (down.size != lastCount) {
            maxPointers = max(maxPointers, down.size)
            lastCount = down.size
            prev = positions(down)
            prevSpan = span(down)
            return out
        }
        val (dx, dy) = centroidDelta(prev, down)
        prev = positions(down)
        if (down.size == 1) {
            lastX = down[0].x; lastY = down[0].y
        } else if (down.size == 2) {
            val s = span(down)
            if (prevSpan > 1f && s > 1f && s != prevSpan) {
                val (cx, cy) = centroid(down)
                out += PadAction.Zoom(s / prevSpan, cx, cy)
            }
            prevSpan = s
            if (dx != 0f || dy != 0f) out += PadAction.Pan(dx, dy)
        }
        return out
    }

    override fun cancel(): List<PadAction> { active = false; return emptyList() }

    companion object {
        const val SWIPE_DP = 48f
        const val TAP_MAX_MS = 300L
    }
}

/** Scroll that keeps going after a fast two-finger lift, slowing down exponentially (time constant [TAU_MS]). */
object ScrollInertia {
    const val TAU_MS = 325f
    /** Below this speed (units per ms) it stops. */
    const val STOP = 0.02f

    /** One step of [dtMs] at speed [v]: the distance to scroll and the new speed. */
    fun step(v: Float, dtMs: Long): Pair<Float, Float> {
        val decay = exp(-dtMs / TAU_MS)
        return v * TAU_MS * (1f - decay) to v * decay
    }
}

/**
 * Where a surface point lands on the computer's display (SPACE.md §15.5 `mouse_move_abs`): the picture is fitted
 * (letterboxed or pillarboxed) inside its box, and shows [ViewRect] of the display (zoomed and panned).
 */
object AbsMap {
    data class Fit(val left: Float, val top: Float, val width: Float, val height: Float)

    /** The picture's rectangle inside a [boxW]×[boxH] box for a picture of shape [aspect] (w/h), centred. */
    fun fit(boxW: Float, boxH: Float, aspect: Float): Fit {
        if (boxW <= 0f || boxH <= 0f) return Fit(0f, 0f, boxW.coerceAtLeast(0f), boxH.coerceAtLeast(0f))
        if (!aspect.isFinite() || aspect <= 0f) return Fit(0f, 0f, boxW, boxH)
        return if (aspect >= boxW / boxH) {
            val h = boxW / aspect
            Fit(0f, (boxH - h) / 2f, boxW, h)
        } else {
            val w = boxH * aspect
            Fit((boxW - w) / 2f, 0f, w, boxH)
        }
    }

    /** Fractions (0…1) of the picture under ([x], [y]); points on the bars clamp to the nearest edge. */
    fun toPicture(x: Float, y: Float, boxW: Float, boxH: Float, aspect: Float): Pair<Float, Float> {
        val f = fit(boxW, boxH, aspect)
        if (f.width <= 0f || f.height <= 0f) return 0.5f to 0.5f
        return ((x - f.left) / f.width).coerceIn(0f, 1f) to ((y - f.top) / f.height).coerceIn(0f, 1f)
    }

    /** Fractions (0…1) of the whole display under ([x], [y]). */
    fun toDisplay(x: Float, y: Float, boxW: Float, boxH: Float, aspect: Float, view: ViewRect): Pair<Float, Float> {
        val (fx, fy) = toPicture(x, y, boxW, boxH, aspect)
        return (view.x + fx * view.w).coerceIn(0f, 1f) to (view.y + fy * view.h).coerceIn(0f, 1f)
    }
}

/**
 * Coalesces pointer traffic so it goes out at most once per [intervalMs] (120 Hz): the first move after a pause
 * goes at once; moves inside the same frame are summed (relative) or replaced (absolute), never queued, so the
 * computer never replays stale positions. Pure: the caller passes the clock.
 */
class InputCoalescer(private val intervalMs: Long = 8L) {
    private var mx = 0f
    private var my = 0f
    private var sx = 0f
    private var sy = 0f
    private var abs: Triple<Float, Float, String>? = null
    private var lastSent = Long.MIN_VALUE / 2

    val pending: Boolean get() = mx != 0f || my != 0f || sx != 0f || sy != 0f || abs != null

    /** True: send now ([drain]); false: held until [waitMs] passes. */
    fun addMove(dx: Float, dy: Float, now: Long): Boolean { mx += dx; my += dy; return due(now) }
    fun addScroll(dx: Float, dy: Float, now: Long): Boolean { sx += dx; sy += dy; return due(now) }
    fun setAbs(x: Float, y: Float, display: String, now: Long): Boolean { abs = Triple(x, y, display); return due(now) }

    fun waitMs(now: Long): Long = (lastSent + intervalMs - now).coerceAtLeast(0L)

    /** The messages to send now (absolute, move, scroll), emptying the coalescer. */
    fun drain(now: Long): List<String> {
        val out = mutableListOf<String>()
        abs?.let { (x, y, d) -> out += RemoteInput.moveAbs(x, y, d) }
        if (mx != 0f || my != 0f) out += RemoteInput.messages(RemoteAction.Move(mx, my)).single()
        if (sx != 0f || sy != 0f) out += RemoteInput.messages(RemoteAction.Scroll(sx, sy)).single()
        abs = null; mx = 0f; my = 0f; sx = 0f; sy = 0f
        if (out.isNotEmpty()) lastSent = now
        return out
    }

    private fun due(now: Long) = now - lastSent >= intervalMs
}

// MARK: - shared finger math

internal fun positions(down: List<TouchPoint>): Map<Long, Pair<Float, Float>> = down.associate { it.id to (it.x to it.y) }

internal fun centroidDelta(prev: Map<Long, Pair<Float, Float>>, down: List<TouchPoint>): Pair<Float, Float> {
    var sx = 0f; var sy = 0f; var n = 0
    down.forEach { p ->
        val q = prev[p.id] ?: return@forEach
        sx += p.x - q.first; sy += p.y - q.second; n++
    }
    return if (n == 0) 0f to 0f else (sx / n) to (sy / n)
}

internal fun centroid(down: List<TouchPoint>): Pair<Float, Float> =
    (down.sumOf { it.x.toDouble() } / down.size).toFloat() to (down.sumOf { it.y.toDouble() } / down.size).toFloat()

internal fun span(down: List<TouchPoint>): Float =
    if (down.size < 2) 0f else hypot(down[0].x - down[1].x, down[0].y - down[1].y)
