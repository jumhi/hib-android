package com.sameerasw.airsync.multidesk

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/*
 * H.I.B Space (docs/SPACE.md): the phone, the Mac and the Windows PC as one set of devices.
 *
 * Everything in this file is pure logic (no Android types, Gson for JSON) so it runs in JVM unit tests.
 */

/** Ids compare lexicographically over their UTF-8 bytes (SPACE.md §2), used for tie-breaks everywhere. */
fun compareSpaceIds(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    val n = minOf(x.size, y.size)
    for (i in 0 until n) {
        val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (c != 0) return c
    }
    return x.size - y.size
}

/**
 * The one target rule (SPACE.md §1.4). Each step only counts when the one before it doesn't decide:
 * explicit pick → aim → the computer in use → the last one used → the primary computer.
 */
object TargetRule {

    data class Candidate(
        val id: String,
        /** From the computer's last `desktopActivity` (and still fresh). */
        val inUse: Boolean = false,
        /** now − idleSeconds at the computer's last `desktopActivity`; 0 when unknown. */
        val lastActiveAt: Long = 0L,
        /** Last time the phone sent this computer a Flick / Play, or the user picked it; 0 when never. */
        val lastUsedAt: Long = 0L,
        val isPrimary: Boolean = false
    )

    enum class Reason { EXPLICIT, AIM, IN_USE, LAST_USED, PRIMARY }

    data class Pick(val id: String, val reason: Reason)

    /**
     * @param candidates the *connected* computers only (an offline computer is never a target)
     * @param explicit the computer the user picked on purpose, if any
     * @param aimed the computer the phone points at (phone only), if any
     */
    fun pick(candidates: List<Candidate>, explicit: String? = null, aimed: String? = null): Pick? {
        if (candidates.isEmpty()) return null
        fun find(id: String?) = id?.let { want -> candidates.firstOrNull { PairedDesktops.sameName(it.id, want) } }

        find(explicit)?.let { return Pick(it.id, Reason.EXPLICIT) }
        find(aimed)?.let { return Pick(it.id, Reason.AIM) }
        inUseId(candidates)?.let { return Pick(it, Reason.IN_USE) }

        val used = candidates
            .map { it to maxOf(it.lastUsedAt, it.lastActiveAt) }
            .filter { it.second > 0L }
            .sortedWith { a, b ->
                val t = b.second.compareTo(a.second)
                if (t != 0) t else compareSpaceIds(a.first.id, b.first.id)
            }
            .firstOrNull()
        if (used != null) return Pick(used.first.id, Reason.LAST_USED)

        val primary = candidates.firstOrNull { it.isPrimary }
            ?: candidates.sortedWith { a, b -> compareSpaceIds(a.id, b.id) }.first()
        return Pick(primary.id, Reason.PRIMARY)
    }

    /** The connected computer with `inUse:true` and the newest lastActiveAt (ties: lower id), or null. */
    fun inUseId(candidates: List<Candidate>): String? = candidates
        .filter { it.inUse }
        .sortedWith { a, b ->
            val t = b.lastActiveAt.compareTo(a.lastActiveAt)
            if (t != 0) t else compareSpaceIds(a.id, b.id)
        }
        .firstOrNull()?.id
}

/** Per-computer activity from `desktopActivity` (SPACE.md §3.1). */
data class DesktopActivityInfo(
    val inUse: Boolean,
    /** As reported; −1 when the computer can't tell. */
    val idleSeconds: Int,
    /** now − idleSeconds at reception (0 when unknown). */
    val lastActiveAt: Long,
    val receivedAt: Long
) {
    /** Still trust `inUse` (a computer re-sends at least every 30 s; three missed heartbeats = stale). */
    fun isFreshInUse(now: Long): Boolean = inUse && now - receivedAt <= STALE_AFTER_MS

    /** idleSeconds as of [now] (the reported value plus the time since it was reported), −1 when unknown. */
    fun idleSecondsAt(now: Long): Int =
        if (idleSeconds < 0) -1 else idleSeconds + ((now - receivedAt).coerceAtLeast(0L) / 1000L).toInt()

    companion object {
        const val STALE_AFTER_MS = 95_000L

        fun from(inUse: Boolean, idleSeconds: Int, now: Long): DesktopActivityInfo {
            val lastActive = when {
                idleSeconds >= 0 -> now - idleSeconds * 1000L
                inUse -> now
                else -> 0L
            }
            return DesktopActivityInfo(inUse, idleSeconds, lastActive, now)
        }
    }
}

/**
 * The Flick screen's bubbles sit across the top; the top 180° above the card is split into N equal
 * sectors, leftmost bubble first. A throw straight up (within [STRAIGHT_UP_CONE_DEG] of vertical) means
 * "the highlighted default" (SPACE.md §1.5).
 */
object FlickSectors {
    const val STRAIGHT_UP_CONE_DEG = 12.0

    /**
     * @param dx horizontal drag (screen px, + = right)
     * @param dy vertical drag (screen px, + = down)
     * @param n number of bubbles
     * @param minDistance drags shorter than this don't point anywhere yet
     * @return the bubble index (0 = leftmost) or null for "the default target"
     */
    fun sectorFor(
        dx: Float,
        dy: Float,
        n: Int,
        minDistance: Float = 0f,
        straightUpConeDeg: Double = STRAIGHT_UP_CONE_DEG
    ): Int? {
        if (n <= 0) return null
        if (dy >= 0f) return null // not upward
        if (hypot(dx, dy) < minDistance) return null
        if (n == 1) return 0
        // 0° = right, 90° = straight up, 180° = left.
        val angle = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        if (kotlin.math.abs(angle - 90.0) < straightUpConeDeg) return null
        val width = 180.0 / n
        return floor((180.0 - angle) / width).toInt().coerceIn(0, n - 1)
    }

    /** The drag direction (degrees, 0 = right, 90 = up) that points at the middle of bubble [index]. */
    fun centreAngle(index: Int, n: Int): Double = 180.0 - (index + 0.5) * (180.0 / n)
}

/** Where the phone points (FlickAim) matched against the directions the user taught per computer. */
object AimMatcher {
    const val MAX_ANGLE_DEG = 35.0
    const val MIN_TAUGHT = 2

    data class Aim(val heading: Float, val elevation: Float)

    /** Great-circle angle between two pointing directions, in degrees. */
    fun angleBetween(a: Aim, b: Aim): Double {
        val (ax, ay, az) = unit(a)
        val (bx, by, bz) = unit(b)
        val dot = (ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }

    /**
     * The taught computer nearest to [current] within [MAX_ANGLE_DEG], once at least [MIN_TAUGHT] are taught.
     * @param eligible when given, only these ids (the connected computers) can win
     */
    fun nearest(current: Aim?, taught: Map<String, Aim>, eligible: Collection<String>? = null): String? {
        if (current == null || taught.size < MIN_TAUGHT) return null
        return taught.entries
            .filter { e -> eligible == null || eligible.any { PairedDesktops.sameName(it, e.key) } }
            .map { it.key to angleBetween(current, it.value) }
            .filter { it.second <= MAX_ANGLE_DEG }
            .minWithOrNull { a, b ->
                val c = a.second.compareTo(b.second)
                if (c != 0) c else compareSpaceIds(a.first, b.first)
            }?.first
    }

    private fun unit(a: Aim): Triple<Double, Double, Double> {
        val h = Math.toRadians(a.heading.toDouble())
        val e = Math.toRadians(a.elevation.toDouble())
        return Triple(cos(e) * sin(h), cos(e) * cos(h), sin(e))
    }

    // Persistence: {"<id>":{"h":214.0,"e":8.5}, …}

    fun encode(map: Map<String, Aim>): String {
        val o = JsonObject()
        map.forEach { (id, aim) ->
            o.add(id, JsonObject().apply {
                addProperty("h", aim.heading)
                addProperty("e", aim.elevation)
            })
        }
        return o.toString()
    }

    fun decode(json: String?): Map<String, Aim> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JsonParser.parseString(json).asJsonObject
            o.entrySet().mapNotNull { (id, v) ->
                val a = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val h = a.get("h")?.asFloat ?: return@mapNotNull null
                id to Aim(h, a.get("e")?.asFloat ?: 0f)
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/**
 * Play on… (SPACE.md §3.5): the phone's audio plays on at most one computer. Every decision returns the
 * new `playingOn` plus the `phoneAudioControl` commands to send.
 */
object PlayExclusivity {
    data class Command(val id: String, val action: String)
    data class Result(val playingOn: String?, val commands: List<Command>)

    /** Phone "Play on X": start on X, stop everywhere else. */
    fun playOn(target: String, connected: List<String>): Result =
        Result(
            target,
            listOf(Command(target, "start")) +
                connected.filterNot { PairedDesktops.sameName(it, target) }.map { Command(it, "stop") }
        )

    /** Phone "Stop": stop on every connected computer (harmless where nothing plays). */
    fun stop(connected: List<String>): Result = Result(null, connected.map { Command(it, "stop") })

    /** QS tile / one-tap: stop if something plays, else play on [target] (the target rule's pick). */
    fun toggle(playingOn: String?, target: String?, connected: List<String>): Result = when {
        playingOn != null -> stop(connected)
        target != null -> playOn(target, connected)
        else -> Result(null, emptyList())
    }

    /** `phoneAudioState {active}` arrived from computer [from]. */
    fun onState(from: String, active: Boolean, playingOn: String?, connected: List<String>): Result = when {
        active -> Result(
            from,
            connected.filterNot { PairedDesktops.sameName(it, from) }.map { Command(it, "stop") }
        )
        playingOn != null && PairedDesktops.sameName(playingOn, from) -> Result(null, emptyList())
        else -> Result(playingOn, emptyList())
    }

    /** A computer dropped: it can't be playing any more. */
    fun onDisconnected(id: String, playingOn: String?): String? =
        if (playingOn != null && PairedDesktops.sameName(playingOn, id)) null else playingOn
}

/** The space key (SPACE.md §3.2): 32 random bytes, base64, made once by the phone. */
object SpaceKey {
    const val BYTES = 32

    fun generate(random: SecureRandom = SecureRandom()): String {
        val b = ByteArray(BYTES)
        random.nextBytes(b)
        return Base64.getEncoder().encodeToString(b)
    }

    fun isValid(key: String?): Boolean =
        key != null && runCatching { Base64.getDecoder().decode(key).size == BYTES }.getOrDefault(false)
}

/** Builds the extended `desktopsList` (SPACE.md §3.2). */
object SpaceJson {
    const val SPACE_VERSION = 1

    data class PhoneInfo(
        val name: String,
        val model: String?,
        val battery: Int?,
        val charging: Boolean?
    )

    data class DesktopEntry(
        val id: String,
        val name: String,
        val platform: String,
        val type: String?,
        val connected: Boolean,
        val inUse: Boolean,
        val idleSeconds: Int,
        val ips: List<String>,
        val port: Int
    )

    /**
     * @param you the recipient's own id; it never appears in `desktops`
     * @param spaceKey omitted when not loaded yet (a computer keeps the key it already has)
     */
    fun desktopsList(
        you: String?,
        phone: PhoneInfo,
        spaceKey: String?,
        inUse: String?,
        playingOn: String?,
        desktops: List<DesktopEntry>
    ): String {
        val arr = JsonArray()
        desktops.filter { you == null || !PairedDesktops.sameName(it.id, you) }.forEach { d ->
            arr.add(JsonObject().apply {
                addProperty("id", d.id)
                addProperty("name", d.name)
                addProperty("platform", d.platform)
                d.type?.let { addProperty("type", it) }
                addProperty("connected", d.connected)
                addProperty("inUse", d.inUse)
                addProperty("idleSeconds", d.idleSeconds)
                add("ips", JsonArray().apply { d.ips.forEach { add(it) } })
                addProperty("port", d.port)
            })
        }
        val data = JsonObject().apply {
            if (you != null) addProperty("you", you) else add("you", JsonNull.INSTANCE)
            add("phone", JsonObject().apply {
                addProperty("id", DesktopHubIds.PHONE_ID)
                addProperty("name", phone.name)
                phone.model?.let { addProperty("model", it) }
                phone.battery?.let { addProperty("battery", it) }
                phone.charging?.let { addProperty("charging", it) }
            })
            if (spaceKey != null) {
                add("space", JsonObject().apply {
                    addProperty("key", spaceKey)
                    addProperty("v", SPACE_VERSION)
                })
            }
            if (inUse != null) addProperty("inUse", inUse) else add("inUse", JsonNull.INSTANCE)
            if (playingOn != null) addProperty("playingOn", playingOn) else add("playingOn", JsonNull.INSTANCE)
            add("desktops", arr)
        }
        return JsonObject().apply {
            addProperty("type", "desktopsList")
            add("data", data)
        }.toString()
    }
}

/** Ids shared by the pure helpers (kept out of DesktopHub so tests don't load Android classes). */
object DesktopHubIds {
    const val PHONE_ID = "phone"
}

/** A short, human label for a target pick ("In use", "Aimed at", …) used by the UI. */
fun TargetRule.Reason.label(): String = when (this) {
    TargetRule.Reason.EXPLICIT -> "Picked"
    TargetRule.Reason.AIM -> "Pointing at it"
    TargetRule.Reason.IN_USE -> "In use"
    TargetRule.Reason.LAST_USED -> "Last used"
    TargetRule.Reason.PRIMARY -> "Main computer"
}
