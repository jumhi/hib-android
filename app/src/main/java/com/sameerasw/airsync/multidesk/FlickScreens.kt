package com.sameerasw.airsync.multidesk

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * H.I.B Point & Flick (docs/SPACE.md §7): one map of every screen of every computer, where the phone
 * points to reach each one, and the messages that light the aimed screen on the computers.
 *
 * Pure logic (Gson for JSON, no Android types) so it runs in JVM unit tests.
 */

/** One screen a computer reported in `screensInfo` (SPACE.md §7.2). Bounds are in that computer's desktop coordinates. */
data class ComputerScreen(
    val id: String,
    val name: String,
    val primary: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0
)

/** A connected computer and its screens; null screens = it never sent `screensInfo` (older version). */
data class ScreenComputer(val name: String, val screens: List<ComputerScreen>?)

/**
 * Something the phone can point at: one screen of one computer.
 * @param screen the screen id, or "" for a computer that never sent `screensInfo` ("let the computer choose")
 * @param label "Left screen", "Right screen", a display name… (empty when the computer has one screen)
 * @param screensOnComputer how many screens that computer has (the subtitle names the screen only when > 1)
 */
data class ScreenTarget(
    val computer: String,
    val screen: String,
    val label: String,
    val displayName: String,
    val screensOnComputer: Int
) {
    fun sameAs(computer: String, screen: String): Boolean =
        PairedDesktops.sameName(this.computer, computer) && this.screen == screen

    /** "Haitham’s MacBook · Left screen", or just the computer when it has one screen. */
    fun title(): String = if (screensOnComputer > 1 && label.isNotBlank()) "$computer · $label" else computer
}

/** A saved direction for one screen (`hib_flick_screens_v1`, SPACE.md §7.6). */
data class SavedScreenAim(
    val computer: String,
    val screen: String,
    val name: String,
    val h: Float,
    val e: Float
) {
    val aim: AimMatcher.Aim get() = AimMatcher.Aim(h, e)
}

object ScreensInfo {
    /** Parses a `screensInfo` envelope (or its `data`). Null when it isn't one; an empty list is valid. */
    fun parse(json: String?): List<ComputerScreen>? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
            val arr = data.get("screens")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val seen = HashSet<String>()
            arr.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                ComputerScreen(
                    id = id,
                    name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    primary = o.get("primary")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    x = o.num("x"), y = o.num("y"), w = o.num("w"), h = o.num("h")
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.num(key: String): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { round(it.asDouble).toInt() }.getOrNull() } ?: 0
}

/** The phone → computer messages of SPACE.md §7.3–7.5. */
object PointJson {
    fun screensRequest(): String = envelope("screensRequest", JsonObject())

    fun flickAim(screen: String?): String = envelope("flickAim", JsonObject().apply {
        if (screen != null) addProperty("screen", screen) else add("screen", JsonNull.INSTANCE)
    })

    fun aimSetup(screen: String, step: Int, total: Int, progress: Float): String =
        envelope("aimSetup", JsonObject().apply {
            addProperty("screen", screen)
            addProperty("step", step)
            addProperty("total", total)
            addProperty("progress", round(progress.coerceIn(0f, 1f) * 100.0) / 100.0)
        })

    fun aimSetupHide(): String = envelope("aimSetup", JsonObject().apply { add("screen", JsonNull.INSTANCE) })

    private fun envelope(type: String, data: JsonObject): String = JsonObject().apply {
        addProperty("type", type)
        add("data", data)
    }.toString()
}

object ScreenMap {
    const val MAX_ANGLE_DEG = AimMatcher.MAX_ANGLE_DEG

    // MARK: naming and ordering

    /** A computer's screens left → right (then top → bottom, then id). */
    fun leftToRight(screens: List<ComputerScreen>): List<ComputerScreen> = screens.sortedWith { a, b ->
        val x = a.x.compareTo(b.x)
        if (x != 0) return@sortedWith x
        val y = a.y.compareTo(b.y)
        if (y != 0) y else compareSpaceIds(a.id, b.id)
    }

    /**
     * Short, human names for a computer's screens: "Left screen" / "Right screen" (or "Top" / "Bottom" when
     * stacked), "Middle screen" for a third, else the display name, else "Screen N". One screen gets "".
     */
    fun labels(screens: List<ComputerScreen>): Map<String, String> {
        if (screens.size <= 1) return screens.associate { it.id to "" }
        val ordered = leftToRight(screens)
        if (ordered.size == 2) {
            val (a, b) = ordered
            val overlapX = min(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
            val stacked = a.w > 0 && b.w > 0 && overlapX > min(a.w, b.w) / 2
            return if (stacked) {
                val (top, bottom) = if (a.y <= b.y) a to b else b to a
                mapOf(top.id to "Top screen", bottom.id to "Bottom screen")
            } else {
                mapOf(a.id to "Left screen", b.id to "Right screen")
            }
        }
        val distinctX = ordered.map { it.x }.distinct().size == ordered.size
        if (ordered.size == 3 && distinctX) {
            return mapOf(ordered[0].id to "Left screen", ordered[1].id to "Middle screen", ordered[2].id to "Right screen")
        }
        val friendly = ordered.map { friendlyName(it) }
        val unique = friendly.filterNotNull().let { it.size == ordered.size && it.distinct().size == it.size }
        return ordered.mapIndexed { i, s ->
            s.id to (if (unique) friendly[i]!! else "Screen ${i + 1}")
        }.toMap()
    }

    /** The display name when it reads like one ("DELL U2720Q"), not a device path (`\\.\DISPLAY1`) or bounds. */
    fun friendlyName(s: ComputerScreen): String? {
        val n = s.name.trim()
        if (n.isEmpty() || n.startsWith("\\\\") || n.matches(Regex("^-?\\d+,-?\\d+,\\d+x\\d+$"))) return null
        return n
    }

    /**
     * Everything the phone can point at across the connected computers, in Point setup order (SPACE.md §7.5):
     * the computer in use first, then the others in the given order; each computer's screens left → right.
     * A computer without `screensInfo` (or with an empty list) is one pseudo-screen with id "".
     */
    fun targets(computers: List<ScreenComputer>, inUse: String? = null): List<ScreenTarget> {
        val ordered = computers.sortedBy { c -> if (inUse != null && PairedDesktops.sameName(c.name, inUse)) 0 else 1 }
        return ordered.flatMap { c ->
            val screens = c.screens
            if (screens.isNullOrEmpty()) {
                listOf(ScreenTarget(c.name, "", "", "", 1))
            } else {
                val labels = labels(screens)
                leftToRight(screens).map { s ->
                    ScreenTarget(c.name, s.id, labels[s.id].orEmpty(), s.name, screens.size)
                }
            }
        }
    }

    fun isSaved(t: ScreenTarget, saved: List<SavedScreenAim>): Boolean = saved.any { t.sameAs(it.computer, it.screen) }

    /** Point setup runs by itself when there are 2+ screens in total and any of them has no saved aim. */
    fun unmapped(computers: List<ScreenComputer>, saved: List<SavedScreenAim>, inUse: String? = null): List<ScreenTarget> {
        val all = targets(computers, inUse)
        if (all.size < 2) return emptyList()
        return all.filterNot { isSaved(it, saved) }
    }

    /**
     * Live aim (SPACE.md §7.6): the nearest saved screen of a connected computer within 35°, or null.
     * A saved screen counts while its computer still reports it; for a computer whose screens aren't known
     * (yet), any of its saved screens counts. With fewer than two screens in total there is no aim.
     */
    fun nearest(current: AimMatcher.Aim?, saved: List<SavedScreenAim>, computers: List<ScreenComputer>): ScreenTarget? {
        if (current == null) return null
        val all = targets(computers)
        if (all.size < 2) return null
        var best: Pair<ScreenTarget, Double>? = null
        for (s in saved) {
            val c = computers.firstOrNull { PairedDesktops.sameName(it.name, s.computer) } ?: continue
            val screens = c.screens
            val target = when {
                // Screens not known (yet): trust a saved id.
                screens == null && s.screen.isNotEmpty() -> ScreenTarget(c.name, s.screen, "", s.name, 1)
                else -> all.firstOrNull { it.sameAs(c.name, s.screen) } ?: continue
            }
            val d = AimMatcher.angleBetween(current, s.aim)
            if (d > MAX_ANGLE_DEG) continue
            val b = best
            if (b == null || d < b.second || (d == b.second && tieBreak(target, b.first) < 0)) best = target to d
        }
        return best?.first
    }

    private fun tieBreak(a: ScreenTarget, b: ScreenTarget): Int {
        val c = compareSpaceIds(a.computer, b.computer)
        return if (c != 0) c else compareSpaceIds(a.screen, b.screen)
    }

    /** Re-running setup replaces the entries of the screens it captured. */
    fun replace(existing: List<SavedScreenAim>, captured: List<SavedScreenAim>): List<SavedScreenAim> =
        existing.filterNot { e -> captured.any { PairedDesktops.sameName(it.computer, e.computer) && it.screen == e.screen } } + captured

    // MARK: persistence: [{"computer":"…","screen":"…","name":"…","h":214.0,"e":8.5}, …]

    fun encode(list: List<SavedScreenAim>): String {
        val arr = JsonArray()
        list.forEach { s ->
            arr.add(JsonObject().apply {
                addProperty("computer", s.computer)
                addProperty("screen", s.screen)
                addProperty("name", s.name)
                addProperty("h", s.h)
                addProperty("e", s.e)
            })
        }
        return arr.toString()
    }

    fun decode(json: String?): List<SavedScreenAim> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val computer = o.get("computer")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                val h = o.get("h")?.takeIf { it.isJsonPrimitive }?.asFloat ?: return@mapNotNull null
                SavedScreenAim(
                    computer = computer,
                    screen = o.get("screen")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    h = h,
                    e = o.get("e")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** The average of several pointing directions (on the sphere, so 359° and 1° average to 0°). */
    fun mean(aims: List<AimMatcher.Aim>): AimMatcher.Aim? {
        if (aims.isEmpty()) return null
        var x = 0.0; var y = 0.0; var z = 0.0
        aims.forEach { a ->
            val h = Math.toRadians(a.heading.toDouble())
            val e = Math.toRadians(a.elevation.toDouble())
            x += cos(e) * sin(h); y += cos(e) * cos(h); z += sin(e)
        }
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1e-9) return aims.last()
        var heading = Math.toDegrees(atan2(x, y))
        if (heading < 0) heading += 360.0
        val elevation = Math.toDegrees(asin((z / len).coerceIn(-1.0, 1.0)))
        return AimMatcher.Aim(heading.toFloat(), elevation.toFloat())
    }
}

/**
 * Hold-to-capture (SPACE.md §7.5): while the aim moves less than [maxMoveDeg] over the last [windowMs],
 * progress grows to 1 in [fillMs]. Moving resets it. Directions in [avoid] (screens already captured in
 * this run) can't be captured again: the user has to point somewhere new first.
 */
class HoldToCapture(
    private val maxMoveDeg: Double = 3.0,
    private val windowMs: Long = 500L,
    private val fillMs: Long = 900L,
    private val avoidDeg: Double = 8.0,
    private val maxStepMs: Long = 100L
) {
    private val samples = ArrayDeque<Pair<Long, AimMatcher.Aim>>()
    private var lastAt: Long? = null

    var progress: Float = 0f
        private set

    /** True when the last sample was too close to an already captured screen. */
    var blocked: Boolean = false
        private set

    fun reset() {
        samples.clear()
        lastAt = null
        progress = 0f
        blocked = false
    }

    fun update(aim: AimMatcher.Aim, now: Long, avoid: List<AimMatcher.Aim> = emptyList()): Float {
        samples.addLast(now to aim)
        while (samples.isNotEmpty() && samples.first().first < now - windowMs) samples.removeFirst()
        val dt = lastAt?.let { (now - it).coerceIn(0L, maxStepMs) } ?: 0L
        lastAt = now
        blocked = avoid.any { AimMatcher.angleBetween(it, aim) < avoidDeg }
        val moved = samples.maxOfOrNull { AimMatcher.angleBetween(it.second, aim) } ?: 0.0
        progress = if (blocked || moved >= maxMoveDeg) 0f else (progress + dt.toFloat() / fillMs).coerceAtMost(1f)
        return progress
    }

    /** The steady direction being held (average of the window). */
    fun heldAim(): AimMatcher.Aim? = ScreenMap.mean(samples.map { it.second })
}

/**
 * One Point setup run over [targets]. Feed it aim samples; it reports progress, captures each screen in
 * turn and is [done] after the last one.
 */
class PointSetupRun(val targets: List<ScreenTarget>, private val hold: HoldToCapture = HoldToCapture()) {
    var index: Int = 0
        private set
    val captured = mutableListOf<SavedScreenAim>()

    val current: ScreenTarget? get() = targets.getOrNull(index)
    val done: Boolean get() = index >= targets.size
    val progress: Float get() = hold.progress
    val blocked: Boolean get() = hold.blocked

    /** Feeds one sample. Returns the saved entry when this sample completed the current screen. */
    fun feed(aim: AimMatcher.Aim?, now: Long): SavedScreenAim? {
        val t = current ?: return null
        if (aim == null) {
            hold.reset()
            return null
        }
        val p = hold.update(aim, now, captured.map { it.aim })
        if (p < 1f) return null
        val held = hold.heldAim() ?: aim
        val name = t.displayName.ifBlank { if (t.screen.isEmpty()) t.computer else t.label }
        val s = SavedScreenAim(t.computer, t.screen, name, held.heading, held.elevation)
        captured += s
        index++
        hold.reset()
        return s
    }

    /** Moves past the current screen without saving it (its computer went away). */
    fun skipCurrent() {
        if (done) return
        index++
        hold.reset()
    }
}

/**
 * Which computer's screen is lit while the Flick screen is open (SPACE.md §7.4): `flickAim{screen}` to the
 * computer that owns the aimed screen, `null` to the one lit before, the current one again every
 * [resendMs], and `null` to all on close.
 */
class AimLight(private val resendMs: Long = 1500L) {
    data class Send(val computer: String, val screen: String?) {
        fun json(): String = PointJson.flickAim(screen)
    }

    private var lit: ScreenTarget? = null
    private var sentAt = 0L

    val current: ScreenTarget? get() = lit

    fun update(target: ScreenTarget?, now: Long): List<Send> {
        val prev = lit
        if (prev == null && target == null) return emptyList()
        if (prev != null && target != null && target.sameAs(prev.computer, prev.screen)) {
            if (now - sentAt < resendMs) return emptyList()
            sentAt = now
            return listOf(Send(target.computer, target.screen))
        }
        val out = mutableListOf<Send>()
        if (prev != null && (target == null || !PairedDesktops.sameName(prev.computer, target.computer))) {
            out += Send(prev.computer, null)
        }
        if (target != null) {
            out += Send(target.computer, target.screen)
            sentAt = now
        }
        lit = target
        return out
    }

    /** Flick closes (or pauses): light nothing anywhere. */
    fun clear(connected: List<String>): List<Send> {
        lit = null
        sentAt = 0L
        return connected.map { Send(it, null) }
    }
}

/**
 * The Point setup target on the computers (SPACE.md §7.5): `aimSetup` to the current screen's computer
 * (on a new step, on visible progress, and at least every [keepAliveMs] so it doesn't hide by itself),
 * `null` to the previous computer when setup moves to another one, `null` to all at the end.
 */
class SetupLight(
    private val minGapMs: Long = 100L,
    private val keepAliveMs: Long = 1500L,
    private val minProgressDelta: Float = 0.04f
) {
    data class Send(val computer: String, val json: String)

    private var last: ScreenTarget? = null
    private var lastStep = -1
    private var lastProgress = -1f
    private var sentAt = 0L

    /** @param force send now even if throttled (the captured screen's full, lime ring) */
    fun update(target: ScreenTarget?, step: Int, total: Int, progress: Float, now: Long, force: Boolean = false): List<Send> {
        val prev = last
        val out = mutableListOf<Send>()
        if (target == null) {
            if (prev != null) out += Send(prev.computer, PointJson.aimSetupHide())
            last = null
            lastStep = -1
            return out
        }
        val sameScreen = prev != null && target.sameAs(prev.computer, prev.screen) && step == lastStep
        if (sameScreen && !force) {
            val changed = abs(progress - lastProgress) >= minProgressDelta || (progress >= 1f && lastProgress < 1f) ||
                (progress == 0f && lastProgress > 0f)
            val due = (changed && now - sentAt >= minGapMs) || now - sentAt >= keepAliveMs
            if (!due) return out
        } else if (prev != null && !PairedDesktops.sameName(prev.computer, target.computer)) {
            out += Send(prev.computer, PointJson.aimSetupHide())
        }
        out += Send(target.computer, PointJson.aimSetup(target.screen, step, total, progress))
        last = target
        lastStep = step
        lastProgress = progress
        sentAt = now
        return out
    }

    /** Setup ends: hide it on every computer. */
    fun end(connected: List<String>): List<Send> {
        last = null
        lastStep = -1
        lastProgress = -1f
        return connected.map { Send(it, PointJson.aimSetupHide()) }
    }
}
