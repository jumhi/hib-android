package com.sameerasw.airsync.deskdeck

import com.sameerasw.airsync.multidesk.DeckHandover
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PointTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * H.I.B Desk Deck link state.
 * phone -> computer: deckOpen {open}, deckAction {action, arg?}
 * computer -> phone: deckState {...} every 2 s while open, deckActionResult {action, ok, error?}
 *
 * Point to act (SPACE.md §11.1): the deck follows the point target. When it changes, the deck closes on the
 * old computer and opens on the new one, shows only that computer's live state, and every tile goes to the
 * target computer only.
 */
object DeskDeckRemote {

    data class NextEvent(val title: String, val start: Long, val end: Long, val location: String?)

    data class MacStats(
        val cpu: Int = 0,
        val mem: Int = 0,
        val memPressure: String = "normal",
        val battery: Int = -1,
        val charging: Boolean = false,
        val uptimeSec: Long = 0,
        val netUpKBs: Int = 0,
        val netDownKBs: Int = 0,
        val nextEvent: NextEvent? = null,
        val receivedAt: Long = 0L
    )

    data class ActionResult(val action: String, val ok: Boolean, val error: String?, val at: Long)

    private val handover = DeckHandover()

    private val _stats = MutableStateFlow<MacStats?>(null)
    /** The live state of the computer the deck is open on. */
    val stats = _stats.asStateFlow()

    private val _computer = MutableStateFlow<String?>(null)
    /** The computer the deck is open on (null while closed). */
    val computer = _computer.asStateFlow()

    private val _lastResult = MutableStateFlow<ActionResult?>(null)
    val lastResult = _lastResult.asStateFlow()

    /** The deck is on screen and the point target is [target]: open it there (closing it where it was). */
    fun follow(target: String?) {
        if (target == null) return  // nothing decided (yet): stay where it is
        val sends = synchronized(handover) {
            val out = handover.moveTo(target)
            if (out.isNotEmpty()) {
                _computer.value = handover.current
                _stats.value = null  // that computer's state arrives within ~2 s
            }
            out
        }
        sends.forEach { DesktopHub.sendTo(it.computer, it.json()) }
    }

    /** Asks the deck's computer to stream again (the stream stops after a reconnect). */
    fun reopen() {
        val send = synchronized(handover) { handover.reopen() } ?: return
        DesktopHub.sendTo(send.computer, send.json())
    }

    /** The deck left the screen. */
    fun close() {
        val sends = synchronized(handover) {
            val out = handover.close()
            _computer.value = null
            _stats.value = null
            out
        }
        sends.forEach { DesktopHub.sendTo(it.computer, it.json()) }
    }

    fun sendAction(action: String, arg: String? = null): Boolean {
        val data = JSONObject().put("action", action)
        if (!arg.isNullOrBlank()) data.put("arg", arg)
        val msg = JSONObject().put("type", "deckAction").put("data", data).toString()
        val sent = PointTarget.send(msg)
        if (!sent) {
            val who = PointTarget.targetId() ?: "your computer"
            _lastResult.value = ActionResult(action, false, "Couldn't reach $who", System.currentTimeMillis())
        }
        return sent
    }

    fun onDeckState(data: JSONObject) {
        // Only the computer the deck is on (another may still be streaming until its deckOpen{open:false} lands).
        if (!synchronized(handover) { handover.accepts(DesktopHub.currentSourceId()) }) return
        val ev = data.optJSONObject("nextEvent")?.let {
            NextEvent(
                title = it.optString("title", "Event"),
                start = it.optLong("start", 0L),
                end = it.optLong("end", 0L),
                location = it.optString("location").takeIf { s -> s.isNotBlank() }
            )
        }
        _stats.value = MacStats(
            cpu = data.optInt("cpu", 0),
            mem = data.optInt("mem", 0),
            memPressure = data.optString("memPressure", "normal"),
            battery = data.optInt("battery", -1),
            charging = data.optBoolean("charging", false),
            uptimeSec = data.optLong("uptime", 0L),
            netUpKBs = data.optInt("netUp", 0),
            netDownKBs = data.optInt("netDown", 0),
            nextEvent = ev,
            receivedAt = System.currentTimeMillis()
        )
    }

    fun onActionResult(data: JSONObject) {
        _lastResult.value = ActionResult(
            action = data.optString("action"),
            ok = data.optBoolean("ok", true),
            error = data.optString("error").takeIf { it.isNotBlank() },
            at = System.currentTimeMillis()
        )
    }
}
