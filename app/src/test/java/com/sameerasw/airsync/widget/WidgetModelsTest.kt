package com.sameerasw.airsync.widget

import com.sameerasw.airsync.home.DeviceCards
import com.sameerasw.airsync.multidesk.ComputerClip
import com.sameerasw.airsync.multidesk.ComputerLive
import com.sameerasw.airsync.multidesk.DesktopHub
import com.sameerasw.airsync.multidesk.PairedDesktops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the H.I.B widgets show. */
class WidgetModelsTest {

    private fun d(name: String, state: String = "connected", inUse: Boolean = false, primary: Boolean = false) =
        DesktopHub.DesktopStatus(name, "mac", null, primary, state, null, null, inUse = inUse)

    private fun live(vararg pairs: Pair<String, ComputerLive>) =
        pairs.associate { PairedDesktops.normalizeName(it.first) to it.second }

    // MARK: - Devices

    @Test
    fun devicesWidgetListsEveryComputerLikeHome() {
        val cards = DeviceCards.build(
            listOf(d("Zed", primary = true), d("Amy", inUse = true), d("Bo", state = "offline")),
            live = live("Amy" to ComputerLive(batteryLevel = 40, playing = true, title = "Tune"))
        )
        val w = WidgetModels.devices(cards, maxRows = 3, summary = DeviceCards.summary(cards, false))
        assertEquals(listOf("Amy", "Zed", "Bo"), w.rows.map { it.name })
        assertEquals(WidgetDot.IN_USE, w.rows[0].dot)
        assertEquals(WidgetDot.CONNECTED, w.rows[1].dot)
        assertEquals(WidgetDot.OFF, w.rows[2].dot)
        assertEquals(40, w.rows[0].battery)
        assertEquals("In use · ♪ Tune", w.rows[0].subtitle)
        assertTrue(w.rows[0].highlighted)
        assertEquals(0, w.more)
        assertNull(w.empty)
    }

    @Test
    fun devicesWidgetCountsWhatDoesNotFit() {
        val cards = DeviceCards.build(listOf(d("A"), d("B"), d("C"), d("D")))
        val w = WidgetModels.devices(cards, maxRows = 2, summary = "")
        assertEquals(2, w.rows.size)
        assertEquals(2, w.more)
    }

    @Test
    fun devicesWidgetEmptyState() {
        val w = WidgetModels.devices(emptyList(), 4, "")
        assertTrue(w.rows.isEmpty())
        assertTrue(w.empty!!.contains("add one"))
    }

    @Test
    fun rowsFitTheHeight() {
        assertEquals(1, WidgetModels.rowsFor(60))
        assertEquals(2, WidgetModels.rowsFor(150))
        assertEquals(6, WidgetModels.rowsFor(1000))
    }

    // MARK: - Media

    @Test
    fun mediaShowsTheComputerInUseWhenItHasSomething() {
        val l = live("Mac" to ComputerLive(playing = false, title = "Paused song"), "PC" to ComputerLive(playing = true, title = "Live"))
        val m = WidgetModels.media(listOf("Mac", "PC"), l, target = "Mac", playingOn = null)
        assertEquals("Mac", m.computer)
        assertEquals("Paused song", m.title)
        assertFalse(m.isPlaying)
    }

    @Test
    fun mediaFallsBackToWhateverIsPlaying() {
        val l = live("PC" to ComputerLive(playing = true, title = "Live", artist = "Band"))
        val m = WidgetModels.media(listOf("Mac", "PC"), l, target = "Mac", playingOn = null)
        assertEquals("PC", m.computer)
        assertEquals("Band · on PC", m.subtitle)
        assertTrue(m.isPlaying)
        assertTrue(m.canControl)
    }

    @Test
    fun mediaWithNothingPlayingStillNamesTheComputer() {
        val m = WidgetModels.media(listOf("Mac"), emptyMap(), target = "Mac", playingOn = null)
        assertEquals("Nothing playing", m.title)
        assertEquals("on Mac", m.subtitle)
        assertEquals("Play on Mac", m.playOnLabel)
        assertEquals("Mac", m.playOnTarget)
        assertFalse(m.playOnActive)
    }

    @Test
    fun playOnToggleStopsWhenPlaying() {
        val m = WidgetModels.media(listOf("Mac", "PC"), emptyMap(), target = "Mac", playingOn = "PC")
        assertEquals("Playing on PC", m.playOnLabel)
        assertTrue(m.playOnActive)
        assertNull(m.playOnTarget)
    }

    @Test
    fun mediaWithNothingConnected() {
        val m = WidgetModels.media(emptyList(), emptyMap(), target = null, playingOn = null)
        assertNull(m.computer)
        assertFalse(m.canControl)
        assertEquals("Play on…", m.playOnLabel)
    }

    // MARK: - Desk Deck

    @Test
    fun deckAlwaysHasFourButtons() {
        assertEquals(WidgetModels.DEFAULT_DECK, WidgetModels.deckButtons(emptyList()).map { it.actionId })
        val custom = WidgetModels.deckButtons(listOf("screenshot", "bogus", "screenshot", "mute"))
        assertEquals(listOf("screenshot", "mute", "lock", "play_pause"), custom.map { it.actionId })
        val many = WidgetModels.deckButtons(listOf("next", "previous", "mute", "lock", "vol_up"))
        assertEquals(4, many.size)
    }

    @Test
    fun deckNamesItsTarget() {
        assertEquals("Desk Deck → Mac", WidgetModels.deck(emptyList(), "Mac").header)
        assertTrue(WidgetModels.deck(emptyList(), null).header.contains("no computer"))
        // Only argument-free actions can be on a widget.
        assertTrue(WidgetModels.DECK_ACTIONS.none { it.actionId in setOf("open_app", "shortcut", "dnd") })
    }

    // MARK: - Clipboard

    @Test
    fun clipboardShowsTheLastClipAndItsComputer() {
        val clip = ComputerClip("PC", "  hello\n\nworld  ", at = 0)
        val c = WidgetModels.clipboard("Mac", clip, now = 120_000)
        assertEquals("Clipboard → Mac", c.header)
        assertTrue(c.canSend)
        assertTrue(c.canGet)
        assertEquals("hello world", c.lastClip)
        assertEquals("From PC · 2 min ago", c.lastClipFrom)
    }

    @Test
    fun clipboardWithoutComputerOrClip() {
        val c = WidgetModels.clipboard(null, null, now = 0)
        assertFalse(c.canSend)
        assertFalse(c.canGet)
        assertNull(c.lastClip)
    }

    @Test
    fun shortenAndAgo() {
        assertEquals("abc…", WidgetModels.shorten("abcdef", 4))
        assertEquals("just now", WidgetModels.ago(10_000))
        assertEquals("3 h ago", WidgetModels.ago(3 * 3_600_000L + 5))
    }
}
