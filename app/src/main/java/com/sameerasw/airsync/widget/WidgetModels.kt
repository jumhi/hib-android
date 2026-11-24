package com.sameerasw.airsync.widget

import com.sameerasw.airsync.home.CardStatus
import com.sameerasw.airsync.home.DeviceCardUi
import com.sameerasw.airsync.multidesk.ComputerClip
import com.sameerasw.airsync.multidesk.ComputerLive
import com.sameerasw.airsync.multidesk.PairedDesktops

/*
 * H.I.B home-screen widgets: what each widget shows, worked out without Android so it is unit-tested.
 * The providers turn these into RemoteViews.
 */

/** The dot next to a computer: same colours as the app. */
enum class WidgetDot { IN_USE, CONNECTED, BUSY, OFF, ALERT }

data class DevicesWidgetRow(
    val name: String,
    val platform: String,
    val subtitle: String,
    val dot: WidgetDot,
    val battery: Int?,
    val highlighted: Boolean
)

data class DevicesWidgetState(
    val summary: String,
    val rows: List<DevicesWidgetRow>,
    /** How many computers didn't fit. */
    val more: Int,
    /** Shown instead of rows when there are none. */
    val empty: String?
)

data class MediaWidgetState(
    /** Whose media is shown and controlled (null: nothing connected). */
    val computer: String?,
    val title: String,
    val subtitle: String,
    val isPlaying: Boolean,
    val canControl: Boolean,
    /** "Play on MacBook" / "Playing on MacBook" / "Play on…". */
    val playOnLabel: String,
    val playOnActive: Boolean,
    /** Where a tap on Play on… sends the phone's audio (null: it stops, or nothing to play on). */
    val playOnTarget: String?
)

data class DeckWidgetButton(val actionId: String, val label: String, val icon: String)

data class DeckWidgetState(val target: String?, val header: String, val buttons: List<DeckWidgetButton>)

data class ClipboardWidgetState(
    val target: String?,
    val header: String,
    val canSend: Boolean,
    /** The last text a computer copied, shortened for the widget; null when none yet. */
    val lastClip: String?,
    /** "From MacBook · 2 min ago". */
    val lastClipFrom: String?,
    val canGet: Boolean
)

object WidgetModels {

    fun dot(status: CardStatus): WidgetDot = when (status) {
        CardStatus.IN_USE -> WidgetDot.IN_USE
        CardStatus.CONNECTED -> WidgetDot.CONNECTED
        CardStatus.CONNECTING -> WidgetDot.BUSY
        CardStatus.NEEDS_PAIRING -> WidgetDot.ALERT
        CardStatus.OFFLINE, CardStatus.PAUSED -> WidgetDot.OFF
    }

    /** Every computer, same order and words as Home. */
    fun devices(cards: List<DeviceCardUi>, maxRows: Int, summary: String): DevicesWidgetState {
        if (cards.isEmpty()) return DevicesWidgetState("", emptyList(), 0, "No computers yet — open H.I.B to add one")
        val fit = maxRows.coerceAtLeast(1)
        val rows = cards.take(fit).map { c ->
            DevicesWidgetRow(
                name = c.name,
                platform = c.platform,
                subtitle = listOfNotNull(c.statusLabel, c.nowPlaying?.let { "♪ $it" } ?: c.detail).joinToString(" · "),
                dot = dot(c.status),
                battery = c.battery,
                highlighted = c.highlighted
            )
        }
        return DevicesWidgetState(summary, rows, (cards.size - fit).coerceAtLeast(0), null)
    }

    /**
     * Whose media the Media widget shows: the computer in use (the target) when it has something loaded,
     * else a connected computer that is playing (by name), else the target with nothing playing.
     */
    fun mediaComputer(connected: List<String>, live: Map<String, ComputerLive>, target: String?): String? {
        fun l(name: String) = live[PairedDesktops.normalizeName(name)]
        val t = target?.let { want -> connected.firstOrNull { PairedDesktops.sameName(it, want) } }
        if (t != null && l(t)?.hasMedia == true) return t
        connected.sortedBy { it.lowercase() }.firstOrNull { l(it)?.playing == true && l(it)?.hasMedia == true }?.let { return it }
        return t ?: connected.minByOrNull { it.lowercase() }
    }

    fun media(
        connected: List<String>,
        live: Map<String, ComputerLive>,
        target: String?,
        playingOn: String?
    ): MediaWidgetState {
        val who = mediaComputer(connected, live, target)
        val l = who?.let { live[PairedDesktops.normalizeName(it)] }
        val playTarget = target?.takeIf { want -> connected.any { PairedDesktops.sameName(it, want) } }
            ?: connected.minByOrNull { it.lowercase() }
        val (playLabel, playActive, playTo) = when {
            playingOn != null -> Triple("Playing on $playingOn", true, null)
            playTarget != null -> Triple("Play on $playTarget", false, playTarget)
            else -> Triple("Play on…", false, null)
        }
        return when {
            who == null -> MediaWidgetState(null, "No computer connected", "Open H.I.B to connect", false, false, playLabel, playActive, playTo)
            l == null || !l.hasMedia -> MediaWidgetState(who, "Nothing playing", "on $who", false, true, playLabel, playActive, playTo)
            else -> MediaWidgetState(
                computer = who,
                title = l.title.ifBlank { l.artist },
                subtitle = listOfNotNull(l.artist.takeIf { l.title.isNotBlank() && it.isNotBlank() }, "on $who").joinToString(" · "),
                isPlaying = l.playing,
                canControl = true,
                playOnLabel = playLabel,
                playOnActive = playActive,
                playOnTarget = playTo
            )
        }
    }

    /** The no-argument Desk Deck actions a widget button can send (ids as in the Desk Deck). */
    val DECK_ACTIONS: List<DeckWidgetButton> = listOf(
        DeckWidgetButton("lock", "Lock", "lock"),
        DeckWidgetButton("play_pause", "Play/Pause", "play_pause"),
        DeckWidgetButton("vol_down", "Volume −", "vol_down"),
        DeckWidgetButton("vol_up", "Volume +", "vol_up"),
        DeckWidgetButton("mute", "Mute", "mute"),
        DeckWidgetButton("screenshot", "Screenshot", "screenshot"),
        DeckWidgetButton("next", "Next", "next"),
        DeckWidgetButton("previous", "Previous", "previous"),
        DeckWidgetButton("mic_mute", "Mic mute", "mic_mute"),
        DeckWidgetButton("screensaver", "Screensaver", "screensaver"),
        DeckWidgetButton("show_desktop", "Desktop", "show_desktop"),
        DeckWidgetButton("mission_control", "Windows", "mission_control"),
        DeckWidgetButton("brightness_down", "Dimmer", "brightness_down"),
        DeckWidgetButton("brightness_up", "Brighter", "brightness_up"),
        DeckWidgetButton("phone_audio", "Phone audio", "phone_audio")
    )

    val DEFAULT_DECK = listOf("lock", "play_pause", "vol_down", "vol_up")

    /** Exactly four buttons: the saved ones that still exist, topped up from the defaults. */
    fun deckButtons(saved: List<String>): List<DeckWidgetButton> {
        val byId = DECK_ACTIONS.associateBy { it.actionId }
        val chosen = saved.mapNotNull { byId[it] }.distinctBy { it.actionId }.toMutableList()
        for (id in DEFAULT_DECK + DECK_ACTIONS.map { it.actionId }) {
            if (chosen.size >= 4) break
            if (chosen.none { it.actionId == id }) byId[id]?.let { chosen += it }
        }
        return chosen.take(4)
    }

    fun deck(saved: List<String>, target: String?): DeckWidgetState =
        DeckWidgetState(target, if (target != null) "Desk Deck → $target" else "Desk Deck · no computer", deckButtons(saved))

    fun clipboard(target: String?, clip: ComputerClip?, now: Long): ClipboardWidgetState = ClipboardWidgetState(
        target = target,
        header = if (target != null) "Clipboard → $target" else "Clipboard · no computer",
        canSend = target != null,
        lastClip = clip?.text?.let { shorten(it, 90) },
        lastClipFrom = clip?.let { "From ${it.computer} · ${ago(now - it.at)}" },
        canGet = clip != null
    )

    fun shorten(text: String, max: Int): String {
        val one = text.trim().replace(Regex("\\s+"), " ")
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }

    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    /** How many device rows fit in a widget [heightDp] tall. */
    fun rowsFor(heightDp: Int): Int = ((heightDp - 44) / 52).coerceIn(1, 6)
}
