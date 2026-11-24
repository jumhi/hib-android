package com.sameerasw.airsync.home

import com.sameerasw.airsync.multidesk.ComputerLive
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home "Your devices": every computer is an equal card; the primary connection never shows. */
class DeviceCardsTest {

    private fun d(
        name: String,
        state: String = "connected",
        primary: Boolean = false,
        platform: String = "mac",
        inUse: Boolean = false,
        idle: Int = -1
    ) = DesktopHub.DesktopStatus(name, platform, null, primary, state, null, null, inUse = inUse, idleSeconds = idle)

    private fun live(vararg pairs: Pair<String, ComputerLive>) =
        pairs.associate { PairedDesktops.normalizeName(it.first) to it.second }

    @Test
    fun primaryIsNeverFirstJustBecauseItIsPrimary() {
        val cards = DeviceCards.build(
            listOf(d("Zed MacBook", primary = true), d("Atlas PC", platform = "windows"), d("Mira iMac"))
        )
        assertEquals(listOf("Atlas PC", "Mira iMac", "Zed MacBook"), cards.map { it.name })
    }

    @Test
    fun orderIsStatusGroupThenName() {
        val cards = DeviceCards.build(
            listOf(
                d("Bravo", state = "off"),
                d("Alpha", state = "offline", primary = true),
                d("Delta", state = "connecting"),
                d("Echo", inUse = true),
                d("charlie"),
                d("Foxtrot", state = "authFailed")
            )
        )
        // connected (in use counts as connected) → connecting → offline / pair again → paused; by name within.
        assertEquals(listOf("charlie", "Echo", "Delta", "Alpha", "Foxtrot", "Bravo"), cards.map { it.name })
    }

    @Test
    fun inUseIsHighlightedNotPromoted() {
        val cards = DeviceCards.build(listOf(d("Zulu", inUse = true), d("Alpha")))
        assertEquals("Alpha", cards[0].name)
        assertTrue(cards[1].highlighted)
        assertEquals(CardStatus.IN_USE, cards[1].status)
        assertFalse(cards[0].highlighted)
    }

    @Test
    fun pointTargetCanBeHighlightedOnlyWhenConnected() {
        val cards = DeviceCards.build(listOf(d("Alpha"), d("Bravo", state = "offline")), highlightId = "bravo")
        assertFalse(cards.first { it.name == "Bravo" }.highlighted)
        val again = DeviceCards.build(listOf(d("Alpha"), d("Bravo")), highlightId = "bravo")
        assertTrue(again.first { it.name == "Bravo" }.highlighted)
    }

    @Test
    fun cardsLookTheSameWhetherPrimaryOrNot() {
        val l = live("Mac" to ComputerLive(batteryLevel = 80), "PC" to ComputerLive(batteryLevel = 80))
        val (a, b) = DeviceCards.build(listOf(d("Mac", primary = true), d("PC")), live = l)
        assertEquals(a.quickActions, b.quickActions)
        assertEquals(a.menu, b.menu)
        assertEquals(a.statusLabel, b.statusLabel)
        assertEquals(80, a.battery)
        assertEquals(80, b.battery)
        assertFalse(a.subtitle.contains("main"))
    }

    @Test
    fun connectedCardHasEveryQuickActionAndNoDestructiveOne() {
        val c = DeviceCards.build(listOf(d("Mac"))).single()
        assertEquals(QuickAction.entries.toList(), c.quickActions)
        assertNull(c.primaryAction)
        // Pause and Forget only in the ⋯ menu.
        assertEquals(listOf(MenuAction.LOCK, MenuAction.SCREENSAVER, MenuAction.PAUSE, MenuAction.FORGET), c.menu)
    }

    @Test
    fun offlinePausedAndPairAgainOfferTheRightButton() {
        val cards = DeviceCards.build(
            listOf(d("A", state = "offline"), d("B", state = "off"), d("C", state = "authFailed"), d("D", state = "connecting"))
        ).associateBy { it.name }
        assertEquals(PrimaryAction.CONNECT, cards["A"]!!.primaryAction)
        assertEquals(PrimaryAction.RESUME, cards["B"]!!.primaryAction)
        assertEquals(listOf(MenuAction.RESUME, MenuAction.FORGET), cards["B"]!!.menu)
        assertEquals(PrimaryAction.PAIR_AGAIN, cards["C"]!!.primaryAction)
        assertNull(cards["D"]!!.primaryAction)
        assertTrue(cards.values.filter { it.name != "D" }.all { it.quickActions.isEmpty() })
    }

    @Test
    fun primaryPausedByTheUserReadsPausedNotOffline() {
        val c = DeviceCards.build(listOf(d("Mac", state = "offline", primary = true)), primaryPausedByUser = true).single()
        assertEquals(CardStatus.PAUSED, c.status)
        assertEquals(PrimaryAction.RESUME, c.primaryAction)
        // The flag is about the primary connection only.
        val other = DeviceCards.build(listOf(d("PC", state = "offline")), primaryPausedByUser = true).single()
        assertEquals(CardStatus.OFFLINE, other.status)
    }

    @Test
    fun appPausedPausesEveryCard() {
        val cards = DeviceCards.build(listOf(d("Mac", primary = true), d("PC", state = "off")), appPaused = true)
        assertTrue(cards.all { it.status == CardStatus.PAUSED && it.primaryAction == PrimaryAction.RESUME_ALL })
        assertTrue(cards.all { it.menu == listOf(MenuAction.FORGET) })
        assertEquals("H.I.B is paused", DeviceCards.summary(cards, appPaused = true))
    }

    @Test
    fun batteryAndNowPlayingOnlyWhileConnected() {
        val l = live(
            "Mac" to ComputerLive(batteryLevel = 64, charging = true, playing = true, title = "Song", artist = "Band"),
            "Old" to ComputerLive(batteryLevel = 10, playing = true, title = "Stale")
        )
        val cards = DeviceCards.build(listOf(d("Mac"), d("Old", state = "offline")), live = l).associateBy { it.name }
        assertEquals(64, cards["Mac"]!!.battery)
        assertTrue(cards["Mac"]!!.charging)
        assertEquals("Song — Band", cards["Mac"]!!.nowPlaying)
        assertNull(cards["Old"]!!.battery)
        assertNull(cards["Old"]!!.nowPlaying)
    }

    @Test
    fun desktopWithoutBatteryShowsNone() {
        val l = live("PC" to ComputerLive(batteryLevel = null))
        assertNull(DeviceCards.build(listOf(d("PC", platform = "windows")), live = l).single().battery)
    }

    @Test
    fun phoneAudioMarkerFollowsPlayingOn() {
        val cards = DeviceCards.build(listOf(d("Mac"), d("PC")), playingOn = "pc").associateBy { it.name }
        assertTrue(cards["PC"]!!.playingPhoneAudio)
        assertFalse(cards["Mac"]!!.playingPhoneAudio)
    }

    @Test
    fun duplicateNamesCollapseToOneCard() {
        val cards = DeviceCards.build(listOf(d("Haitham’s Mac", primary = true), d("haitham's mac")))
        assertEquals(1, cards.size)
    }

    @Test
    fun wordsAreDeviceNeutral() {
        val cards = DeviceCards.build(listOf(d("Desk", platform = "windows", idle = 600), d("Book")))
        val pc = cards.first { it.name == "Desk" }
        assertEquals("Connected · Windows PC · idle 10 min", pc.subtitle)
        assertEquals("2 of 2 connected", DeviceCards.summary(cards, appPaused = false))
        val inUse = DeviceCards.build(listOf(d("Desk", inUse = true), d("Book")))
        assertEquals("Using Desk · 2 of 2 connected", DeviceCards.summary(inUse, appPaused = false))
    }
}
