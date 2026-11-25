package com.sameerasw.airsync.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.round

/** One display a computer offers to the Remote (`remoteHello.displays`, ids from SPACE.md §7.1). */
data class RemoteDisplay(val id: String, val name: String, val w: Int, val h: Int, val main: Boolean)

/** `remoteHello` (SPACE.md §15.1): what a computer lets the Remote do. */
data class RemoteHello(
    val v: Int,
    val id: String,
    val platform: String,
    val screen: Boolean,
    val control: Boolean,
    /** ready | needsSetup | unsupported | managed */
    val unlock: String,
    val locked: Boolean,
    val displays: List<RemoteDisplay>,
    val addrs: List<String>
) {
    val mainDisplay: RemoteDisplay? get() = displays.firstOrNull { it.main } ?: displays.firstOrNull()

    companion object {
        const val UNLOCK_READY = "ready"
        const val UNLOCK_NEEDS_SETUP = "needsSetup"
        const val UNLOCK_UNSUPPORTED = "unsupported"
        const val UNLOCK_MANAGED = "managed"
    }
}

data class RemoteFocus(val text: Boolean, val secure: Boolean)

/** `unlockResult` (SPACE.md §15.1). [error] is null on success. */
data class UnlockResult(val ok: Boolean, val error: String?)

/**
 * The Phone Remote's wire messages (SPACE.md §15.1 and §15.3): parsing what computers send and building what
 * the phone sends. Pure (Gson only) so it is unit-tested on the JVM.
 */
object RemoteJson {
    // MARK: - inbound (computer → phone)

    /** The inbound `/socket` types the Remote handles. */
    val INBOUND = setOf(
        "remoteHello", "remoteLockState", "remoteFocus",
        "unlockChallenge", "unlockResult", "unlockEnrollRequest", "unlockEnrollResult"
    )

    fun data(json: String?): JsonObject? = try {
        if (json.isNullOrBlank()) null
        else {
            val root = JsonParser.parseString(json).asJsonObject
            root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        }
    } catch (_: Exception) {
        null
    }

    fun type(json: String?): String? = try {
        if (json.isNullOrBlank()) null
        else JsonParser.parseString(json).asJsonObject.get("type")?.takeIf { it.isJsonPrimitive }?.asString
    } catch (_: Exception) {
        null
    }

    /** [fallbackId] is the computer the message came from (its desktop name, SPACE.md §2) when `id` is missing. */
    fun hello(json: String?, fallbackId: String): RemoteHello? {
        val d = data(json) ?: return null
        val displays = d.arr("displays")?.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            RemoteDisplay(id, o.str("name").orEmpty(), o.int("w"), o.int("h"), o.bool("main") ?: false)
        }.orEmpty()
        return RemoteHello(
            v = d.int("v").takeIf { it > 0 } ?: 1,
            id = d.str("id")?.takeIf { it.isNotBlank() } ?: fallbackId,
            platform = d.str("platform") ?: "",
            screen = d.bool("screen") ?: false,
            control = d.bool("control") ?: true,
            unlock = d.str("unlock") ?: RemoteHello.UNLOCK_UNSUPPORTED,
            locked = d.bool("locked") ?: false,
            displays = displays,
            addrs = d.arr("addrs")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim()?.ifBlank { null } }
                .orEmpty().filter { TailscaleAddrs.isTailscale(it) }.distinct()
        )
    }

    fun locked(json: String?): Boolean? = data(json)?.bool("locked")

    fun focus(json: String?): RemoteFocus? {
        val d = data(json) ?: return null
        val text = d.bool("text") ?: return null
        return RemoteFocus(text, d.bool("secure") ?: false)
    }

    fun challengeNonce(json: String?): String? = data(json)?.str("nonce")?.takeIf { it.isNotBlank() }

    fun unlockResult(json: String?): UnlockResult? {
        val d = data(json) ?: return null
        val ok = d.bool("ok") ?: return null
        return UnlockResult(ok, d.str("error")?.ifBlank { null })
    }

    fun enrollName(json: String?): String? = data(json)?.str("name")?.ifBlank { null }

    fun enrollOk(json: String?): Boolean = data(json)?.bool("ok") ?: false

    // `/screen` text replies
    data class Opened(val display: String, val w: Int, val h: Int, val codec: String)

    fun opened(json: String?): Opened? {
        val d = data(json) ?: return null
        return Opened(d.str("display").orEmpty(), d.int("w"), d.int("h"), d.str("codec") ?: "h264")
    }

    fun pongT(json: String?): Long? = data(json)?.get("t")?.takeIf { it.isJsonPrimitive }?.let {
        runCatching { it.asLong }.getOrNull()
    }

    fun pausedReason(json: String?): String = data(json)?.str("reason") ?: "stopped"

    // MARK: - outbound (phone → computer)

    fun remoteHelloRequest(): String = envelope("remoteHelloRequest", JsonObject())

    fun unlockRequest(): String = envelope("unlockRequest", JsonObject())

    fun unlockApprove(nonce: String, sig: String): String = envelope("unlockApprove", JsonObject().apply {
        addProperty("nonce", nonce)
        addProperty("sig", sig)
    })

    fun unlockEnroll(phoneId: String, publicKey: String): String = envelope("unlockEnroll", JsonObject().apply {
        addProperty("phoneId", phoneId)
        addProperty("publicKey", publicKey)
    })

    fun screenOpen(
        display: String, maxW: Int, maxH: Int, fps: Int, codec: String, view: ViewRect, route: String?
    ): String = envelope("screenOpen", JsonObject().apply {
        addProperty("display", display)
        addProperty("maxW", maxW)
        addProperty("maxH", maxH)
        addProperty("fps", fps)
        addProperty("codec", codec)
        add("view", view.toJson())
        if (route != null) addProperty("route", route)
    })

    fun screenView(view: ViewRect): String = envelope("screenView", view.toJson())

    fun screenDisplay(display: String): String = envelope("screenDisplay", JsonObject().apply { addProperty("display", display) })

    fun screenKeyframe(): String = envelope("screenKeyframe", JsonObject())

    fun screenPing(t: Long): String = envelope("screenPing", JsonObject().apply { addProperty("t", t) })

    fun screenStats(fps: Int, rttMs: Int, decodeMs: Int): String = envelope("screenStats", JsonObject().apply {
        addProperty("fps", fps)
        addProperty("rttMs", rttMs)
        addProperty("decodeMs", decodeMs)
    })

    /** `remoteControl` (SPACE.md §15.2, PROTOCOL.md), the unchanged input message. */
    fun remoteControl(action: String, fill: JsonObject.() -> Unit = {}): String =
        envelope("remoteControl", JsonObject().apply { addProperty("action", action); fill() })

    private fun ViewRect.toJson() = JsonObject().apply {
        addProperty("x", r4(x)); addProperty("y", r4(y)); addProperty("w", r4(w)); addProperty("h", r4(h))
    }

    private fun r4(v: Float): Double = round(v.toDouble() * 10_000.0) / 10_000.0

    private fun envelope(type: String, data: JsonObject): String =
        JsonObject().apply { addProperty("type", type); add("data", data) }.toString()

    // MARK: - helpers

    private fun JsonObject.prim(k: String): JsonElement? = get(k)?.takeIf { it.isJsonPrimitive }
    private fun JsonObject.str(k: String): String? = prim(k)?.asString
    private fun JsonObject.bool(k: String): Boolean? = prim(k)?.let { runCatching { it.asBoolean }.getOrNull() }
    private fun JsonObject.int(k: String): Int = prim(k)?.let { runCatching { round(it.asDouble).toInt() }.getOrNull() } ?: 0
    private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray && it !is JsonNull }?.asJsonArray
}

/**
 * Tailscale addresses (SPACE.md §15.1, design §6.8): `100.64.0.0/10` and `fd7a:115c:a1e0::/48`. The phone
 * remembers a computer's ones and dials them [DELAY_MS] after its LAN addresses; the first socket to open wins.
 */
object TailscaleAddrs {
    const val DELAY_MS = 1_000L

    fun isTailscale(ip: String): Boolean {
        val s = ip.trim().removeSurrounding("[", "]").substringBefore("%").lowercase()
        if (s.contains(':')) {
            val groups = expandV6(s) ?: return false
            return groups[0] == 0xfd7a && groups[1] == 0x115c && groups[2] == 0xa1e0
        }
        val parts = s.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (parts.any { p -> p.toIntOrNull()?.let { it !in 0..255 } != false }) return false
        return a == 100 && b in 64..127
    }

    /** One address to dial and how long after the start to dial it. */
    data class Dial(val ip: String, val delayMs: Long)

    /**
     * LAN addresses first (now), then the remembered Tailscale ones not already in the list (after [DELAY_MS]).
     * A Tailscale address the LAN list already holds stays where it is (it was learned as a LAN one).
     */
    fun plan(lan: List<String>, tailscale: List<String>): List<Dial> {
        val seen = HashSet<String>()
        val out = mutableListOf<Dial>()
        lan.forEach { ip -> if (ip.isNotBlank() && seen.add(norm(ip))) out += Dial(ip, 0L) }
        tailscale.forEach { ip -> if (ip.isNotBlank() && isTailscale(ip) && seen.add(norm(ip))) out += Dial(ip, DELAY_MS) }
        return out
    }

    private fun norm(ip: String) = ip.trim().removeSurrounding("[", "]").substringBefore("%").lowercase()

    private fun expandV6(s: String): IntArray? {
        val halves = s.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? =
            if (part.isEmpty()) emptyList() else part.split(':').map { g -> g.toIntOrNull(16)?.takeIf { g.length <= 4 } ?: return null }
        val head = groups(halves[0]) ?: return null
        val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
        val missing = 8 - head.size - tail.size
        if (missing < 0 || (halves.size == 1 && missing != 0)) return null
        return (head + List(missing) { 0 } + tail).toIntArray()
    }
}
