package com.sameerasw.airsync.deskdeck

import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * H.I.B Desk Deck link state.
 * phone -> Mac: deckOpen {open}, deckAction {action, arg?}
 * Mac -> phone: deckState {...} every 2 s while open, deckActionResult {action, ok, error?}
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

    private val _stats = MutableStateFlow<MacStats?>(null)
    val stats = _stats.asStateFlow()

    private val _lastResult = MutableStateFlow<ActionResult?>(null)
    val lastResult = _lastResult.asStateFlow()

    fun setOpen(open: Boolean): Boolean {
        val msg = JSONObject()
            .put("type", "deckOpen")
            .put("data", JSONObject().put("open", open))
            .toString()
        return WebSocketUtil.sendMessage(msg)
    }

    fun sendAction(action: String, arg: String? = null): Boolean {
        val data = JSONObject().put("action", action)
        if (!arg.isNullOrBlank()) data.put("arg", arg)
        val msg = JSONObject().put("type", "deckAction").put("data", data).toString()
        val sent = WebSocketUtil.sendMessage(msg)
        if (!sent) {
            _lastResult.value = ActionResult(action, false, "Not connected to your computer", System.currentTimeMillis())
        }
        return sent
    }

    fun onDeckState(data: JSONObject) {
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
