package com.sameerasw.airsync.remote

/**
 * Phone Remote (SPACE.md §15.3): when the `/screen` session opens and closes, and when the decoder only changes
 * the surface it draws into. Pure (no Android), main thread only; [RemoteStream] does the work through [Io].
 *
 * The session lives exactly while [want] is set (page visible, app resumed, computer picked, picture on); while
 * the computer is locked it stays open and paused. The surface is not part of that: views come and go (Touchpad ↔
 * Direct, picture layout, keyboard, cards), and each new surface only re-binds the decoder and asks for a
 * keyframe. Tying the session to the surface made the phone close and reopen `/screen` on every view swap.
 */
class ScreenSessionPolicy<S : Any>(private val io: Io<S>) {

    interface Io<S : Any> {
        /** Dial a new session for [want], drawing into [surface]. False when it couldn't start. */
        fun open(want: RemoteStream.Want, surface: S?): Boolean
        /** Stop the session and its decoder (idempotent). */
        fun close()
        /** The decoder draws into [surface] from now on (null: nowhere). */
        fun bind(surface: S?)
        /** Ask the computer for config + keyframe. */
        fun keyframe()
        /** Only the display changed on a session that is up: switch in place. */
        fun switchDisplay(display: String)
    }

    var want: RemoteStream.Want? = null
        private set
    var surface: S? = null
        private set
    var isOpen = false
        private set

    /** [up]: the current session has said `screenOpened` (live or paused). */
    fun setWant(w: RemoteStream.Want?, up: Boolean) {
        if (w == want) return
        val prev = want
        want = w
        if (w != null && prev != null && isOpen && up && w.copy(display = prev.display) == prev) {
            io.switchDisplay(w.display)
            return
        }
        restart()
    }

    /** A new surface is ready (it replaces any other, whatever order the old view's callbacks arrive in). */
    fun attach(s: S) {
        if (s === surface) return
        surface = s
        if (!isOpen) return
        io.bind(s)
        io.keyframe()
    }

    /** [s] is going away; ignored when a newer surface already replaced it. */
    fun detach(s: S) {
        if (s !== surface) return
        surface = null
        if (isOpen) io.bind(null)
    }

    /** Retry after a drop, "Try again", or a codec fallback. */
    fun reopen() {
        if (want != null) restart()
    }

    /** The session closed by itself (computer closed it, dropped, went silent). */
    fun ended() {
        io.close()
        isOpen = false
    }

    private fun restart() {
        io.close()
        isOpen = false
        val w = want ?: return
        isOpen = io.open(w, surface)
    }
}
