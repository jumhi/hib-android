package com.sameerasw.airsync.multidesk

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/*
 * H.I.B Home — what each computer told the phone about itself (battery, now playing) and the last text it
 * copied. Every computer is equal here: the primary connection and the other paired computers feed the
 * same table, so Home and the widgets can show any of them the same way.
 *
 * Pure logic (Gson, no Android types) so it runs in JVM unit tests.
 */

/** One computer's last `status` (PROTOCOL.md §6.2, D→P). */
data class ComputerLive(
    /** 0…100, or null when unknown or the computer has no battery (`level: -1`). */
    val batteryLevel: Int? = null,
    val charging: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    /** 0…100, or -1 when unknown. */
    val volume: Int = -1,
    val muted: Boolean = false,
    /** The small (80×80) album art as base64, when the computer sends it. */
    val artLite: String? = null,
    val at: Long = 0L
) {
    val hasMedia: Boolean get() = title.isNotBlank() || artist.isNotBlank()

    /** "Title — Artist", "Title" or "Artist"; null when nothing is loaded. */
    val nowPlaying: String?
        get() = when {
            title.isNotBlank() && artist.isNotBlank() -> "${title.trim()} — ${artist.trim()}"
            title.isNotBlank() -> title.trim()
            artist.isNotBlank() -> artist.trim()
            else -> null
        }
}

/** The last text a computer put on the universal clipboard. */
data class ComputerClip(val computer: String, val text: String, val at: Long)

object LiveStatusParser {

    /** Parses a `status` envelope (or its `data`). Null when it isn't one. Lenient: a bad field is skipped. */
    fun parse(json: String, now: Long): ComputerLive? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        val data = if (root.has("type")) {
            if (root.str("type") != "status") return null
            root.obj("data") ?: return null
        } else root
        val battery = data.obj("battery")
        val music = data.obj("music")
        if (battery == null && music == null) return null
        val level = battery?.int("level")
        return ComputerLive(
            batteryLevel = level?.takeIf { it in 0..100 },
            charging = battery?.bool("isCharging") ?: false,
            playing = music?.bool("isPlaying") ?: false,
            title = music?.str("title").orEmpty(),
            artist = music?.str("artist").orEmpty(),
            volume = music?.int("volume")?.takeIf { it in 0..100 } ?: -1,
            muted = music?.bool("isMuted") ?: false,
            artLite = music?.str("albumArtLite")?.takeIf { it.isNotBlank() },
            at = now
        )
    }

    /** The text of a `clipboardUpdate` envelope, or null. */
    fun clipboardText(json: String): String? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        if (root.str("type") != "clipboardUpdate") return null
        return root.obj("data")?.str("text")?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.el(k: String): JsonElement? = get(k)?.takeIf { !it.isJsonNull }
    private fun JsonObject.obj(k: String): JsonObject? = el(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(k: String): String? = el(k)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(k: String): Int? =
        el(k)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble.toInt() }.getOrNull() }
    private fun JsonObject.bool(k: String): Boolean? =
        el(k)?.takeIf { it.isJsonPrimitive }?.let { p ->
            val prim = p.asJsonPrimitive
            if (prim.isBoolean) prim.asBoolean else prim.asString.equals("true", ignoreCase = true)
        }
}

/**
 * A computer the user picked on purpose from a device card ("Desk Deck", "Remote", "Flick" on *that* card).
 * It is the target rule's explicit step (SPACE.md §1.4): it beats pointing and the computer in use. It holds
 * while a point-aware screen stays open, then for [graceMs] after the last one closes (or after the pick,
 * for one-shot actions like Flick), and only while that computer is connected.
 */
class ExplicitPick(private val graceMs: Long = GRACE_MS) {
    private var name: String? = null
    private var at = 0L
    private var trackingEndedAt = 0L

    fun set(computer: String, now: Long) {
        name = computer
        at = now
    }

    fun clear() {
        name = null
    }

    /** The last point-aware screen closed: the grace period starts now. */
    fun onTrackingEnded(now: Long) {
        trackingEndedAt = now
    }

    fun current(now: Long, tracking: Boolean, connected: Collection<String>): String? {
        val n = name ?: return null
        val since = maxOf(at, trackingEndedAt)
        if (!tracking && now - since > graceMs) {
            name = null
            return null
        }
        return connected.firstOrNull { PairedDesktops.sameName(it, n) }
    }

    companion object {
        const val GRACE_MS = 3 * 60_000L
    }
}
