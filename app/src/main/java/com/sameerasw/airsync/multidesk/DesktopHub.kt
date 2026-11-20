package com.sameerasw.airsync.multidesk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.AppUtil
import com.sameerasw.airsync.utils.ServiceManager
import com.sameerasw.airsync.utils.SyncManager
import com.sameerasw.airsync.utils.WebSocketMessageHandler
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.I.B multi-desktop — the phone as the hub of an ecosystem of computers.
 *
 * The legacy single connection in [WebSocketUtil] stays the *primary* computer (everything on the main
 * screen is about it). Every other paired computer gets its own [DesktopSession]. This object:
 *  - keeps the paired list (DataStore, migrated from the single "last device" on first run);
 *  - routes outgoing messages (see [DesktopRouting]): phone state to everyone, replies to whoever asked;
 *  - dispatches inbound messages from any computer, remembering who is asking;
 *  - receives desktop→phone files ([PhoneFileReceiver]) and relays desktop→desktop files ([RelayTable]);
 *  - answers `desktopsRequest` and pushes `desktopsList` when the set of connected computers changes.
 */
object DesktopHub {
    private const val TAG = "DesktopHub"
    const val PHONE_ID = "phone"

    data class DesktopStatus(
        val name: String,
        val platform: String,
        val type: String?,
        val isPrimary: Boolean,
        /** connected | connecting | offline | authFailed | off */
        val state: String,
        val ip: String?,
        val error: String?
    ) {
        val isConnected: Boolean get() = state == "connected"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    private val client by lazy { DesktopSession.newClient() }
    private val sessions = ConcurrentHashMap<String, DesktopSession>()
    @Volatile private var paired: List<PairedDesktop> = emptyList()
    @Volatile private var paused = false
    @Volatile private var primaryLoaded = false
    @Volatile private var pairedLoaded = false
    @Volatile var primaryName: String? = null
        private set
    @Volatile private var primaryType: String? = null
    @Volatile private var primaryModel: String? = null
    @Volatile private var phoneName: String = "Phone"

    private val source = ThreadLocal<DesktopLink?>()
    @Volatile private var lastRequester: String? = null
    @Volatile private var lastRequestAt = 0L
    private val relays = RelayTable()
    private var pushJob: Job? = null
    private val main = Handler(Looper.getMainLooper())

    private val _desktops = MutableStateFlow<List<DesktopStatus>>(emptyList())
    val desktops: StateFlow<List<DesktopStatus>> = _desktops.asStateFlow()

    /** The legacy primary connection, seen as a link. */
    object PrimaryLink : DesktopLink {
        override val id: String get() = primaryName ?: "primary"
        override val name: String get() = primaryName ?: "Computer"
        override val type: String? get() = primaryType
        override val model: String? get() = primaryModel
        override fun isReady(): Boolean = WebSocketUtil.isWifiConnected()
        override fun send(json: String): Boolean = WebSocketUtil.sendPrimary(json)
    }

    // MARK: - lifecycle

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        val ds = DataStoreManager.getInstance(context.applicationContext)
        scope.launch {
            migrateIfNeeded(ds)
            launch {
                ds.getLastConnectedDevice().collect { last ->
                    primaryName = last?.name
                    primaryType = last?.deviceType
                    primaryModel = last?.model
                    primaryLoaded = true
                    reconcile()
                }
            }
            launch {
                ds.getPairedDesktopsJson().collect { json ->
                    paired = PairedDesktopCodec.decode(json)
                    pairedLoaded = true
                    reconcile()
                }
            }
            launch {
                ds.isAppPaused().collect { p ->
                    paused = p
                    reconcile()
                }
            }
            launch { ds.getDeviceName().collect { if (it.isNotBlank()) phoneName = it } }
            launch {
                WebSocketUtil.connectionState.collect {
                    refresh()
                    pushDesktopsListSoon()
                }
            }
        }
    }

    private suspend fun migrateIfNeeded(ds: DataStoreManager) {
        val existing = ds.getPairedDesktopsJson().first()
        if (existing != null) return
        val last = ds.getLastConnectedDevice().first()
        val seed = mutableListOf<PairedDesktop>()
        if (last != null && !last.symmetricKey.isNullOrBlank()) {
            val net = runCatching { ds.getNetworkDeviceConnection(last.name).first() }.getOrNull()
            val ips = PairedDesktops.mergeIps(
                PairedDesktops.splitIps(last.ipAddress),
                net?.networkConnections?.values?.toList() ?: emptyList()
            )
            seed += PairedDesktop(
                name = last.name,
                ips = ips,
                port = last.port.toIntOrNull() ?: 6996,
                key = last.symmetricKey,
                type = last.deviceType ?: net?.deviceType,
                model = last.model ?: net?.model,
                addedAt = last.lastConnected,
                lastConnected = last.lastConnected
            )
        }
        ds.updatePairedDesktopsJson { PairedDesktopCodec.encode(seed) }
        Log.d(TAG, "Migrated ${seed.size} paired computer(s) into the multi-desktop list")
    }

    @Synchronized
    private fun reconcile() {
        val ctx = appContext ?: return
        if (!primaryLoaded || !pairedLoaded) return
        val primary = primaryName
        val wanted = if (paused) emptyList() else paired.filter {
            it.enabled && it.key.isNotBlank() && (primary == null || !PairedDesktops.sameName(it.name, primary))
        }
        val wantedKeys = wanted.map { PairedDesktops.normalizeName(it.name) }.toSet()
        sessions.keys.filter { it !in wantedKeys }.forEach { k ->
            sessions.remove(k)?.let { s ->
                Log.d(TAG, "Stopping session for ${s.name}")
                s.dispose()
                cleanupFor(ctx, s.id)
            }
        }
        wanted.forEach { d ->
            val k = PairedDesktops.normalizeName(d.name)
            val s = sessions[k]
            if (s == null) {
                Log.d(TAG, "Starting session for ${d.name}")
                DesktopSession(ctx, d, client).also { sessions[k] = it }.start()
            } else {
                if (s.desktop != d) s.update(d)
                if (s.state == DesktopSession.State.STOPPED) s.start()
            }
        }
        refresh()
    }

    // MARK: - paired list management (UI)

    /** Adds (or re-pairs) a computer from its QR code without touching any other pairing. */
    fun addFromQr(context: Context, qr: PairingQr): String? {
        val name = qr.name ?: return null
        val key = qr.key ?: return null
        start(context)
        val d = PairedDesktop(
            name = name, ips = qr.ips, port = qr.port, key = key,
            addedAt = System.currentTimeMillis(), enabled = true
        )
        scope.launch {
            DataStoreManager.getInstance(context).updatePairedDesktopsJson { json ->
                PairedDesktopCodec.encode(PairedDesktops.upsert(PairedDesktopCodec.decode(json), d))
            }
            sessions[PairedDesktops.normalizeName(name)]?.poke(qr.ips)
        }
        return name
    }

    fun remove(context: Context, name: String) {
        scope.launch {
            if (primaryName != null && PairedDesktops.sameName(name, primaryName!!)) {
                WebSocketUtil.disconnect(context, manual = true)
            }
            DataStoreManager.getInstance(context).updatePairedDesktopsJson { json ->
                PairedDesktopCodec.encode(PairedDesktops.remove(PairedDesktopCodec.decode(json), name))
            }
        }
    }

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        if (!enabled) sessions[PairedDesktops.normalizeName(name)]?.stop(sendBye = true)
        scope.launch {
            DataStoreManager.getInstance(context).updatePairedDesktopsJson { json ->
                PairedDesktopCodec.encode(
                    PairedDesktops.update(PairedDesktopCodec.decode(json), name) { it.copy(enabled = enabled) }
                )
            }
        }
    }

    fun reconnectNow(name: String) {
        sessions[PairedDesktops.normalizeName(name)]?.poke()
    }

    /** Wake-up request (HTTP/UDP) from a computer that is not the primary. Returns true if handled here. */
    fun handleWakeup(context: Context, macName: String, ip: String, port: Int): Boolean {
        start(context)
        val p = primaryName
        if (p != null && PairedDesktops.sameName(p, macName)) return false
        val d = PairedDesktops.find(paired, macName) ?: return false
        if (p == null) return false // no primary yet: let the legacy flow make it the primary
        if (!d.enabled) setEnabled(context, d.name, true)
        sessions[PairedDesktops.normalizeName(d.name)]?.poke(listOf(ip))
        Log.d(TAG, "Wake-up for secondary computer ${d.name} at $ip:$port")
        return true
    }

    // MARK: - routing

    fun currentSourceId(): String? = source.get()?.id

    fun anySecondaryConnected(): Boolean = sessions.values.any { it.isReady() }

    fun connectedLinks(): List<DesktopLink> {
        val out = mutableListOf<DesktopLink>()
        if (primaryName != null && PrimaryLink.isReady()) out += PrimaryLink
        sessions.values.filter { it.isReady() }.sortedBy { it.name.lowercase() }.forEach { out += it }
        return out
    }

    fun findLink(id: String?): DesktopLink? {
        if (id.isNullOrBlank()) return null
        val p = primaryName
        if (p != null && PairedDesktops.sameName(p, id)) return PrimaryLink
        return sessions[PairedDesktops.normalizeName(id)]
    }

    /** Sends to one computer by name; null/unknown → the primary (with its fallbacks). */
    fun sendTo(id: String?, json: String): Boolean {
        val link = findLink(id)
        return when {
            link == null -> route(json)
            link === PrimaryLink -> WebSocketUtil.sendPrimaryOrBle(json)
            else -> link.send(json)
        }
    }

    /** Entry point for WebSocketUtil.sendMessage: picks the computer(s) for this message. */
    fun route(json: String): Boolean {
        val type = DesktopRouting.messageType(json)
        val requester = lastRequester?.takeIf { System.currentTimeMillis() - lastRequestAt < DesktopRouting.REQUESTER_WINDOW_MS }
        return when (val r = DesktopRouting.decide(type, source.get()?.id, requester)) {
            DesktopRouting.Route.All -> {
                var ok = WebSocketUtil.sendPrimaryOrBle(json)
                sessions.values.forEach { s -> if (s.isReady() && s.send(json)) ok = true }
                ok
            }
            is DesktopRouting.Route.Only -> {
                val link = findLink(r.desktopId)
                when {
                    link == null || link === PrimaryLink -> WebSocketUtil.sendPrimaryOrBle(json)
                    else -> link.send(json)
                }
            }
            DesktopRouting.Route.Primary -> {
                if (WebSocketUtil.isPrimarySocketOpen()) WebSocketUtil.sendPrimaryOrBle(json)
                else defaultSecondary()?.send(json) ?: WebSocketUtil.sendPrimaryOrBle(json)
            }
        }
    }

    private fun defaultSecondary(): DesktopSession? =
        sessions.values.filter { it.isReady() }.maxByOrNull { s ->
            PairedDesktops.find(paired, s.name)?.lastConnected ?: 0L
        }

    // MARK: - inbound

    private val FILE_TYPES = setOf(
        "fileTransferInit", "fileChunk", "fileTransferComplete", "fileTransferCancel", "fileChunkAck", "transferVerified"
    )

    /** Every decrypted message from any computer comes through here (primary included). */
    fun dispatchInbound(context: Context, link: DesktopLink, json: String) {
        val type = DesktopRouting.messageType(json)
        if (type != null && type in FILE_TYPES) {
            try {
                if (handleFileMessage(context, link, type, json)) return
            } catch (e: Exception) {
                Log.e(TAG, "File message $type failed: ${e.message}")
                return
            }
        }
        val previous = source.get()
        source.set(link)
        if (DesktopRouting.countsAsRequest(type)) {
            lastRequester = link.id
            lastRequestAt = System.currentTimeMillis()
        }
        try {
            val isPrimary = link === PrimaryLink
            when {
                type == "desktopsRequest" -> link.send(desktopsListJson(link))
                !isPrimary && (type == "macInfo" || type == "status" || type == "macVolume") -> {
                    // A secondary computer's own media/volume state isn't shown on the phone (the main
                    // screen is about the primary); its macInfo was already consumed by the session.
                }
                !isPrimary && type == "disconnectRequest" -> {
                    Log.d(TAG, "${link.name} asked the phone to disconnect")
                    setEnabled(context, link.name, false)
                }
                else -> {
                    if (type == "clipboardUpdate" || type == "clipboardImageUpdate") {
                        // Universal clipboard across every computer: forward to the others.
                        connectedLinks().filter { it.id != link.id }.forEach { it.send(json) }
                    }
                    if (isPrimary && type == "macInfo") onPrimaryMacInfo(context, json)
                    WebSocketMessageHandler.handleIncomingMessage(context, json)
                }
            }
        } finally {
            source.set(previous)
        }
    }

    private fun handleFileMessage(context: Context, link: DesktopLink, type: String, json: String): Boolean {
        val envelope = JSONObject(json)
        val data = envelope.optJSONObject("data") ?: JSONObject()
        val id = data.optString("id")
        when (type) {
            "fileTransferInit" -> {
                val relayTo = data.optString("relayTo").trim()
                if (relayTo.isNotEmpty() && relayTo != PHONE_ID) {
                    startRelay(context, link, relayTo, data)
                } else {
                    PhoneFileReceiver.onInit(context, link, data)
                }
                return true
            }
            "fileChunk" -> {
                val e = relays.get(id)
                if (e != null && e.sourceId == link.id) {
                    val target = findLink(e.targetId)
                    if (target == null || !target.isReady() || !target.send(json)) failRelay(id, "targetLost")
                } else {
                    PhoneFileReceiver.onChunk(context, link, data)
                }
                return true
            }
            "fileTransferComplete" -> {
                val e = relays.get(id)
                if (e != null && e.sourceId == link.id) {
                    findLink(e.targetId)?.send(json)
                    relays.markComplete(id)
                    Log.d(TAG, "Relayed ${e.name} from ${e.sourceId} to ${e.targetId}")
                } else {
                    PhoneFileReceiver.onComplete(context, link, data)
                }
                return true
            }
            "fileChunkAck", "transferVerified" -> {
                val e = relays.get(id) ?: return false // not a relay: FlickSender's acks
                if (e.targetId == link.id) findLink(e.sourceId)?.send(json)
                if (type == "transferVerified") relays.remove(id)
                return true
            }
            "fileTransferCancel" -> {
                val e = relays.remove(id)
                if (e != null) {
                    val other = if (e.sourceId == link.id) e.targetId else e.sourceId
                    findLink(other)?.send(json)
                    return true
                }
                return PhoneFileReceiver.onCancel(context, data)
            }
        }
        return false
    }

    private fun startRelay(context: Context, from: DesktopLink, relayTo: String, data: JSONObject) {
        val id = data.optString("id")
        relays.prune().forEach { Log.d(TAG, "Pruned relay ${it.id}") }
        val target = findLink(relayTo)
        if (target == null || !target.isReady() || target.id == from.id) {
            from.send(PhoneFileReceiver.cancelJson(id, "targetOffline"))
            return
        }
        val name = data.optString("name")
        val mime = data.optString("mime")
        val size = data.optLong("size", 0L)
        relays.start(id, from.id, target.id, name, size)

        // A flying card on the receiving computer (Flick), labelled with who sent it.
        val flick = JSONObject().put("type", "flickStart").put("data", JSONObject().apply {
            put("id", id)
            put("kind", kindForMime(mime))
            put("name", name)
            put("mime", mime)
            put("size", size)
            put("exitXRatio", 0.5)
            put("velocity", 1800.0)
            put("angle", 0.0)
            put("from", from.name)
            put("fromPlatform", from.platform)
        })
        target.send(flick.toString())

        data.remove("relayTo")
        data.put("relayFrom", from.name)
        data.put("relayFromPlatform", from.platform)
        if (!data.has("flickId")) data.put("flickId", id)
        target.send(JSONObject().put("type", "fileTransferInit").put("data", data).toString())
        Log.d(TAG, "Relaying $name (${size}B) ${from.name} → ${target.name}")
        toast(context, "Passing $name from ${from.name} to ${target.name}")
    }

    private fun failRelay(id: String, reason: String) {
        val e = relays.remove(id) ?: return
        val cancel = PhoneFileReceiver.cancelJson(id, reason)
        findLink(e.sourceId)?.send(cancel)
        findLink(e.targetId)?.send(cancel)
    }

    fun kindForMime(mime: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        else -> "file"
    }

    private fun cleanupFor(context: Context, desktopId: String) {
        PhoneFileReceiver.onSenderGone(context, desktopId)
        relays.involving(desktopId).forEach { failRelay(it.id, "peerLost") }
    }

    // MARK: - session callbacks

    fun onSessionConnected(session: DesktopSession, macInfo: JSONObject) {
        val ctx = appContext ?: return
        Log.d(TAG, "Connected to ${session.name} (${session.platform})")
        scope.launch {
            val ds = DataStoreManager.getInstance(ctx)
            ds.updatePairedDesktopsJson { json ->
                PairedDesktopCodec.encode(
                    PairedDesktops.update(PairedDesktopCodec.decode(json), session.name) {
                        it.copy(
                            ips = PairedDesktops.mergeIps(listOfNotNull(session.connectedIp), it.ips),
                            type = session.type ?: it.type,
                            model = session.model ?: it.model,
                            lastConnected = System.currentTimeMillis()
                        )
                    }
                )
            }
        }
        runCatching { SyncManager.startPeriodicSync(ctx) }
        runCatching { SyncManager.checkAndSyncDeviceStatus(ctx, forceSync = true) }
        runCatching { ServiceManager.updateServiceState(ctx) }
        syncIconsTo(ctx, session, macInfo)
        refresh()
        pushDesktopsListSoon()
    }

    private fun syncIconsTo(ctx: Context, session: DesktopSession, macInfo: JSONObject) {
        scope.launch {
            try {
                val saved = mutableSetOf<String>()
                macInfo.optJSONArray("savedAppPackages")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { saved += it }
                }
                val android = AppUtil.getLauncherPackageNames(ctx)
                val fetch = saved.isEmpty() || saved != android.toSet()
                SyncManager.sendOptimizedAppIcons(ctx, android, fetchIcons = fetch, target = session)
            } catch (e: Exception) {
                Log.w(TAG, "Icon sync to ${session.name} failed: ${e.message}")
            }
        }
    }

    fun onSessionChanged(session: DesktopSession) {
        val ctx = appContext
        if (ctx != null && !session.isReady()) cleanupFor(ctx, session.id)
        refresh()
        pushDesktopsListSoon()
    }

    private fun onPrimaryMacInfo(context: Context, json: String) {
        scope.launch {
            try {
                val data = JSONObject(json).optJSONObject("data") ?: JSONObject()
                val ds = DataStoreManager.getInstance(context)
                val last = ds.getLastConnectedDevice().first()
                val name = data.optString("name").ifBlank { last?.name ?: "" }
                if (name.isBlank()) return@launch
                val key = WebSocketUtil.currentKeyString ?: last?.symmetricKey ?: return@launch
                val type = data.optString("type", data.optString("categoryType")).ifBlank { null }
                val model = data.optString("model", data.optString("modelIdentifier")).ifBlank { null }
                primaryType = type ?: primaryType
                primaryModel = model ?: primaryModel
                val ips = PairedDesktops.mergeIps(
                    listOfNotNull(WebSocketUtil.currentIpAddress),
                    PairedDesktops.splitIps(WebSocketUtil.currentIpList)
                )
                val d = PairedDesktop(
                    name = name, ips = ips, port = WebSocketUtil.currentPortValue ?: 6996, key = key,
                    type = type, model = model, addedAt = System.currentTimeMillis(),
                    lastConnected = System.currentTimeMillis(), enabled = true
                )
                ds.updatePairedDesktopsJson { j ->
                    PairedDesktopCodec.encode(PairedDesktops.upsert(PairedDesktopCodec.decode(j), d))
                }
                refresh()
                pushDesktopsListSoon()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't record primary computer: ${e.message}")
            }
        }
    }

    // MARK: - status + desktopsList

    fun refresh() {
        val out = mutableListOf<DesktopStatus>()
        val p = primaryName
        if (p != null) {
            val st = when {
                WebSocketUtil.isWifiConnected() -> "connected"
                WebSocketUtil.isConnecting() -> "connecting"
                else -> "offline"
            }
            out += DesktopStatus(
                p, PairedDesktops.platformOf(primaryType, primaryModel), primaryType, true, st,
                if (st == "connected") WebSocketUtil.currentIpAddress else null, null
            )
        }
        paired.filter { p == null || !PairedDesktops.sameName(it.name, p) }
            .sortedBy { it.name.lowercase() }
            .forEach { d ->
                val s = sessions[PairedDesktops.normalizeName(d.name)]
                val st = when {
                    !d.enabled || paused -> "off"
                    s == null -> "offline"
                    s.state == DesktopSession.State.CONNECTED -> "connected"
                    s.state == DesktopSession.State.CONNECTING -> "connecting"
                    s.state == DesktopSession.State.AUTH_FAILED -> "authFailed"
                    else -> "offline"
                }
                out += DesktopStatus(
                    d.name, PairedDesktops.platformOf(s?.type ?: d.type, s?.model ?: d.model), s?.type ?: d.type,
                    false, st, s?.connectedIp, s?.lastError
                )
            }
        _desktops.value = out
    }

    fun desktopsListJson(recipient: DesktopLink?): String {
        val arr = JSONArray()
        desktops.value.filter { recipient == null || !PairedDesktops.sameName(it.name, recipient.id) }.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.name)
                put("name", d.name)
                put("platform", d.platform)
                d.type?.let { put("type", it) }
                put("connected", d.isConnected)
            })
        }
        return JSONObject().put("type", "desktopsList").put("data", JSONObject().apply {
            put("phone", JSONObject().put("id", PHONE_ID).put("name", phoneName))
            put("desktops", arr)
        }).toString()
    }

    private fun pushDesktopsListSoon() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(800)
            connectedLinks().forEach { it.send(desktopsListJson(it)) }
        }
    }

    private fun toast(context: Context, text: String) {
        if (!com.sameerasw.airsync.AirSyncApp.isAppForeground()) return
        main.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
