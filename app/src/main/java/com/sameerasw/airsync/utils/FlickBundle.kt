package com.sameerasw.airsync.utils

import com.google.gson.JsonObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * H.I.B — Flick bundles (SPACE.md §14): many items, one throw.
 *
 * Every `flickStart` of a bundle carries `"bundle":{"id":"…","index":0,"count":5}`; all of them share the bundle
 * id, the screen, `exitXRatio`, `velocity` and `angle`. Pure Kotlin (no Android types) so it is unit-tested.
 */
data class FlickBundle(val id: String, val index: Int, val count: Int) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("index", index)
        addProperty("count", count)
    }

    companion object {
        /** A bundle tag from the wire, or null when it isn't a usable one (missing id, index out of range). */
        fun from(id: String?, index: Int, count: Int): FlickBundle? {
            if (id.isNullOrBlank() || count < 1 || index < 0 || index >= count) return null
            return FlickBundle(id, index, count)
        }

        /** Tags for [count] items thrown together; null for a single item (it isn't a bundle). */
        fun tags(id: String, count: Int): List<FlickBundle?> =
            if (count < 2) List(count) { null } else List(count) { FlickBundle(id, it, count) }
    }
}

/** The `flickStart` message the phone sends the instant a card (or a bundle) is thrown. */
object FlickWire {
    const val MAX_TEXT_CHARS = 100_000

    fun flickStart(
        id: String,
        kind: String,
        name: String,
        mime: String,
        size: Long,
        exitXRatio: Float,
        velocity: Float,
        angle: Float,
        thumbnailBase64: String? = null,
        text: String? = null,
        screen: String? = null,
        aim: Pair<Float, Float>? = null,
        bundle: FlickBundle? = null
    ): String {
        val data = JsonObject().apply {
            addProperty("id", id)
            addProperty("kind", kind)
            addProperty("name", name)
            addProperty("mime", mime)
            addProperty("size", size)
            addProperty("exitXRatio", exitXRatio.toDouble())
            addProperty("velocity", velocity.toDouble())
            addProperty("angle", angle.toDouble())
            if (!thumbnailBase64.isNullOrEmpty()) addProperty("thumbnail", thumbnailBase64)
            if (text != null) addProperty("text", text.take(MAX_TEXT_CHARS))
            if (!screen.isNullOrEmpty()) addProperty("screen", screen)
            // Where the phone is pointing, for computers that don't know `screen` yet.
            if (aim != null) {
                addProperty("heading", aim.first.toDouble())
                addProperty("elevation", aim.second.toDouble())
            }
            if (bundle != null) add("bundle", bundle.toJson())
        }
        return JsonObject().apply {
            addProperty("type", "flickStart")
            add("data", data)
        }.toString()
    }
}

/** Words for a bundle: "5 photos", "1 video", "3 photos and videos", "4 files". */
object BundleWords {
    fun noun(kinds: List<String>, count: Int = kinds.size): String {
        val images = kinds.count { it == "image" }
        val videos = kinds.count { it == "video" }
        val n = count
        val one = n == 1
        return when {
            kinds.isNotEmpty() && images == kinds.size -> if (one) "1 photo" else "$n photos"
            kinds.isNotEmpty() && videos == kinds.size -> if (one) "1 video" else "$n videos"
            kinds.isNotEmpty() && images + videos == kinds.size -> "$n photos and videos"
            else -> if (one) "1 item" else "$n items"
        }
    }
}

/** A point in px, relative to whatever origin the caller uses. */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
}

/** A card's resting pose in the stack, in dp/degrees. */
data class StackPose(val dx: Float, val dy: Float, val rotation: Float, val scale: Float)

/**
 * Where the cards of a bundle sit: the stack (top card plus a few peeking out at small angles), the fan-out
 * pattern (the same shapes the computers use, SPACE.md §14), the staggered arc the cards fly on, and the
 * phone's preview strip for incoming bundles.
 */
object FlickBundleLayout {
    /** Cards drawn behind the top one in a stack. */
    const val PEEK = 3
    /** Slots in a fan; a bigger bundle shows the first [PAGE] and a "+N" card. */
    const val MAX_SLOTS = 9
    const val PAGE = MAX_SLOTS - 1

    /**
     * Cards per row for a fan of [count]: 2 side by side · 3 = two on top, one below · 4 = 2×2 ·
     * 5 = dice-five · 6 = 3×2 · 7 = 2-3-2 · 8 = 3-2-3 · 9 = 3×3 · 10+ = 3×3 (8 cards and a "+N").
     */
    fun rows(count: Int): List<Int> = when {
        count <= 0 -> emptyList()
        count == 1 -> listOf(1)
        count == 2 -> listOf(2)
        count == 3 -> listOf(2, 1)
        count == 4 -> listOf(2, 2)
        count == 5 -> listOf(2, 1, 2)
        count == 6 -> listOf(3, 3)
        count == 7 -> listOf(2, 3, 2)
        count == 8 -> listOf(3, 2, 3)
        else -> listOf(3, 3, 3)
    }

    /** Slots shown for [count] items (the last one is the "+N" card when [overflow] > 0). */
    fun slotCount(count: Int): Int = min(count, MAX_SLOTS).coerceAtLeast(0)

    /** How many items the "+N" card stands for (0 when everything fits). */
    fun overflow(count: Int): Int = if (count > MAX_SLOTS) count - PAGE else 0

    /** Columns of the widest row. */
    fun columns(count: Int): Int = rows(count).maxOrNull() ?: 0

    /**
     * Centres of the fan's slots relative to the fan's centre, row by row, left to right, for cards of
     * [cellW]×[cellH] with [gap] between them.
     */
    fun fan(count: Int, cellW: Float, cellH: Float, gap: Float): List<Pt> {
        val rows = rows(count)
        val out = ArrayList<Pt>(rows.sum())
        val rowCount = rows.size
        rows.forEachIndexed { r, k ->
            val y = (r - (rowCount - 1) / 2f) * (cellH + gap)
            for (c in 0 until k) {
                val x = (c - (k - 1) / 2f) * (cellW + gap)
                out += Pt(x, y)
            }
        }
        return out
    }

    /**
     * The largest card width that fits a fan of [count] into [areaW]×[areaH] at [aspect] (height / width),
     * never larger than [maxW].
     */
    fun fitCell(count: Int, areaW: Float, areaH: Float, gap: Float, aspect: Float, maxW: Float): Float {
        val rows = rows(count)
        if (rows.isEmpty()) return 0f
        val cols = rows.max()
        val byW = (areaW - (cols - 1) * gap) / cols
        val byH = ((areaH - (rows.size - 1) * gap) / rows.size) / aspect
        return max(0f, min(maxW, min(byW, byH)))
    }

    /** The pose of the card [depth] places behind the top one (0 = the top card). */
    fun stackPose(depth: Int): StackPose = when (depth) {
        0 -> StackPose(0f, 0f, 0f, 1f)
        1 -> StackPose(-12f, 8f, -7f, 0.97f)
        2 -> StackPose(13f, 14f, 6f, 0.94f)
        else -> StackPose(-4f, 20f, -3f, 0.91f)
    }

    /**
     * Progress (0..1) of the card [index] of a throw at overall progress [t] (0..1), where each card leaves
     * [staggerMs] after the one before it and flies for [flightMs]. Card 0 leads.
     */
    fun localT(t: Float, index: Int, cards: Int, flightMs: Int, staggerMs: Int): Float {
        val total = flightMs + staggerMs * (cards - 1).coerceAtLeast(0)
        if (total <= 0 || flightMs <= 0) return 1f
        val now = t.coerceIn(0f, 1f) * total
        return ((now - index * staggerMs) / flightMs).coerceIn(0f, 1f)
    }

    /** The whole throw's length for [cards] cards. */
    fun throwMs(cards: Int, flightMs: Int, staggerMs: Int): Int = flightMs + staggerMs * (cards - 1).coerceAtLeast(0)

    /**
     * A point on the arc the card [index] flies from [start] to [end]: a quadratic curve bent sideways,
     * alternately left and right and a little more for each card further back, so the cards trail the lead
     * one in a fanned arc. [bend] is the first card's sideways offset in px; card 0 flies straight.
     */
    fun trailPoint(start: Pt, end: Pt, t: Float, index: Int, bend: Float): Pt {
        val u = t.coerceIn(0f, 1f)
        val d = end - start
        val len = hypot(d.x, d.y)
        if (len < 1e-3f) return start
        val side = if (index % 2 == 1) 1f else -1f
        val amount = if (index == 0) 0f else side * bend * ceil(index / 2f)
        // Unit normal to the path.
        val nx = -d.y / len
        val ny = d.x / len
        val mid = Pt(start.x + d.x / 2f + nx * amount, start.y + d.y / 2f + ny * amount)
        val a = (1 - u) * (1 - u)
        val b = 2 * (1 - u) * u
        val c = u * u
        return Pt(a * start.x + b * mid.x + c * end.x, a * start.y + b * mid.y + c * end.y)
    }

    /** A rectangle in px. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** Tiles shown in the preview strip of an incoming bundle's notification. */
    const val STRIP_TILES = 5

    /**
     * The preview strip for [count] items: up to [STRIP_TILES] equal tiles side by side across [width]×[height]
     * with [pad] around and [gap] between. A bigger bundle's last tile carries the "+N".
     */
    fun strip(count: Int, width: Float, height: Float, pad: Float, gap: Float): List<Box> {
        val n = min(count, STRIP_TILES)
        if (n <= 0) return emptyList()
        val tileW = (width - 2 * pad - (n - 1) * gap) / n
        val tileH = height - 2 * pad
        return List(n) { i ->
            val l = pad + i * (tileW + gap)
            Box(l, pad, l + tileW, pad + tileH)
        }
    }

    /** "+N" for the strip's last tile when the bundle has more than fits (0: none). */
    fun stripOverflow(count: Int): Int = if (count > STRIP_TILES) count - (STRIP_TILES - 1) else 0

    /** True when two floats are equal within [eps] (for tests and hit-tests). */
    fun near(a: Float, b: Float, eps: Float = 0.01f) = abs(a - b) <= eps
}

/** The state of one file stream Flick sends (see [FlickSender.transfers]). */
data class FlickTransfer(
    val id: String,
    val size: Long,
    val sent: Long = 0L,
    val state: State = State.QUEUED,
    val at: Long = 0L
) {
    enum class State { QUEUED, SENDING, DONE, FAILED }
    val finished get() = state == State.DONE || state == State.FAILED
}

/** Progress for a whole bundle: by bytes, with the counts of finished and failed items. */
data class BundleProgress(val total: Int, val done: Int, val failed: Int, val fraction: Float) {
    val finished get() = done + failed >= total

    companion object {
        /**
         * Progress of the files [ids] (with their expected [sizes]) given the [transfers] FlickSender reports.
         * An id it doesn't know yet counts as queued.
         */
        fun of(ids: List<String>, sizes: Map<String, Long>, transfers: Map<String, FlickTransfer>): BundleProgress {
            if (ids.isEmpty()) return BundleProgress(0, 0, 0, 1f)
            var done = 0
            var failed = 0
            var totalBytes = 0.0
            var sentBytes = 0.0
            for (id in ids) {
                val t = transfers[id]
                val size = (t?.size?.takeIf { it > 0 } ?: sizes[id]?.takeIf { it > 0 } ?: 1L).toDouble()
                totalBytes += size
                when (t?.state) {
                    FlickTransfer.State.DONE -> { done++; sentBytes += size }
                    FlickTransfer.State.FAILED -> { failed++; sentBytes += size }
                    FlickTransfer.State.SENDING -> sentBytes += min(t.sent.toDouble(), size)
                    else -> {}
                }
            }
            val fraction = if (totalBytes <= 0.0) 1f else (sentBytes / totalBytes).toFloat().coerceIn(0f, 1f)
            return BundleProgress(ids.size, done, failed, fraction)
        }
    }
}
