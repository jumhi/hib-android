package com.sameerasw.airsync.home

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.deskdeck.DeskDeckActivity
import com.sameerasw.airsync.domain.model.ConnectedDevice
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktop
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.presentation.ui.activities.FlickActivity
import com.sameerasw.airsync.utils.FlickSender
import com.sameerasw.airsync.utils.PhoneAudioRemote
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * H.I.B Home — what a device card's buttons do. Every action names its computer (the card's), so nothing
 * goes to "the main one" by accident. Pausing and forgetting go through [UndoLedger]: the caller shows a
 * snackbar with Undo for the returned ticket.
 */
object DeviceActions {
    private const val TAG = "DeviceActions"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = UndoLedger()

    /** What Forget removed, kept until its Undo window closes. */
    private val forgotten = HashMap<Long, Pair<PairedDesktop?, ConnectedDevice?>>()

    // MARK: - quick actions

    /** Lock / screensaver / media keys on that computer only. */
    fun remoteControl(computer: String, action: String): Boolean {
        val json = JSONObject()
            .put("type", "remoteControl")
            .put("data", JSONObject().put("action", action))
            .toString()
        return DesktopHub.sendToComputer(computer, json)
    }

    /** Desk Deck opens on this computer (the deck follows the explicit pick until it closes). */
    fun openDeskDeck(context: Context, computer: String) {
        DesktopHub.pickComputer(computer)
        DeskDeckActivity.start(context)
    }

    /** The Remote tab acts on this computer. */
    fun pickForRemote(computer: String) = DesktopHub.pickComputer(computer)

    /** Play here: the phone's audio on this computer, or stop when it already plays here. */
    fun togglePlay(computer: String): Boolean {
        val now = PhoneAudioRemote.playingOn.value
        return if (now != null && PairedDesktops.sameName(now, computer)) PhoneAudioRemote.stop()
        else PhoneAudioRemote.playOn(computer)
    }

    /** Flick here: the Flick screen opens with this computer lit (a pick beats pointing and "in use"). */
    fun flick(context: Context, computer: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        DesktopHub.pickComputer(computer)
        val clip = ClipData.newRawUri("flick", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        context.startActivity(Intent(context, FlickActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Send files: straight to this computer, no throw (they arrive like a flick, in its Downloads).
     * Returns how many started.
     */
    suspend fun sendFiles(context: Context, computer: String, uris: List<Uri>): Int {
        var started = 0
        DesktopHub.noteUsed(computer)
        for (uri in uris) {
            try {
                val (name, size) = meta(context, uri)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val id = UUID.randomUUID().toString()
                val ok = FlickSender.sendFlickStart(
                    id = id,
                    kind = DesktopHub.kindForMime(mime),
                    name = name,
                    mime = mime,
                    size = size,
                    exitXRatio = 0.5f,
                    velocity = 1200f,
                    angle = 0f,
                    thumbnailBase64 = null,
                    text = null,
                    to = computer
                )
                if (ok && FlickSender.startFileTransfer(context, id, uri, name, mime, to = computer)) started++
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't send a file to $computer: ${e.message}")
            }
        }
        return started
    }

    private fun meta(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }?.let { name = it }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
        }
        if (size < 0) size = FlickSender.querySize(context, uri)
        return name to size
    }

    // MARK: - pause / resume / forget (never one tap: the caller confirms first)

    /** Pauses one computer's connection. It stays paired and won't reconnect by itself. */
    fun pauseComputer(context: Context, card: DeviceCardUi): UndoTicket {
        val app = context.applicationContext
        if (card.viaPrimaryLink) {
            WebSocketUtil.stopAutoReconnect(app)
            WebSocketUtil.disconnect(app, manual = true)
        } else {
            DesktopHub.setEnabled(app, card.name, false)
        }
        return ledger.record(UndoKind.PAUSE_COMPUTER, card.name, card.viaPrimaryLink, System.currentTimeMillis())
    }

    /** Resume / Connect on a card. [connectPrimary] reconnects the primary connection the Home way. */
    fun resumeComputer(context: Context, card: DeviceCardUi, connectPrimary: () -> Unit) {
        val app = context.applicationContext
        if (card.viaPrimaryLink) {
            scope.launch { DataStoreManager.getInstance(app).setUserManuallyDisconnected(false) }
            connectPrimary()
        } else {
            DesktopHub.setEnabled(app, card.name, true)
            DesktopHub.reconnectNow(card.name)
        }
    }

    /** H.I.B as a whole was paused (by the caller, through the view model). */
    fun recordPauseAll(): UndoTicket = ledger.record(UndoKind.PAUSE_ALL, null, false, System.currentTimeMillis())

    /** Forgets a computer (after the red confirmation). Undo puts the pairing back. */
    suspend fun forget(context: Context, card: DeviceCardUi): UndoTicket {
        val app = context.applicationContext
        val record = DesktopHub.pairedRecord(card.name)
        val last = if (card.viaPrimaryLink) {
            runCatching { DataStoreManager.getInstance(app).getLastConnectedDevice().first() }.getOrNull()
        } else null
        DesktopHub.remove(app, card.name)
        val t = ledger.record(UndoKind.FORGET, card.name, card.viaPrimaryLink, System.currentTimeMillis())
        synchronized(forgotten) { forgotten[t.id] = record to last }
        return t
    }

    /** The snackbar closed without Undo. */
    fun dismiss(ticket: UndoTicket) {
        ledger.dismiss(ticket.id)
        synchronized(forgotten) { forgotten.remove(ticket.id) }
    }

    /**
     * Undo pressed. Returns false when it was too late. [connectPrimary] reconnects the primary connection;
     * [resumeAll] resumes H.I.B as a whole.
     */
    fun undo(context: Context, ticket: UndoTicket, connectPrimary: () -> Unit, resumeAll: () -> Unit): Boolean {
        val t = ledger.undo(ticket.id, System.currentTimeMillis()) ?: return false
        val app = context.applicationContext
        val saved = synchronized(forgotten) { forgotten.remove(t.id) }
        scope.launch {
            val ds = DataStoreManager.getInstance(app)
            for (step in UndoPlan.steps(t)) {
                when (step) {
                    UndoStep.CLEAR_MANUAL_DISCONNECT -> ds.setUserManuallyDisconnected(false)
                    UndoStep.CONNECT_PRIMARY -> launch(Dispatchers.Main) { connectPrimary() }
                    UndoStep.ENABLE_COMPUTER -> t.computer?.let { DesktopHub.setEnabled(app, it, true) }
                    UndoStep.RECONNECT_COMPUTER -> t.computer?.let { DesktopHub.reconnectNow(it) }
                    UndoStep.RESUME_ALL -> launch(Dispatchers.Main) { resumeAll() }
                    UndoStep.RESTORE_PAIRING -> saved?.first?.let { DesktopHub.restorePairing(app, it) }
                    UndoStep.RESTORE_PRIMARY_RECORD -> saved?.second?.let { ds.saveLastConnectedDevice(it) }
                }
            }
        }
        return true
    }
}

/**
 * Which computer Home should open its quick actions for (a tap on that computer in the Devices widget).
 * MainActivity sets it from the intent; Home shows that computer's card in a sheet and clears it.
 */
object HomeFocus {
    const val EXTRA_COMPUTER = "com.sameerasw.airsync.extra.FOCUS_COMPUTER"

    private val _computer = MutableStateFlow<String?>(null)
    val computer: StateFlow<String?> = _computer.asStateFlow()

    fun fromIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_COMPUTER)?.takeIf { it.isNotBlank() }?.let { _computer.value = it }
    }

    fun clear() {
        _computer.value = null
    }
}
