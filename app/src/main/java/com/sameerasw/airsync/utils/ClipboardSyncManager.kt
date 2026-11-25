package com.sameerasw.airsync.utils

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sameerasw.airsync.clip.Clip
import com.sameerasw.airsync.clip.ClipFocusReader
import com.sameerasw.airsync.clip.ClipIds
import com.sameerasw.airsync.clip.ClipJson
import com.sameerasw.airsync.clip.ClipLogWatcher
import com.sameerasw.airsync.clip.ClipPort
import com.sameerasw.airsync.clip.ClipReader
import com.sameerasw.airsync.clip.ClipSyncCore
import com.sameerasw.airsync.clip.LocalClip
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's one clipboard sync component on Android (SPACE.md §16). The rules live in [ClipSyncCore];
 * this object wires it to the clipboard, the links to computers (primary + other sessions, BLE), the
 * background-copy reader (§16.3: log watcher + focus overlay) and the explicit "Send clipboard" paths.
 * Every path that writes the phone's clipboard for a sync goes through here. Never logs clip content.
 */
object ClipboardSyncManager {
    private const val TAG = "HIBClip"
    /** Wait this long after a link becomes ready before catching it up (its handshake settles first). */
    private const val CATCH_UP_DELAY_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val initialized = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val listenerLock = Any()
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    @Volatile private var appContext: Context? = null

    // Callback for clipboard history tracking
    private var onClipboardSent: ((text: String) -> Unit)? = null

    fun setOnClipboardSentCallback(callback: ((text: String) -> Unit)?) {
        onClipboardSent = callback
    }

    private object Port : ClipPort {
        override fun readyLinks(): List<String> {
            val ids = DesktopHub.connectedLinks().map { it.id }.toMutableList()
            // The primary computer over Bluetooth when its WebSocket is down (text only; see WebSocketUtil).
            val primary = DesktopHub.primaryName
            val ble = com.sameerasw.airsync.AirSyncApp.getBleConnectionManager()?.isAuthenticated == true
            if (primary != null && ble && ids.none { PairedDesktops.sameName(it, primary) }) ids += primary
            return ids
        }

        override fun send(linkId: String, clip: Clip): Boolean {
            val link = DesktopHub.findLink(linkId) ?: return false
            val json = ClipJson.encode(clip)
            return if (link === DesktopHub.PrimaryLink) WebSocketUtil.sendPrimaryOrBle(json)
            else link.isReady() && link.send(json)
        }

        override fun apply(clip: Clip): Boolean {
            val ctx = appContext ?: return false
            val png = clip.png
            return if (png != null) {
                val bytes = ClipIds.decodeBase64(png) ?: return false
                ClipboardUtil.setClipboardImage(
                    ctx, bytes, clip.mime ?: "image/png", clip.name ?: "HIB-Clipboard.png", tag = clip.id
                )
            } else {
                ClipboardUtil.setClipboardText(ctx, clip.text ?: return false, tag = clip.id)
            }
        }
    }

    val core = ClipSyncCore(
        port = Port,
        phoneId = DesktopHub.PHONE_ID,
        sameDevice = PairedDesktops::sameName,
        log = { Log.d(TAG, it) }
    )

    /** Once per process (AirSyncApp): follows the "Clipboard sync" setting. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!initialized.compareAndSet(false, true)) return
        val ds = DataStoreManager.getInstance(context.applicationContext)
        scope.launch { ds.getClipboardSyncEnabled().collect { core.enabled = it } }
    }

    // MARK: - watching the phone's clipboard

    /** Starts the clipboard watcher (listener + background-copy reader). Owned by the foreground service. */
    fun startSync(context: Context) {
        init(context)
        if (!listening.compareAndSet(false, true)) return
        scope.launch {
            val enabled = runCatching {
                DataStoreManager.getInstance(context.applicationContext).getClipboardSyncEnabled().first()
            }.getOrDefault(true)
            if (!enabled) {
                listening.set(false)
                Log.d(TAG, "[clip] watcher not started: sync is off")
                return@launch
            }
            main.post {
                synchronized(listenerLock) {
                    if (!listening.get() || clipboardListener != null) return@post
                    val ctx = context.applicationContext
                    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return@post
                    val l = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged(ctx) }
                    cm.addPrimaryClipChangedListener(l)
                    clipboardListener = l
                    ensureLogWatcher(ctx, com.sameerasw.airsync.AirSyncApp.isAppForeground())
                    Log.d(TAG, "[clip] watcher started")
                }
            }
        }
    }

    fun stopSync(context: Context) {
        listening.set(false)
        main.post {
            synchronized(listenerLock) {
                clipboardListener?.let { l ->
                    runCatching {
                        context.applicationContext.getSystemService(ClipboardManager::class.java)
                            ?.removePrimaryClipChangedListener(l)
                    }
                }
                clipboardListener = null
            }
            ClipLogWatcher.stop()
            Log.d(TAG, "[clip] watcher stopped")
        }
    }

    /** H.I.B came to the front (window focus): read the clipboard and (re)start the log reader. */
    fun onAppFocused(context: Context) {
        init(context)
        val ctx = context.applicationContext
        if (listening.get()) ensureLogWatcher(ctx, inFront = true)
        val (tag, stamp) = runCatching { ClipReader.description(context) }.getOrNull() ?: return
        if (core.isOwn(tag) || core.isKnownStamp(stamp)) return
        val s = runCatching { ClipReader.snapshot(context) }.getOrNull() ?: return
        handleSnapshot(ctx, s, ClipSyncCore.Trigger.POLL)
    }

    private fun ensureLogWatcher(ctx: Context, inFront: Boolean) {
        ClipLogWatcher.ensure(ctx, inFront) { onDenied(ctx) }
    }

    /** The log watcher saw Android refuse us a clipboard read: a copy happened in another app. */
    private fun onDenied(ctx: Context) {
        // Our own write also makes Android notify (and refuse) us; that one isn't a copy.
        if (core.recentlyApplied(1_500)) return
        readWithFocus(ctx)
    }

    private fun onClipChanged(ctx: Context) {
        val s = runCatching { ClipReader.snapshot(ctx) }.getOrNull()
        if (s != null) {
            handleSnapshot(ctx, s, ClipSyncCore.Trigger.CALLBACK)
        } else if (!core.recentlyApplied(1_500)) {
            // Told about a change but not allowed to read it: take focus briefly (§16.3).
            readWithFocus(ctx)
        }
    }

    private fun readWithFocus(ctx: Context) {
        ClipFocusReader.read(ctx) { s ->
            if (s != null) handleSnapshot(ctx, s, ClipSyncCore.Trigger.CALLBACK)
            else Log.d(TAG, "[clip] focus read failed; will read when H.I.B is opened")
        }
    }

    private fun handleSnapshot(ctx: Context, s: ClipReader.Snapshot, trigger: ClipSyncCore.Trigger) {
        if (core.isOwn(s.tag) || ClipReader.isOwnImage(ctx, s)) return
        if (s.text == null && s.imageUri == null) return
        scope.launch {
            val local = runCatching { ClipReader.toLocal(ctx, s) }.getOrNull() ?: return@launch
            if (core.onLocal(local, trigger) == ClipSyncCore.Outcome.COPIED) {
                local.text?.let { onClipboardSent?.invoke(it) }
            }
        }
    }

    // MARK: - from computers

    /** A `clipboardUpdate` / `clipboardImageUpdate` from a computer's link. */
    fun onInboundJson(context: Context, fromLink: String, type: String, json: String) {
        val data = runCatching { JSONObject(json).optJSONObject("data") }.getOrNull()
        onInboundData(context, fromLink, type, data, ClipSyncCore.VIA_PHONE)
    }

    fun onInboundData(context: Context, fromLink: String, type: String, data: JSONObject?, via: String) {
        init(context)
        val inc = ClipJson.decode(type, data) ?: return
        val outcome = core.onInbound(fromLink, inc, via)
        if (outcome == ClipSyncCore.Outcome.APPLIED && inc.text != null && inc.png == null) {
            afterTextApplied(context.applicationContext, inc.text)
        }
    }

    /** A clipboard write over BLE (the primary computer while its WebSocket is down). */
    fun onBle(context: Context, payload: String) {
        init(context)
        val inc = ClipJson.decodeBle(payload) ?: return
        val from = DesktopHub.primaryName ?: "ble"
        val outcome = core.onInbound(from, inc, ClipSyncCore.VIA_BLE)
        if (outcome == ClipSyncCore.Outcome.APPLIED && inc.text != null) afterTextApplied(context.applicationContext, inc.text)
    }

    /** A link to a computer became ready (primary handshake or another session): catch it up (§16.2). */
    fun onLinkReady(linkId: String) {
        scope.launch {
            delay(CATCH_UP_DELAY_MS)
            val link = DesktopHub.findLink(linkId) ?: return@launch
            if (link.isReady()) core.onLinkReady(link.id)
        }
    }

    private fun afterTextApplied(context: Context, text: String) {
        WebSocketMessageHandler.notifyClipboardEntry(text)
        // Continue Browsing: a notification when the text is just a link.
        scope.launch {
            val ds = DataStoreManager(context)
            val continueEnabled = runCatching { ds.getContinueBrowsingEnabled().first() }.getOrDefault(true)
            val keepPrevious = runCatching { ds.getKeepPreviousLinkEnabled().first() }.getOrDefault(true)
            if (continueEnabled && WebSocketUtil.isAnyDesktopConnected() && isLinkOnly(text)) {
                NotificationUtil.showContinueBrowsingLink(context, text.trim(), keepPrevious)
            }
        }
    }

    // MARK: - explicit user actions (work even with "Clipboard sync" off)

    /**
     * Notification "Send clipboard", the Quick Settings tile: reads the clipboard (the caller's window
     * must have focus) and sends it to every connected computer. True when at least one got it.
     */
    suspend fun sendClipboardNow(context: Context): Boolean {
        init(context)
        val s = runCatching { ClipReader.snapshot(context) }.getOrNull() ?: return false
        val ctx = context.applicationContext
        return withContext(Dispatchers.IO) {
            val local = ClipReader.toLocal(ctx, s) ?: return@withContext false
            val n = core.explicit(local)
            if (n > 0) local.text?.let { onClipboardSent?.invoke(it) }
            n > 0
        }
    }

    /** Share sheet text: to every connected computer. */
    fun syncTextToDesktop(text: String) {
        if (text.isBlank()) return
        val n = core.explicit(LocalClip(text, null))
        if (n > 0) onClipboardSent?.invoke(text)
    }

    /** The widget's "Send to …": one computer only. */
    fun sendTextTo(target: String?, text: String): Boolean {
        if (text.isEmpty()) return false
        val id = target ?: DesktopHub.connectedLinks().firstOrNull()?.id ?: return false
        return core.explicit(LocalClip(text, null), only = id) > 0
    }

    /** A new screenshot (ScreenshotClipboardMonitor) as a clip, sent as `clipboardImageUpdate`. */
    fun sendScreenshot(bytes: ByteArray, mime: String, name: String): Boolean =
        core.explicit(
            LocalClip(null, Base64.getEncoder().encodeToString(bytes), mime = mime, name = name, legacyImage = true)
        ) > 0

    /** The widget's "Get": a computer's last copy onto the phone, not to be sent out again as a copy. */
    fun writeOwnText(context: Context, text: String): Boolean {
        val tag = ClipIds.random()
        core.noteOwnWrite(tag)
        return ClipboardUtil.setClipboardText(context, text, tag)
    }

    /** A computer's "Send clipboard" file (image) onto the phone's clipboard. */
    fun writeOwnImage(context: Context, bytes: ByteArray, mime: String, name: String): Boolean {
        val tag = ClipIds.random()
        core.noteOwnWrite(tag)
        return ClipboardUtil.setClipboardImage(context, bytes, mime, name, tag)
    }

    // MARK: - link detection for Continue Browsing

    private fun isLinkOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size != 1) return false
        val url = tokens[0]
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("ftp://", ignoreCase = true)
        ) return true
        if (url.startsWith("www.", ignoreCase = true)) return isValidDomainFormat(url.substring(4))
        return isValidDomainFormat(url)
    }

    private fun isValidDomainFormat(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val domainRegex = Regex(
            "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
                "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*" +
                "\\.[a-zA-Z]{2,}$",
            RegexOption.IGNORE_CASE
        )
        return domainRegex.matches(domain)
    }
}
