package com.sameerasw.airsync.multidesk

/*
 * H.I.B Point to act (docs/SPACE.md §11): whatever the phone points at receives what the phone does — not
 * only Flick. Desk Deck, the Remote, Scenes, the computer media controls and the Play on… default all use
 * one shared point target ([PointTarget] on the phone).
 *
 * Pure logic (Gson for JSON, no Android types) so it runs in JVM unit tests.
 */

/** What the aimed screen's pill says while a point-aware screen is open (SPACE.md §11.2). */
object PointLabels {
    const val DESK_DECK = "Desk Deck"
    const val REMOTE = "Remote"
    const val SCENES = "Scenes"
    const val MEDIA = "Media"
}

/**
 * Where actions go right now.
 * @param computer the target computer (always a connected one)
 * @param reason [TargetRule.Reason.AIM] when the phone points at one of its screens, else the target rule's step
 * @param screen the aimed screen when the aim decided (it is lit on that computer), else null (nothing lit)
 */
data class PointChoice(val computer: String, val reason: TargetRule.Reason, val screen: ScreenTarget? = null) {
    val aimed: Boolean get() = screen != null

    fun sameComputer(other: String?): Boolean = other != null && PairedDesktops.sameName(computer, other)
}

object PointPick {
    /**
     * The point target (SPACE.md §11.1): the computer owning the aimed screen (nearest saved screen of a
     * connected computer within 35°, §7.6), else the target rule (in use → last used → main computer).
     * Null when nothing is connected.
     *
     * @param computers the connected computers with their screens (as for Point & Flick)
     * @param candidates the same computers as target-rule candidates
     */
    fun choose(
        aim: AimMatcher.Aim?,
        saved: List<SavedScreenAim>,
        computers: List<ScreenComputer>,
        candidates: List<TargetRule.Candidate>
    ): PointChoice? {
        val hit = ScreenMap.nearest(aim, saved, computers)
        val pick = TargetRule.pick(candidates, aimed = hit?.computer) ?: return null
        val screen = hit?.takeIf { pick.reason == TargetRule.Reason.AIM && PairedDesktops.sameName(it.computer, pick.id) }
        return PointChoice(pick.id, pick.reason, screen)
    }
}

/**
 * The shared point target without Android (SPACE.md §11.1): which point-aware screens are open (the newest
 * one names the aim light), the live target, and the `flickAim{screen,label}` messages ([AimLight]: the aimed
 * screen's computer, `null` to the one lit before, the current one again every 1.5 s, `null` to all on stop).
 */
class PointSession(resendMs: Long = 1500L) {
    private val holders = ArrayList<Pair<Long, String>>()
    private var nextToken = 1L
    private val light = AimLight(resendMs)

    /** The live target while a point-aware screen is open; null otherwise (and before the first reading). */
    var choice: PointChoice? = null
        private set

    /** A point-aware screen is in front: aim tracking runs. */
    val active: Boolean get() = holders.isNotEmpty()

    /** The newest open point-aware screen's label ("Desk Deck", "Remote"…). */
    val label: String? get() = holders.lastOrNull()?.second

    /** The screen lit now, if any. */
    val lit: ScreenTarget? get() = light.current

    /** A point-aware screen came to the front. Returns the token for [release]. */
    fun hold(label: String): Long {
        val token = nextToken++
        holders += token to label
        return token
    }

    /** That screen left the front. True when it was the last one (tracking stops: call [stop]). */
    fun release(token: Long): Boolean {
        val removed = holders.removeAll { it.first == token }
        return removed && holders.isEmpty()
    }

    /** A new reading decided [next]. Returns the light messages to send (none while no screen is open). */
    fun update(next: PointChoice?, now: Long): List<AimLight.Send> {
        if (!active) return emptyList()
        choice = next
        return light.update(next?.screen, now, label)
    }

    /** The last point-aware screen closed (or the app went to the background): dark on every computer. */
    fun stop(connected: List<String>): List<AimLight.Send> {
        choice = null
        return light.clear(connected)
    }

    /** Where an action goes now: the live target while tracking, else [fallback] (the target rule). */
    fun targetId(fallback: () -> String?): String? = (if (active) choice?.computer else null) ?: fallback()
}

/**
 * Desk Deck follows the point target (SPACE.md §11.1): when the target changes, `deckOpen{open:false}` to the
 * computer the deck was open on and `deckOpen{open:true}` to the new one; `open:false` when the deck closes.
 * Only the computer the deck is on feeds its live state (CPU, memory, battery, network…).
 */
class DeckHandover {
    data class Send(val computer: String, val open: Boolean) {
        fun json(): String = PointJson.deckOpen(open)
    }

    /** The computer the deck is open on, or null when it is closed. */
    var current: String? = null
        private set

    /** The target is now [target] (null: nothing connected, the deck closes where it was). */
    fun moveTo(target: String?): List<Send> {
        val prev = current
        if (target != null && prev != null && PairedDesktops.sameName(prev, target)) return emptyList()
        val out = mutableListOf<Send>()
        if (prev != null) out += Send(prev, false)
        if (target != null) out += Send(target, true)
        current = target
        return out
    }

    /** Ask the deck's computer to stream again (after a reconnect the stream stops). */
    fun reopen(): Send? = current?.let { Send(it, true) }

    /** The deck leaves the screen. */
    fun close(): List<Send> {
        val prev = current ?: return emptyList()
        current = null
        return listOf(Send(prev, false))
    }

    /** Whether a `deckState` from [source] is shown: only the deck's computer's (unknown sources pass). */
    fun accepts(source: String?): Boolean {
        val c = current ?: return true
        return source == null || PairedDesktops.sameName(c, source)
    }
}
