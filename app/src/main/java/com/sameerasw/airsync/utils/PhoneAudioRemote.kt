package com.sameerasw.airsync.utils

import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import com.sameerasw.airsync.multidesk.PlayExclusivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * H.I.B "Play on…" (SPACE.md §3.5): the phone's audio plays on exactly one computer, which streams it
 * over ADB (scrcpy audio), so Android shows no screen-share prompt.
 *
 * Starting on one computer stops it on the others, whether it was started here ("Play on X") or on a
 * computer ("Play here" → `phoneAudioState active:true`). [playingOn] is pushed to every computer in
 * `desktopsList`.
 */
object PhoneAudioRemote {
    private val _playingOn = MutableStateFlow<String?>(null)
    /** The computer playing the phone's audio, or null. */
    val playingOn: StateFlow<String?> = _playingOn.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** "Play on X": start on X, stop on every other connected computer. */
    fun playOn(id: String): Boolean {
        _error.value = null
        DesktopHub.noteUsed(id)
        return apply(PlayExclusivity.playOn(id, DesktopHub.connectedIds()))
    }

    /** Stop playing everywhere. */
    fun stop(): Boolean {
        _error.value = null
        return apply(PlayExclusivity.stop(DesktopHub.connectedIds()))
    }

    /** One tap: stop if playing, else play on the target rule's computer (the one in use, …). */
    fun toggle(): Boolean {
        _error.value = null
        val target = DesktopHub.targetPick()?.id
        if (_playingOn.value == null && target != null) DesktopHub.noteUsed(target)
        return apply(PlayExclusivity.toggle(_playingOn.value, target, DesktopHub.connectedIds()))
    }

    /** `phoneAudioState {active, error?}` from a computer (the one whose message is being handled). */
    fun onMacState(data: JSONObject) {
        val from = DesktopHub.currentSourceId() ?: DesktopHub.primaryName ?: return
        val active = data.optBoolean("active", false)
        val err = data.optString("error").takeIf { it.isNotBlank() }
        val before = _playingOn.value
        // Errors matter from the computer that is (or was asked to be) playing, not from one we told to stop.
        if (err != null && (before == null || PairedDesktops.sameName(before, from))) {
            _error.value = "$from: $err"
        } else if (active) {
            _error.value = null
        }
        apply(PlayExclusivity.onState(from, active, before, DesktopHub.connectedIds()))
    }

    /** The set of connected computers changed: one that dropped can't be playing any more. */
    fun onComputersChanged(connected: List<String>) {
        val current = _playingOn.value ?: return
        if (connected.none { PairedDesktops.sameName(it, current) }) setPlayingOn(null)
    }

    fun reset() {
        setPlayingOn(null)
        _error.value = null
    }

    private fun apply(r: PlayExclusivity.Result): Boolean {
        var ok = r.commands.isEmpty()
        r.commands.forEach { c ->
            val msg = JSONObject()
                .put("type", "phoneAudioControl")
                .put("data", JSONObject().put("action", c.action))
                .toString()
            val sent = DesktopHub.findLink(c.id)?.send(msg) ?: false
            if (sent && (c.action == "start" || r.playingOn == null)) ok = true
        }
        setPlayingOn(r.playingOn)
        return ok
    }

    private fun setPlayingOn(id: String?) {
        val changed = _playingOn.value != id
        _playingOn.value = id
        _isActive.value = id != null
        if (changed) DesktopHub.onPlayingChanged()
    }
}
