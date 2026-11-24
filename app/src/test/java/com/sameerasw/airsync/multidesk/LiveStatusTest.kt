package com.sameerasw.airsync.multidesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every computer's own battery / now playing, and the explicit pick from a Home card. */
class LiveStatusTest {

    @Test
    fun parsesAStatusEnvelope() {
        val json = """{"type":"status","data":{"battery":{"level":82,"isCharging":true},"isPaired":true,
            "music":{"isPlaying":true,"title":"Song","artist":"Band","volume":45,"isMuted":false,"albumArtLite":"AAA"}}}"""
        val l = LiveStatusParser.parse(json, now = 7)!!
        assertEquals(82, l.batteryLevel)
        assertTrue(l.charging)
        assertTrue(l.playing)
        assertEquals("Song — Band", l.nowPlaying)
        assertEquals(45, l.volume)
        assertEquals("AAA", l.artLite)
        assertEquals(7L, l.at)
    }

    @Test
    fun desktopWithoutBatteryHasNoLevel() {
        val l = LiveStatusParser.parse("""{"type":"status","data":{"battery":{"level":-1,"isCharging":false},"music":{"isPlaying":false,"title":"","artist":""}}}""", 0)!!
        assertNull(l.batteryLevel)
        assertFalse(l.hasMedia)
        assertNull(l.nowPlaying)
    }

    @Test
    fun otherMessagesAreNotStatus() {
        assertNull(LiveStatusParser.parse("""{"type":"macInfo","data":{"name":"x"}}""", 0))
        assertNull(LiveStatusParser.parse("not json", 0))
        assertNull(LiveStatusParser.parse("""{"type":"status","data":{}}""", 0))
    }

    @Test
    fun readsClipboardText() {
        assertEquals("hi", LiveStatusParser.clipboardText("""{"type":"clipboardUpdate","data":{"text":"hi"}}"""))
        assertNull(LiveStatusParser.clipboardText("""{"type":"clipboardUpdate","data":{"text":""}}"""))
        assertNull(LiveStatusParser.clipboardText("""{"type":"status","data":{}}"""))
    }

    // MARK: - explicit pick

    @Test
    fun explicitPickHoldsWhileTrackingAndForAGracePeriod() {
        val p = ExplicitPick(graceMs = 1_000)
        p.set("Office PC", now = 0)
        // Answers with the connected computer's own spelling.
        assertEquals("office pc", p.current(now = 500, tracking = false, connected = listOf("Mac", "office pc")))
        // A point-aware screen keeps it alive however long it stays open.
        assertEquals("office pc", p.current(now = 60_000, tracking = true, connected = listOf("office pc")))
        p.onTrackingEnded(now = 60_000)
        assertEquals("office pc", p.current(now = 60_900, tracking = false, connected = listOf("office pc")))
        assertNull(p.current(now = 61_500, tracking = false, connected = listOf("office pc")))
        // Gone for good once expired.
        assertNull(p.current(now = 61_501, tracking = true, connected = listOf("office pc")))
    }

    @Test
    fun explicitPickNeedsTheComputerConnected() {
        val p = ExplicitPick()
        p.set("PC", now = 0)
        assertNull(p.current(now = 1, tracking = false, connected = listOf("Mac")))
        assertEquals("PC", p.current(now = 2, tracking = false, connected = listOf("PC")))
    }

    @Test
    fun explicitPickBeatsPointing() {
        val mac = "Mac"
        val pc = "PC"
        val screen = ComputerScreen("s1", "Built-in", primary = true, x = 0, y = 0, w = 100, h = 100)
        val computers = listOf(ScreenComputer(mac, listOf(screen)), ScreenComputer(pc, emptyList()))
        val saved = listOf(SavedScreenAim(mac, "s1", "Built-in", 0f, 0f))
        val cands = listOf(TargetRule.Candidate(mac, inUse = true, lastActiveAt = 5, isPrimary = true), TargetRule.Candidate(pc))
        val aimed = PointPick.choose(AimMatcher.Aim(0f, 0f), saved, computers, cands)!!
        assertEquals(mac, aimed.computer)
        val picked = PointPick.choose(AimMatcher.Aim(0f, 0f), saved, computers, cands, explicit = pc)!!
        assertEquals(pc, picked.computer)
        assertEquals(TargetRule.Reason.EXPLICIT, picked.reason)
        assertFalse(picked.aimed)
    }
}
