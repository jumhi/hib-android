package com.sameerasw.airsync.remote

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.DesktopLink
import com.sameerasw.airsync.multidesk.PairedDesktops
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phone Remote (SPACE.md §15): what each computer told the Remote (`remoteHello`, lock state, keyboard focus)
 * and which computer the Remote drives. The Remote always acts on the computer picked on its own chips (or by a
 * card's Remote button), never on the one the phone points at.
 */
object RemoteHub {
    private const val TAG = "RemoteHub"

    /** One computer as the Remote sees it. [focusSeq] grows on every `remoteFocus` so the UI reacts to repeats. */
    data class Computer(
        val name: String,
        val hello: RemoteHello? = null,
        val locked: Boolean = false,
        val focus: RemoteFocus? = null,
        val focusSeq: Int = 0
    )

    private val _computers = MutableStateFlow<Map<String, Computer>>(emptyMap())
    val computers: StateFlow<Map<String, Computer>> = _computers.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    /** The computer the Remote drives (a desktop name), or null before anything was picked. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun key(name: String): String = PairedDesktops.normalizeName(name)

    fun computer(name: String?): Computer? = name?.let { _computers.value[key(it)] }

    /** The Remote drives [name] from now on (chips, or a computer card's Remote button). */
    fun select(name: String) {
        _selected.value = name
    }

    /** The id a computer signs and keys with (SPACE.md §2: its desktop name, as `remoteHello.id` says). */
    fun computerId(name: String): String = computer(name)?.hello?.id?.takeIf { it.isNotBlank() } ?: name

    /** Sends to that computer only (false when it isn't connected). */
    fun send(name: String, json: String): Boolean = DesktopHub.sendToComputer(name, json)

    fun requestHello(name: String): Boolean = send(name, RemoteJson.remoteHelloRequest())

    /** Every Remote message from any computer (called from [DesktopHub.dispatchInbound]). */
    fun onInbound(context: Context, link: DesktopLink, type: String, json: String) {
        val name = link.name
        when (type) {
            "remoteHello" -> {
                val hello = RemoteJson.hello(json, name) ?: return
                RemoteAddrStore.remember(context, name, hello.addrs)
                update(name) { it.copy(hello = hello, locked = hello.locked) }
                Log.d(TAG, "$name: screen=${hello.screen} control=${hello.control} unlock=${hello.unlock} displays=${hello.displays.size}")
            }
            "remoteLockState" -> {
                val locked = RemoteJson.locked(json) ?: return
                update(name) { it.copy(locked = locked, hello = it.hello?.copy(locked = locked), focus = if (locked) null else it.focus) }
                RemoteUnlockFlow.onLockState(name, locked)
            }
            "remoteFocus" -> {
                val focus = RemoteJson.focus(json) ?: return
                update(name) { it.copy(focus = focus, focusSeq = it.focusSeq + 1) }
            }
            "unlockChallenge" -> RemoteJson.challengeNonce(json)?.let { RemoteUnlockFlow.onChallenge(context, name, it) }
            "unlockResult" -> RemoteJson.unlockResult(json)?.let { RemoteUnlockFlow.onResult(name, it) }
            "unlockEnrollRequest" -> RemoteUnlockFlow.onEnrollRequest(context, name, RemoteJson.enrollName(json) ?: name)
            "unlockEnrollResult" -> RemoteUnlockFlow.onEnrollResult(context, name, RemoteJson.enrollOk(json))
        }
    }

    /** A computer dropped: its focus and lock state are stale (the hello stays for the chips' display list). */
    fun onDisconnected(name: String) {
        update(name) { it.copy(focus = null) }
    }

    private fun update(name: String, f: (Computer) -> Computer) {
        val k = key(name)
        _computers.update { m -> m + (k to f(m[k] ?: Computer(name))) }
    }
}

/**
 * Each computer's Tailscale addresses (`remoteHello.addrs`, SPACE.md §15.1), remembered per computer so the phone
 * can dial them when the LAN addresses fail (discovery doesn't cross Tailscale).
 */
object RemoteAddrStore {
    private const val PREFS = "hib_remote"
    private const val KEY = "tailscale_addrs_v1"
    @Volatile private var cache: Map<String, List<String>>? = null

    fun get(context: Context, name: String): List<String> = load(context)[PairedDesktops.normalizeName(name)].orEmpty()

    fun remember(context: Context, name: String, addrs: List<String>) {
        val k = PairedDesktops.normalizeName(name)
        val now = load(context)
        val clean = addrs.filter { TailscaleAddrs.isTailscale(it) }.distinct().take(4)
        if (now[k] == clean) return
        val next = if (clean.isEmpty()) now - k else now + (k to clean)
        cache = next
        val o = JsonObject()
        next.forEach { (n, list) -> o.add(n, JsonArray().apply { list.forEach { add(it) } }) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    private fun load(context: Context): Map<String, List<String>> {
        cache?.let { return it }
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val map = try {
            if (raw == null) emptyMap() else JsonParser.parseString(raw).asJsonObject.entrySet().associate { (k, v) ->
                k to v.asJsonArray.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
            }
        } catch (_: Exception) {
            emptyMap()
        }
        cache = map
        return map
    }
}
