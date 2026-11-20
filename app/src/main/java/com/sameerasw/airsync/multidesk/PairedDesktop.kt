package com.sameerasw.airsync.multidesk

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * H.I.B multi-desktop — one computer (Mac or Windows PC) the phone is paired with.
 *
 * The desktop's name is its identity everywhere in the protocol (QR, macInfo, mDNS, UDP, wake-up),
 * so it doubles as the id here too.
 */
data class PairedDesktop(
    val name: String,
    val ips: List<String>,
    val port: Int,
    val key: String,
    val type: String? = null,
    val model: String? = null,
    val addedAt: Long = 0L,
    val lastConnected: Long = 0L,
    /** False when the user turned this computer off on the phone (keeps the pairing, no auto-connect). */
    val enabled: Boolean = true
) {
    val platform: String get() = PairedDesktops.platformOf(type, model)
}

/** Pure list operations, kept free of Android/org.json so they can be unit-tested on the JVM. */
object PairedDesktops {
    const val MAX_IPS = 8

    fun normalizeName(name: String): String = name.replace('’', '\'').trim().lowercase()

    fun sameName(a: String, b: String): Boolean = normalizeName(a) == normalizeName(b)

    /** "windows" for H.I.B for Windows (macInfo type "Windows PC", model "WindowsPC"), else "mac". */
    fun platformOf(type: String?, model: String?): String {
        val t = (type ?: "").lowercase()
        val m = (model ?: "").lowercase()
        return if (t.contains("windows") || m.contains("windows")) "windows" else "mac"
    }

    fun find(list: List<PairedDesktop>, name: String): PairedDesktop? =
        list.firstOrNull { sameName(it.name, name) }

    /**
     * Adds [incoming] or merges it into the entry with the same name: newer IPs first (old ones kept as
     * fallbacks), a non-blank key replaces the old one (re-pairing), type/model are only overwritten
     * when known. Never removes other computers — pairing a new one must not unpair the old.
     */
    fun upsert(list: List<PairedDesktop>, incoming: PairedDesktop): List<PairedDesktop> {
        val existing = find(list, incoming.name)
        if (existing == null) return list + incoming
        val merged = existing.copy(
            name = incoming.name,
            ips = mergeIps(incoming.ips, existing.ips),
            port = if (incoming.port > 0) incoming.port else existing.port,
            key = incoming.key.ifBlank { existing.key },
            type = incoming.type ?: existing.type,
            model = incoming.model ?: existing.model,
            addedAt = if (existing.addedAt > 0) existing.addedAt else incoming.addedAt,
            lastConnected = maxOf(existing.lastConnected, incoming.lastConnected),
            enabled = incoming.enabled
        )
        return list.map { if (it === existing) merged else it }
    }

    fun remove(list: List<PairedDesktop>, name: String): List<PairedDesktop> =
        list.filterNot { sameName(it.name, name) }

    fun update(list: List<PairedDesktop>, name: String, transform: (PairedDesktop) -> PairedDesktop): List<PairedDesktop> =
        list.map { if (sameName(it.name, name)) transform(it) else it }

    fun mergeIps(preferred: List<String>, fallback: List<String>): List<String> =
        (preferred + fallback).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_IPS)

    fun splitIps(csv: String?): List<String> =
        (csv ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** Parsed `airsync://ip[,ip…]:port?name=…?plus=…?key=…` pairing QR (see PROTOCOL.md §2.2). */
data class PairingQr(val ips: List<String>, val port: Int, val name: String?, val isPlus: Boolean, val key: String?) {
    companion object {
        fun parse(raw: String): PairingQr? {
            val text = raw.trim()
            if (!text.startsWith("airsync://", ignoreCase = true)) return null
            val authority = text.substringAfter("://").substringBefore("?")
            val host: String
            val port: Int
            // IPv6 hosts are bracketed; otherwise split on the last ':'
            if (authority.contains(":") && !authority.endsWith("]")) {
                host = authority.substringBeforeLast(":")
                port = authority.substringAfterLast(":").toIntOrNull() ?: 6996
            } else {
                host = authority
                port = 6996
            }
            val ips = PairedDesktops.splitIps(host)
            if (ips.isEmpty()) return null
            val query = text.substringAfter('?', "")
            val params = if (query.isEmpty()) emptyMap() else query.split('?').associate {
                val parts = it.split('=', limit = 2)
                (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
            }
            val name = params["name"]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            return PairingQr(
                ips = ips,
                port = port,
                name = name?.ifBlank { null },
                isPlus = params["plus"]?.toBooleanStrictOrNull() ?: false,
                key = params["key"]?.ifBlank { null }
            )
        }
    }
}

/** JSON persistence for the paired list (org.json — Android only). */
object PairedDesktopCodec {
    fun decode(json: String?): List<PairedDesktop> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                val key = o.optString("key")
                if (name.isBlank()) return@mapNotNull null
                val ipsArr = o.optJSONArray("ips")
                val ips = if (ipsArr != null) (0 until ipsArr.length()).map { ipsArr.optString(it) } else emptyList()
                PairedDesktop(
                    name = name,
                    ips = ips.filter { it.isNotBlank() },
                    port = o.optInt("port", 6996),
                    key = key,
                    type = o.optString("type").ifBlank { null },
                    model = o.optString("model").ifBlank { null },
                    addedAt = o.optLong("addedAt", 0L),
                    lastConnected = o.optLong("lastConnected", 0L),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(list: List<PairedDesktop>): String {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.name)
                put("ips", JSONArray(d.ips))
                put("port", d.port)
                put("key", d.key)
                d.type?.let { put("type", it) }
                d.model?.let { put("model", it) }
                put("addedAt", d.addedAt)
                put("lastConnected", d.lastConnected)
                put("enabled", d.enabled)
            })
        }
        return arr.toString()
    }
}
