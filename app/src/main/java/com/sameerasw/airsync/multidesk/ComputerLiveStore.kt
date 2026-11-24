package com.sameerasw.airsync.multidesk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * H.I.B Home — every computer's own battery / now playing (its `status`) and the last text any computer
 * copied, fed by [DesktopHub.dispatchInbound] for the primary connection and the other computers alike.
 * Home cards and the widgets read it; nothing here depends on which computer is the primary.
 */
object ComputerLiveStore {
    private val _live = MutableStateFlow<Map<String, ComputerLive>>(emptyMap())
    /** By [PairedDesktops.normalizeName]. */
    val live: StateFlow<Map<String, ComputerLive>> = _live.asStateFlow()

    private val _lastClip = MutableStateFlow<ComputerClip?>(null)
    val lastClip: StateFlow<ComputerClip?> = _lastClip.asStateFlow()

    fun onStatus(computer: String, json: String) {
        val l = LiveStatusParser.parse(json, System.currentTimeMillis()) ?: return
        val k = PairedDesktops.normalizeName(computer)
        _live.update { m ->
            // Keep the small album art when this update didn't carry it.
            val keepArt = if (l.artLite == null && m[k]?.title == l.title) m[k]?.artLite else l.artLite
            m + (k to l.copy(artLite = keepArt))
        }
    }

    fun onClipboard(computer: String, json: String) {
        val text = LiveStatusParser.clipboardText(json) ?: return
        _lastClip.value = ComputerClip(computer, text, System.currentTimeMillis())
    }

    fun of(computer: String?): ComputerLive? = computer?.let { _live.value[PairedDesktops.normalizeName(it)] }
}
