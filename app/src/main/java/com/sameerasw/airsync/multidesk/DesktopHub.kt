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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 *  - answers `desktopsRequest` and pushes `desktopsList` when the set of connected computers changes;
 *  - H.I.B Space (docs/SPACE.md): tracks which computer is in use (`desktopActivity`), picks the target
 *    for Flick / Play with the one target rule ([TargetRule]) and shares the space key;
 *  - Point & Flick (SPACE.md §7): keeps every connected computer's screens (`screensInfo`) and tells every
 *    computer the saved screen aims (`screenLayout`, §9.2) after its handshake and when they change;
 *  - ADB setup (SPACE.md §8): hands `adbSetupRequest` to [AdbSetupMonitor], which tells every computer
 *    the phone's own Wireless debugging state (`adbSetupState`).
 */
object DesktopHub {
    private const val TAG = "DesktopHub"
    private const val LAYOUT_AFTER_HANDSHAKE_MS = 2_000L
    const val PHONE_ID = DesktopHubIds.PHONE_ID

    data class DesktopStatus(
        val name: String,
        val platform: String,
        val type: String?,
        val isPrimary: Boolean,
        /** connected | connecting | offline | authFailed | off */
        val state: String,
        val ip: String?,
        val error: String?,
        /** The user is at this computer right now (fresh `desktopActivity` with inUse, and connected). */
        val inUse: Boolean = false,
        /** Seconds since the last keyboard/mouse input there; -1 when unknown. */
        val idleSeconds: Int = -1,
        /** This computer is playing the phone's audio (Play on…). */
        val playing: Boolean = false
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

    // H.I.B Space
    private val activity = ConcurrentHashMap<String, DesktopActivityInfo>()
    private val lastUsed = ConcurrentHashMap<String, Long>()
    @Volatile private var spaceKey: String? = null
    @Volatile private var spaceSignature: String? = null
    private val _inUseId = MutableStateFlow<String?>(null)
    /** The computer the phone considers in use right now (SPACE.md §3.2 `inUse`), or null. */
    val inUseId: StateFlow<String?> = _inUseId.asStateFlow()

    // Point & Flick: each connected computer's screens, by normalized computer name. A computer that
    // never sent `screensInfo` (older version) has no entry.
    private val _screens = MutableStateFlow<Map<String, List<ComputerScreen>>>(emptyMap())
    val screens: StateFlow<Map<String, List<ComputerScreen>>> = _screens.asStateFlow()

    // Shared mouse & keyboard (SPACE.md §9.2): the saved aims as `screenLayout`, and what each computer has.
    private val layout = LayoutLedger()
    private val layoutLock = Any()

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
        AdbSetupMonitor.start(context.applicationContext)
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
                WebSocketUtil.connectionState.collect { connected ->
                    refresh()
                    pushDesktopsListSoon()
                    // The primary's handshake (macInfo) is what flips this to true.
                    if (connected) AdbSetupMonitor.onComputerConnected(PrimaryLink)
                    else AdbSetupMonitor.onComputerGone(PrimaryLink.id)
                    if (connected) onLayoutHandshake(PrimaryLink) else layout.forget(PrimaryLink.id)
                }
            }
            launch {
                // Point setup and "Forget all screens" change the map: every connected computer hears it.
                FlickScreenStore.flow(context.applicationContext)
                    .map { PointJson.screenLayout(it) }
                    .distinctUntilChanged()
                    .collect { json ->
                        layout.set(json)
                        connectedLinks().forEach { sendLayout(it) }
                    }
            }
            launch { ensureSpaceKey() }
            launch {
                runCatching { ds.getLastUsedJson().first() }.getOrNull()?.let { json ->
                    runCatching {
                        val o = JSONObject(json)
                        o.keys().forEach { k -> lastUsed.merge(k, o.optLong(k, 0L)) { a, b -> maxOf(a, b) } }
                    }
                }
            }
            launch {
                // inUse goes stale when a computer stops reporting; re-check now and then.
                while (true) {
                    delay(15_000)
                    refresh()
                }
            }
        }
    }

    private suspend fun ensureSpaceKey(): String? {
        spaceKey?.let { return it }
        val ctx = appContext ?: return null
        return runCatching { DataStoreManager.getInstance(ctx).getOrCreateSpaceKey() }
            .onFailure { Log.w(TAG, "Space key unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.also { spaceKey = it }
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

    /** Sends to one connected computer by name, nowhere else (false when it isn't connected). */
    fun sendToComputer(id: String, json: String): Boolean {
        val link = findLink(id) ?: return false
        return link.isReady() && link.send(json)
    }

    /** Point & Flick: asks every connected computer for its screens (`screensRequest`, SPACE.md §7.3). */
    fun requestScreens() {
        val msg = PointJson.screensRequest()
        connectedLinks().forEach { it.send(msg) }
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
                type == "desktopsRequest" -> sendDesktopsList(link)
                type == "desktopActivity" -> onDesktopActivity(link, json)
                // Both the primary and the other computers (SPACE.md §7.2).
                type == "screensInfo" -> onScreensInfo(link, json)
                // Both the primary and the other computers (SPACE.md §8.2); the reply goes to [link] only.
                type == AdbSetupJson.REQUEST -> AdbSetupMonitor.onRequest(context, link, json)
                type == "flickStart" -> {
                    val data = runCatching { JSONObject(json).optJSONObject("data") }.getOrNull() ?: JSONObject()
                    IncomingFlicks.onFlickStart(context, link, data)
                }
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
        AdbSetupMonitor.onComputerGone(desktopId)
        layout.forget(desktopId)
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
        AdbSetupMonitor.onComputerConnected(session)
        onLayoutHandshake(session)
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
        if (ctx != null && !session.isReady()) {
            cleanupFor(ctx, session.id)
            activity.remove(PairedDesktops.normalizeName(session.name))
            forgetScreens(session.name)
        }
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
        _desktops.value = withSpaceState(out)
        // A computer that dropped re-sends its screens when it comes back.
        out.filter { it.state != "connected" }.forEach { forgetScreens(it.name) }
        recomputeSpace()
    }

    /** Adds in-use / idle / playing to each status (only a connected computer can be in use or playing). */
    private fun withSpaceState(list: List<DesktopStatus>): List<DesktopStatus> {
        val now = System.currentTimeMillis()
        val playing = com.sameerasw.airsync.utils.PhoneAudioRemote.playingOn.value
        return list.map { d ->
            val a = activity[PairedDesktops.normalizeName(d.name)]
            d.copy(
                inUse = d.isConnected && a?.isFreshInUse(now) == true,
                idleSeconds = if (d.isConnected) a?.idleSecondsAt(now) ?: -1 else -1,
                playing = d.isConnected && playing != null && PairedDesktops.sameName(playing, d.name)
            )
        }
    }

    // MARK: - H.I.B Space: activity, target rule, push rules

    private fun onDesktopActivity(link: DesktopLink, json: String) {
        val data = runCatching { JSONObject(json).optJSONObject("data") }.getOrNull() ?: return
        val info = DesktopActivityInfo.from(
            inUse = data.optBoolean("inUse", false),
            idleSeconds = data.optInt("idleSeconds", -1),
            now = System.currentTimeMillis()
        )
        activity[PairedDesktops.normalizeName(link.id)] = info
        refresh()
    }

    private fun onScreensInfo(link: DesktopLink, json: String) {
        val list = ScreensInfo.parse(json) ?: return
        val k = PairedDesktops.normalizeName(link.name)
        if (_screens.value[k] == list) return
        _screens.update { it + (k to list) }
        Log.d(TAG, "${link.name} has ${list.size} screen(s)")
    }

    /** A computer finished its handshake: it gets `screenLayout` shortly after (behind its device info). */
    private fun onLayoutHandshake(link: DesktopLink) {
        layout.forget(link.id)
        scope.launch {
            delay(LAYOUT_AFTER_HANDSHAKE_MS)
            sendLayout(link)
        }
    }

    /** Sends [link] the current `screenLayout` unless it already has it (or the map hasn't loaded yet). */
    private fun sendLayout(link: DesktopLink) = synchronized(layoutLock) {
        if (!link.isReady()) return@synchronized
        val json = layout.pending(link.id) ?: return@synchronized
        if (link.send(json)) {
            layout.record(link.id, json)
            Log.d(TAG, "screenLayout → ${link.name}")
        }
    }

    private fun forgetScreens(name: String) {
        val k = PairedDesktops.normalizeName(name)
        if (_screens.value.containsKey(k)) _screens.update { it - k }
    }

    /** Target-rule candidates: the connected computers with what the phone knows about them. */
    private fun candidates(): List<TargetRule.Candidate> = desktops.value.filter { it.isConnected }.map { d ->
        val k = PairedDesktops.normalizeName(d.name)
        TargetRule.Candidate(
            id = d.name,
            inUse = d.inUse,
            lastActiveAt = activity[k]?.lastActiveAt ?: 0L,
            lastUsedAt = lastUsed[k] ?: 0L,
            isPrimary = d.isPrimary
        )
    }

    /** Where Flick / Play / Send go when nothing else is said (SPACE.md §1.4). Null when nothing is connected. */
    fun targetPick(explicit: String? = null, aimed: String? = null): TargetRule.Pick? =
        TargetRule.pick(candidates(), explicit, aimed)

    fun connectedIds(): List<String> = desktops.value.filter { it.isConnected }.map { it.name }

    /** The phone just sent this computer something on purpose (Flick, Play): it becomes "last used". */
    fun noteUsed(id: String) {
        val k = PairedDesktops.normalizeName(id)
        lastUsed[k] = System.currentTimeMillis()
        val ctx = appContext ?: return
        scope.launch {
            runCatching {
                val o = JSONObject()
                lastUsed.forEach { (key, t) -> o.put(key, t) }
                DataStoreManager.getInstance(ctx).setLastUsedJson(o.toString())
            }
        }
    }

    /** Called when `playingOn` changes (PhoneAudioRemote). */
    fun onPlayingChanged() = refresh()

    @Synchronized
    private fun recomputeSpace() {
        val list = desktops.value
        val connected = list.filter { it.isConnected }
        // A computer that dropped can't be playing any more.
        com.sameerasw.airsync.utils.PhoneAudioRemote.onComputersChanged(connected.map { it.name })
        val inUse = TargetRule.inUseId(candidates())
        if (_inUseId.value != inUse) _inUseId.value = inUse
        val playing = com.sameerasw.airsync.utils.PhoneAudioRemote.playingOn.value
        val sig = buildString {
            append(inUse ?: "-").append('|').append(playing ?: "-").append('|')
            connected.filter { it.inUse }.map { it.name }.sorted().forEach { append(it).append(',') }
        }
        val previous = spaceSignature
        spaceSignature = sig
        if (previous != null && previous != sig) schedulePush(1_000L)
    }

    /** The extended `desktopsList` (SPACE.md §3.2) for [recipient]. Contains the space key: never log it. */
    fun desktopsListJson(recipient: DesktopLink?): String {
        val ctx = appContext
        val p = primaryName
        val discovered = runCatching {
            com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator.discoveredDevices.value
        }.getOrDefault(emptyList())
        val entries = desktops.value.map { d ->
            val pd = PairedDesktops.find(paired, d.name)
            val isPrimary = p != null && PairedDesktops.sameName(p, d.name)
            // The address the phone's session uses first, then every other IP it knows (QR, discovery).
            val seen = discovered.filter { PairedDesktops.sameName(it.name, d.name) }.flatMap { it.ips }
            val ips = if (isPrimary) {
                PairedDesktops.mergeIps(
                    listOfNotNull(if (d.isConnected) WebSocketUtil.currentIpAddress else null),
                    PairedDesktops.mergeIps(pd?.ips ?: emptyList(), PairedDesktops.splitIps(WebSocketUtil.currentIpList) + seen)
                )
            } else {
                PairedDesktops.mergeIps(listOfNotNull(d.ip), (pd?.ips ?: emptyList()) + seen)
            }
            val port = (if (isPrimary) WebSocketUtil.currentPortValue else null) ?: pd?.port ?: 6996
            SpaceJson.DesktopEntry(
                id = d.name, name = d.name, platform = d.platform, type = d.type,
                connected = d.isConnected, inUse = d.inUse, idleSeconds = d.idleSeconds,
                ips = ips, port = port
            )
        }
        val battery = ctx?.let { runCatching { com.sameerasw.airsync.utils.DeviceInfoUtil.getBatteryInfo(it) }.getOrNull() }
        return SpaceJson.desktopsList(
            you = recipient?.id,
            phone = SpaceJson.PhoneInfo(
                name = phoneName,
                model = android.os.Build.MODEL,
                battery = battery?.level,
                charging = battery?.isCharging
            ),
            spaceKey = spaceKey,
            inUse = _inUseId.value,
            playingOn = com.sameerasw.airsync.utils.PhoneAudioRemote.playingOn.value,
            desktops = entries
        )
    }

    private fun sendDesktopsList(link: DesktopLink) {
        scope.launch {
            ensureSpaceKey()
            link.send(desktopsListJson(link))
        }
    }

    private fun pushDesktopsListSoon() = schedulePush(800L)

    private fun schedulePush(delayMs: Long) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(delayMs)
            ensureSpaceKey()
            connectedLinks().forEach { it.send(desktopsListJson(it)) }
        }
    }

    private fun toast(context: Context, text: String) {
        if (!com.sameerasw.airsync.AirSyncApp.isAppForeground()) return
        main.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }
}
