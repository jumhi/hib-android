package com.sameerasw.airsync.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Phone-side state for "Play on Mac": the Mac streams this phone's audio over ADB. */
object PhoneAudioRemote {
    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun toggle(): Boolean = send("toggle")

    private fun send(action: String): Boolean {
        _error.value = null
        val message = JSONObject()
            .put("type", "phoneAudioControl")
            .put("data", JSONObject().put("action", action))
            .toString()
        return WebSocketUtil.sendMessage(message)
    }

    fun onMacState(data: JSONObject) {
        _isActive.value = data.optBoolean("active", false)
        _error.value = data.optString("error").takeIf { it.isNotBlank() }
    }

    fun reset() {
        _isActive.value = false
        _error.value = null
    }
}
