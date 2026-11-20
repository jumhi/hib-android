package com.sameerasw.airsync.multidesk

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetAddress

/*
 * H.I.B ADB setup without a terminal (docs/SPACE.md §8): the phone tells each computer what its own
 * Wireless debugging looks like, so the computer never has to find it with mDNS.
 *
 * Pure logic (Gson for JSON, no Android types) so it runs in JVM unit tests. The Android side is
 * [AdbSetupMonitor].
 */

/** `adbSetupState` (SPACE.md §8.1). Ports are null when unknown or not open. */
data class AdbSetupState(
    val developerOptions: Boolean,
    val wirelessDebugging: Boolean,
    val connectPort: Int? = null,
    val pairingPort: Int? = null,
    val pairingName: String? = null
) {
    companion object {
        val OFF = AdbSetupState(developerOptions = false, wirelessDebugging = false)

        /**
         * Puts the raw readings together.
         * @param wirelessSetting `adb_wifi_enabled`, or null when the phone won't let the app read it; then
         *   Wireless debugging counts as on exactly when the phone's own connect service is there.
         *
         * Wireless debugging off means no port is open, whatever NSD still has cached.
         */
        fun build(
            developerOptions: Boolean,
            wirelessSetting: Boolean?,
            connect: AdbService?,
            pairing: AdbService?
        ): AdbSetupState {
            val wireless = wirelessSetting ?: (connect != null)
            return AdbSetupState(
                developerOptions = developerOptions,
                wirelessDebugging = wireless,
                connectPort = if (wireless) connect?.port?.takeIf { it in 1..65535 } else null,
                pairingPort = if (wireless) pairing?.port?.takeIf { it in 1..65535 } else null,
                pairingName = if (wireless && pairing != null && pairing.port in 1..65535) pairing.name else null
            )
        }
    }
}

/** What a computer asked for in `adbSetupRequest` (SPACE.md §8.2). */
enum class AdbSetupOpen(val wire: String) {
    DEVELOPER("developer"),
    WIRELESS("wireless");

    companion object {
        fun fromWire(s: String?): AdbSetupOpen? = entries.firstOrNull { it.wire.equals(s?.trim(), ignoreCase = true) }
    }
}

/** @param watch true = start/renew fast reporting, false = stop it, null = not said (leave as is). */
data class AdbSetupRequest(val open: AdbSetupOpen?, val watch: Boolean?)

object AdbSetupJson {
    const val STATE = "adbSetupState"
    const val REQUEST = "adbSetupRequest"

    /** The `adbSetupState` envelope; unknown ports and names are explicit nulls. */
    fun state(s: AdbSetupState): String = JsonObject().apply {
        addProperty("type", STATE)
        add("data", JsonObject().apply {
            addProperty("developerOptions", s.developerOptions)
            addProperty("wirelessDebugging", s.wirelessDebugging)
            if (s.connectPort != null) addProperty("connectPort", s.connectPort) else add("connectPort", JsonNull.INSTANCE)
            if (s.pairingPort != null) addProperty("pairingPort", s.pairingPort) else add("pairingPort", JsonNull.INSTANCE)
            if (s.pairingName != null) addProperty("pairingName", s.pairingName) else add("pairingName", JsonNull.INSTANCE)
        })
    }.toString()

    /** Parses an `adbSetupRequest` envelope (or its `data`). Null when it isn't valid JSON. */
    fun parseRequest(json: String?): AdbSetupRequest? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
            val open = data.get("open")?.takeIf { it.isJsonPrimitive }?.asString
            val watchEl = data.get("watch")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            val watch = when {
                watchEl == null -> null
                watchEl.isBoolean -> watchEl.asBoolean
                watchEl.isString -> watchEl.asString.trim().lowercase().toBooleanStrictOrNull()
                else -> null
            }
            AdbSetupRequest(AdbSetupOpen.fromWire(open), watch)
        } catch (_: Exception) {
            null
        }
    }
}

/** One resolved `_adb-tls-*._tcp` service seen by NSD on the phone. */
data class AdbService(val name: String, val port: Int, val hosts: List<String> = emptyList())

/**
 * NSD on the phone also sees other phones' Wireless debugging on the same network. The phone's own
 * services resolve to one of its own addresses.
 */
object AdbOwnService {

    /** "FE80::1%wlan0" → "fe80::1"; IPv4 and IPv6 literals in one canonical form. Non-literals → lowercased. */
    fun normalize(address: String): String {
        val bare = address.trim().removePrefix("/").substringBefore('%').lowercase()
        if (bare.isEmpty()) return bare
        val literal = bare.contains(':') || bare.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
        if (!literal) return bare
        return runCatching { InetAddress.getByName(bare).hostAddress?.substringBefore('%')?.lowercase() }
            .getOrNull() ?: bare
    }

    /**
     * The phone's own service among [services] (in the order NSD found them; later = newer):
     * - one that resolved to an address in [localAddresses] (the newest if several);
     * - else, for pairing, one named like the phone's own connect service (pairing-code mode uses that name);
     * - else, only when nothing resolved to a known address and there is exactly one, that one
     *   (some phones don't report the host of their own services).
     * A service that resolved to someone else's address is never the phone's.
     */
    fun pick(services: List<AdbService>, localAddresses: Set<String>, ownName: String? = null): AdbService? {
        val usable = services.filter { it.port in 1..65535 }
        if (usable.isEmpty()) return null
        val local = localAddresses.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        usable.lastOrNull { s -> s.hosts.any { normalize(it) in local } }?.let { return it }
        if (!ownName.isNullOrBlank()) usable.lastOrNull { it.name == ownName }?.let { return it }
        val unknownHost = usable.filter { s -> s.hosts.none { normalize(it).isNotEmpty() } }
        return if (usable.size == 1 && unknownHost.size == 1) unknownHost[0] else null
    }
}

/**
 * What each computer was last told, so a change goes out once per computer and an unchanged state
 * isn't re-sent. Keys are normalized computer names.
 */
class AdbStateLedger {
    private val sent = HashMap<String, AdbSetupState>()

    @Synchronized
    fun needs(computer: String, state: AdbSetupState): Boolean = sent[key(computer)] != state

    @Synchronized
    fun record(computer: String, state: AdbSetupState) {
        sent[key(computer)] = state
    }

    @Synchronized
    fun forget(computer: String) {
        sent.remove(key(computer))
    }

    @Synchronized
    fun computers(): Set<String> = sent.keys.toSet()

    private fun key(computer: String) = PairedDesktops.normalizeName(computer)
}

/**
 * `watch` per computer (SPACE.md §8.2): each `watch:true` keeps fast reporting on for [windowMs] from now,
 * `watch:false` (or the computer leaving) ends that computer's watch. Fast reporting runs while any
 * computer's watch is live.
 */
class AdbWatch(
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val until = HashMap<String, Long>()

    /** Starts or renews; returns when it ends. */
    @Synchronized
    fun renew(computer: String): Long {
        val end = now() + windowMs
        until[key(computer)] = end
        return end
    }

    @Synchronized
    fun stop(computer: String) {
        until.remove(key(computer))
    }

    @Synchronized
    fun isActive(): Boolean {
        prune()
        return until.isNotEmpty()
    }

    @Synchronized
    fun isActive(computer: String): Boolean {
        prune()
        return until.containsKey(key(computer))
    }

    /** When the last live watch ends, or null when none is live. */
    @Synchronized
    fun endsAt(): Long? {
        prune()
        return until.values.maxOrNull()
    }

    private fun prune() {
        val t = now()
        until.entries.removeAll { it.value <= t }
    }

    private fun key(computer: String) = PairedDesktops.normalizeName(computer)

    companion object {
        const val WINDOW_MS = 5 * 60_000L
    }
}
