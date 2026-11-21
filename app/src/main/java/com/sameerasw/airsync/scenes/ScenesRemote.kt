package com.sameerasw.airsync.scenes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.PointTarget
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * H.I.B Scenes, phone side.
 *
 * Mac → phone: `scenesList` {scenes:[{id,name,icon}]}, `phoneAction` {action, requestId, ...},
 * `sceneRunResult` {id, ok, message}.
 * Phone → Mac: `runScene` {id}, `phoneActionResult` {requestId, ok, error|message},
 * `phoneEvent` {event:"headset"|"wifi", ...}.
 *
 * Point to act (SPACE.md §11.1): each computer's scenes are kept apart; the card shows the point target's
 * scenes and `runScene` goes to the target computer only.
 */
object ScenesRemote {
    private const val TAG = "HIBScenes"

    data class PhoneScene(val id: String, val name: String, val icon: String)

    private val main = Handler(Looper.getMainLooper())

    private val _scenesBy = MutableStateFlow<Map<String, List<PhoneScene>>>(emptyMap())
    /** Each computer's scenes, by normalized computer name ("" when the sender wasn't known). */
    val scenesBy = _scenesBy.asStateFlow()

    /** [computer]'s scenes in [byComputer] (falling back to scenes whose sender wasn't known). */
    fun scenesFor(byComputer: Map<String, List<PhoneScene>>, computer: String?): List<PhoneScene> =
        computer?.let { byComputer[PairedDesktops.normalizeName(it)] } ?: byComputer[""].orEmpty()

    private fun sourceKey(): String {
        val id = DesktopHub.currentSourceId() ?: return ""
        return if (id == "primary") "" else PairedDesktops.normalizeName(id)
    }

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    val running = _running.asStateFlow()

    /** Entry point from WebSocketMessageHandler. Runs on the socket reader thread. */
    fun handle(context: Context, type: String, data: JSONObject) {
        val appContext = context.applicationContext
        try {
            when (type) {
                "scenesList" -> {
                    val arr = data.optJSONArray("scenes")
                    val list = mutableListOf<PhoneScene>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("id")
                            if (id.isBlank()) continue
                            list.add(PhoneScene(id, o.optString("name", "Scene"), o.optString("icon")))
                        }
                    }
                    val key = sourceKey()
                    _scenesBy.value = _scenesBy.value + (key to list)
                    // The Mac sends this on every connect: make sure trigger reporting is running.
                    SceneEventMonitor.ensureStarted(appContext)
                }
                "phoneAction" -> {
                    SceneEventMonitor.ensureStarted(appContext)
                    main.post { ScenePhoneActions.perform(appContext, data) }
                }
                "sceneRunResult" -> {
                    val id = data.optString("id")
                    _running.value = _running.value - id
                    val ok = data.optBoolean("ok", false)
                    val message = data.optString("message").ifBlank { if (ok) "Done" else "Scene failed" }
                    main.post {
                        Toast.makeText(appContext, message, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $type: ${e.message}")
        }
    }

    fun run(id: String): Boolean {
        val message = JSONObject()
            .put("type", "runScene")
            .put("data", JSONObject().put("id", id))
            .toString()
        val sent = PointTarget.send(message)
        if (sent) {
            _running.value = _running.value + id
            // Never leave a button spinning if the Mac doesn't answer.
            main.postDelayed({ _running.value = _running.value - id }, 90_000L)
        }
        return sent
    }

    fun reset() {
        _scenesBy.value = emptyMap()
        _running.value = emptySet()
    }

    internal fun sendJson(type: String, data: JSONObject): Boolean {
        val message = JSONObject().put("type", type).put("data", data).toString()
        return WebSocketUtil.sendMessage(message)
    }
}
