package com.sameerasw.airsync.home

import com.sameerasw.airsync.multidesk.ComputerLive
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops

/*
 * H.I.B Home — "Your devices": one equal card per computer (Mac or Windows). Which computer happens to hold
 * the legacy primary connection is an internal detail: it never changes a card's size, order or wording.
 * The computer in use only gets a subtle highlight.
 *
 * Pure logic (no Android types) so it runs in JVM unit tests.
 */

enum class CardStatus { IN_USE, CONNECTED, CONNECTING, OFFLINE, PAUSED, NEEDS_PAIRING }

/** The one-tap things a connected computer's card offers. None of them is destructive. */
enum class QuickAction { FLICK, SEND_FILES, DESK_DECK, REMOTE, PLAY }

/** The card's ⋯ menu. Pause and Forget live only here, never as a one-tap button. */
enum class MenuAction { LOCK, SCREENSAVER, PAUSE, RESUME, FORGET }

/** The single button a computer that isn't connected shows instead of quick actions. */
enum class PrimaryAction { CONNECT, RESUME, PAIR_AGAIN, RESUME_ALL }

data class DeviceCardUi(
    val id: String,
    val name: String,
    /** "mac" | "windows" */
    val platform: String,
    val status: CardStatus,
    /** "In use", "Connected", "Connecting…", "Offline", "Paused", "Pair again". */
    val statusLabel: String,
    /** "Mac" or "Windows PC": what the computer is (never used to mean "the computer"). */
    val kindLabel: String,
    /** A short extra fact: "idle 12 min", "H.I.B is paused"… */
    val detail: String? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val nowPlaying: String? = null,
    /** This computer plays the phone's audio (Play on…). */
    val playingPhoneAudio: Boolean = false,
    /** The computer in use / the point target: a subtle accent, never a "main" card. */
    val highlighted: Boolean = false,
    val quickActions: List<QuickAction> = emptyList(),
    val primaryAction: PrimaryAction? = null,
    val menu: List<MenuAction> = emptyList(),
    /** Internal: which connection mechanism serves it. Not shown, not used for ordering. */
    val viaPrimaryLink: Boolean = false
) {
    val isConnected: Boolean get() = status == CardStatus.IN_USE || status == CardStatus.CONNECTED

    /** "In use · Mac · idle 3 min" */
    val subtitle: String get() = listOfNotNull(statusLabel, kindLabel, detail).joinToString(" · ")
}

object DeviceCards {

    /**
     * @param desktops every paired computer as [DesktopHub] reports it (the primary is just one of them)
     * @param live the last `status` per computer, keyed by [PairedDesktops.normalizeName]
     * @param playingOn the computer playing the phone's audio, if any
     * @param appPaused H.I.B as a whole is paused
     * @param primaryPausedByUser the user paused the computer that rides the primary connection
     * @param highlightId the computer to highlight (in use, or the point target); null for none
     */
    fun build(
        desktops: List<DesktopHub.DesktopStatus>,
        live: Map<String, ComputerLive> = emptyMap(),
        playingOn: String? = null,
        appPaused: Boolean = false,
        primaryPausedByUser: Boolean = false,
        highlightId: String? = null
    ): List<DeviceCardUi> = sort(
        desktops
            .distinctBy { PairedDesktops.normalizeName(it.name) }
            .map { card(it, live[PairedDesktops.normalizeName(it.name)], playingOn, appPaused, primaryPausedByUser, highlightId) }
    )

    /** Connected first (in use is just connected), then connecting, offline, paused; by name inside each group. */
    fun sort(cards: List<DeviceCardUi>): List<DeviceCardUi> = cards.sortedWith(
        compareBy<DeviceCardUi> { rank(it.status) }
            .thenBy { it.name.lowercase() }
            .thenBy { it.name }
    )

    fun rank(status: CardStatus): Int = when (status) {
        CardStatus.IN_USE, CardStatus.CONNECTED -> 0
        CardStatus.CONNECTING -> 1
        CardStatus.OFFLINE, CardStatus.NEEDS_PAIRING -> 2
        CardStatus.PAUSED -> 3
    }

    fun status(d: DesktopHub.DesktopStatus, appPaused: Boolean, primaryPausedByUser: Boolean): CardStatus = when {
        appPaused -> CardStatus.PAUSED
        d.state == "connected" -> if (d.inUse) CardStatus.IN_USE else CardStatus.CONNECTED
        d.state == "connecting" -> CardStatus.CONNECTING
        d.state == "authFailed" -> CardStatus.NEEDS_PAIRING
        d.state == "off" -> CardStatus.PAUSED
        d.isPrimary && primaryPausedByUser -> CardStatus.PAUSED
        else -> CardStatus.OFFLINE
    }

    fun label(status: CardStatus): String = when (status) {
        CardStatus.IN_USE -> "In use"
        CardStatus.CONNECTED -> "Connected"
        CardStatus.CONNECTING -> "Connecting…"
        CardStatus.OFFLINE -> "Offline"
        CardStatus.PAUSED -> "Paused"
        CardStatus.NEEDS_PAIRING -> "Pair again"
    }

    fun kind(platform: String): String = if (platform == "windows") "Windows PC" else "Mac"

    private fun card(
        d: DesktopHub.DesktopStatus,
        l: ComputerLive?,
        playingOn: String?,
        appPaused: Boolean,
        primaryPausedByUser: Boolean,
        highlightId: String?
    ): DeviceCardUi {
        val st = status(d, appPaused, primaryPausedByUser)
        val connected = st == CardStatus.IN_USE || st == CardStatus.CONNECTED
        val detail = when {
            appPaused -> "H.I.B is paused"
            st == CardStatus.NEEDS_PAIRING -> "scan its code again"
            connected && st != CardStatus.IN_USE && d.idleSeconds in 90..(48 * 3600) -> "idle ${idle(d.idleSeconds)}"
            else -> null
        }
        val highlighted = connected && (d.inUse || (highlightId != null && PairedDesktops.sameName(highlightId, d.name)))
        return DeviceCardUi(
            id = d.name,
            name = d.name,
            platform = d.platform,
            status = st,
            statusLabel = label(st),
            kindLabel = kind(d.platform),
            detail = detail,
            battery = if (connected) l?.batteryLevel else null,
            charging = connected && l?.charging == true,
            nowPlaying = if (connected && l?.playing == true) l.nowPlaying else null,
            playingPhoneAudio = connected && playingOn != null && PairedDesktops.sameName(playingOn, d.name),
            highlighted = highlighted,
            quickActions = if (connected) QuickAction.entries.toList() else emptyList(),
            primaryAction = when (st) {
                CardStatus.IN_USE, CardStatus.CONNECTED, CardStatus.CONNECTING -> null
                CardStatus.OFFLINE -> PrimaryAction.CONNECT
                CardStatus.NEEDS_PAIRING -> PrimaryAction.PAIR_AGAIN
                CardStatus.PAUSED -> if (appPaused) PrimaryAction.RESUME_ALL else PrimaryAction.RESUME
            },
            menu = menu(st, appPaused),
            viaPrimaryLink = d.isPrimary
        )
    }

    fun menu(status: CardStatus, appPaused: Boolean): List<MenuAction> = when {
        appPaused -> listOf(MenuAction.FORGET)
        else -> when (status) {
            CardStatus.IN_USE, CardStatus.CONNECTED ->
                listOf(MenuAction.LOCK, MenuAction.SCREENSAVER, MenuAction.PAUSE, MenuAction.FORGET)
            CardStatus.CONNECTING, CardStatus.OFFLINE -> listOf(MenuAction.PAUSE, MenuAction.FORGET)
            CardStatus.PAUSED -> listOf(MenuAction.RESUME, MenuAction.FORGET)
            CardStatus.NEEDS_PAIRING -> listOf(MenuAction.FORGET)
        }
    }

    /** The line under "Your devices": "Using MacBook", "2 of 3 connected", "Paused"… */
    fun summary(cards: List<DeviceCardUi>, appPaused: Boolean): String {
        if (cards.isEmpty()) return ""
        if (appPaused) return "H.I.B is paused"
        val connected = cards.count { it.isConnected }
        val inUse = cards.firstOrNull { it.status == CardStatus.IN_USE }
        return when {
            inUse != null && cards.size == 1 -> "Using ${inUse.name}"
            inUse != null -> "Using ${inUse.name} · $connected of ${cards.size} connected"
            cards.size == 1 -> cards.first().statusLabel
            else -> "$connected of ${cards.size} connected"
        }
    }

    private fun idle(seconds: Int): String = when {
        seconds < 3600 -> "${seconds / 60} min"
        else -> "${seconds / 3600} h"
    }
}
