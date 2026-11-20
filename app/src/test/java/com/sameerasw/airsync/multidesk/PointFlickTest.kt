package com.sameerasw.airsync.multidesk

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Point & Flick (SPACE.md §7): screen map, ordering, matching, hold-to-capture and the light messages. */
class PointFlickTest {

    private val mac = "Haitham’s MacBook"
    private val pc = "Office PC"

    private val builtIn = ComputerScreen("apple-1", "Built-in Retina Display", primary = true, x = 0, y = 0, w = 1512, h = 982)
    private val dell = ComputerScreen("dell-2", "DELL U2720Q", x = 1512, y = -200, w = 2560, h = 1440)
    private val win1 = ComputerScreen("\\\\.\\DISPLAY1", "\\\\.\\DISPLAY1", primary = true, x = 0, y = 0, w = 1920, h = 1080)
    private val win2 = ComputerScreen("\\\\.\\DISPLAY2", "\\\\.\\DISPLAY2", x = -1920, y = 0, w = 1920, h = 1080)

    private fun aim(h: Float, e: Float = 0f) = AimMatcher.Aim(h, e)
    private fun saved(c: String, s: String, h: Float, e: Float = 0f) = SavedScreenAim(c, s, s, h, e)

    // MARK: - screensInfo

    @Test
    fun parsesScreensInfoEnvelope() {
        val json = """{"type":"screensInfo","data":{"screens":[
            {"id":"apple-1","name":"Built-in Retina Display","primary":true,"x":0,"y":0,"w":1512,"h":982},
            {"id":"dell-2","name":"DELL U2720Q","primary":false,"x":1512,"y":-200,"w":2560,"h":1440.4}
        ]}}"""
        val list = ScreensInfo.parse(json)!!
        assertEquals(listOf(builtIn, dell), list)
    }

    @Test
    fun screensInfoSkipsBadEntriesAndDuplicates() {
        val json = """{"type":"screensInfo","data":{"screens":[{"name":"no id"},{"id":"a"},{"id":"a","x":5},7]}}"""
        assertEquals(listOf(ComputerScreen("a", "")), ScreensInfo.parse(json))
        assertNull(ScreensInfo.parse("""{"type":"screensInfo","data":{}}"""))
        assertNull(ScreensInfo.parse("garbage"))
        assertEquals(emptyList<ComputerScreen>(), ScreensInfo.parse("""{"data":{"screens":[]}}"""))
    }

    // MARK: - messages

    @Test
    fun messagesMatchTheSpec() {
        val req = JsonParser.parseString(PointJson.screensRequest()).asJsonObject
        assertEquals("screensRequest", req.get("type").asString)
        assertTrue(req.getAsJsonObject("data").entrySet().isEmpty())

        val lit = JsonParser.parseString(PointJson.flickAim("dell-2")).asJsonObject
        assertEquals("flickAim", lit.get("type").asString)
        assertEquals("dell-2", lit.getAsJsonObject("data").get("screen").asString)
        val off = JsonParser.parseString(PointJson.flickAim(null)).asJsonObject
        assertTrue(off.getAsJsonObject("data").get("screen").isJsonNull)

        val setup = JsonParser.parseString(PointJson.aimSetup("dell-2", 2, 3, 0.4321f)).asJsonObject.getAsJsonObject("data")
        assertEquals("dell-2", setup.get("screen").asString)
        assertEquals(2, setup.get("step").asInt)
        assertEquals(3, setup.get("total").asInt)
        assertEquals(0.43, setup.get("progress").asDouble, 1e-9)
        val hide = JsonParser.parseString(PointJson.aimSetupHide()).asJsonObject
        assertEquals("aimSetup", hide.get("type").asString)
        assertTrue(hide.getAsJsonObject("data").get("screen").isJsonNull)
    }

    // MARK: - names and order

    @Test
    fun twoSideBySideScreensAreLeftAndRight() {
        assertEquals(mapOf("apple-1" to "Left screen", "dell-2" to "Right screen"), ScreenMap.labels(listOf(dell, builtIn)))
        assertEquals(mapOf(win2.id to "Left screen", win1.id to "Right screen"), ScreenMap.labels(listOf(win1, win2)))
    }

    @Test
    fun stackedScreensAreTopAndBottom() {
        val low = ComputerScreen("low", "A", x = 0, y = 0, w = 1920, h = 1080)
        val high = ComputerScreen("high", "B", x = 100, y = -1080, w = 1920, h = 1080)
        assertEquals(mapOf("high" to "Top screen", "low" to "Bottom screen"), ScreenMap.labels(listOf(low, high)))
    }

    @Test
    fun oneScreenHasNoLabelAndThreeGetAMiddle() {
        assertEquals(mapOf("apple-1" to ""), ScreenMap.labels(listOf(builtIn)))
        val mid = ComputerScreen("m", "M", x = 1000, w = 1000, h = 800)
        val right = ComputerScreen("r", "R", x = 2000, w = 1000, h = 800)
        val left = ComputerScreen("l", "L", x = 0, w = 1000, h = 800)
        assertEquals(
            mapOf("l" to "Left screen", "m" to "Middle screen", "r" to "Right screen"),
            ScreenMap.labels(listOf(right, left, mid))
        )
    }

    @Test
    fun manyScreensUseFriendlyNamesOrNumbers() {
        val a = ComputerScreen("a", "LG UltraFine", x = 0, w = 10, h = 10)
        val b = ComputerScreen("b", "DELL P2419H", x = 10, w = 10, h = 10)
        val c = ComputerScreen("c", "Sidecar Display", x = 20, w = 10, h = 10)
        val d = ComputerScreen("d", "Built-in", x = 30, w = 10, h = 10)
        assertEquals("DELL P2419H", ScreenMap.labels(listOf(a, b, c, d))["b"])
        val w = (1..4).map { ComputerScreen("\\\\.\\DISPLAY$it", "\\\\.\\DISPLAY$it", x = it * 10, w = 10, h = 10) }
        assertEquals("Screen 1", ScreenMap.labels(w)["\\\\.\\DISPLAY1"])
        assertEquals("Screen 4", ScreenMap.labels(w)["\\\\.\\DISPLAY4"])
        assertNull(ScreenMap.friendlyName(ComputerScreen("x", "0,0,1920x1080")))
    }

    @Test
    fun setupOrderIsInUseFirstThenLeftToRight() {
        val computers = listOf(ScreenComputer(mac, listOf(dell, builtIn)), ScreenComputer(pc, listOf(win1, win2)))
        val order = ScreenMap.targets(computers, inUse = "office pc").map { it.computer to it.screen }
        assertEquals(
            listOf(pc to win2.id, pc to win1.id, mac to "apple-1", mac to "dell-2"),
            order
        )
        val noInUse = ScreenMap.targets(computers).map { it.screen }
        assertEquals(listOf("apple-1", "dell-2", win2.id, win1.id), noInUse)
    }

    @Test
    fun olderComputerIsOnePseudoScreen() {
        val t = ScreenMap.targets(listOf(ScreenComputer(mac, listOf(builtIn)), ScreenComputer(pc, null)))
        assertEquals(2, t.size)
        assertEquals("", t[1].screen)
        assertEquals(pc, t[1].title())
        assertEquals(mac, t[0].title()) // one screen: no screen part
        val two = ScreenMap.targets(listOf(ScreenComputer(mac, listOf(builtIn, dell))))
        assertEquals("$mac · Right screen", two[1].title())
    }

    @Test
    fun setupNeededOnlyWithTwoOrMoreScreensAndSomethingUnmapped() {
        val one = listOf(ScreenComputer(mac, listOf(builtIn)))
        assertTrue(ScreenMap.unmapped(one, emptyList()).isEmpty())
        val oneOld = listOf(ScreenComputer(mac, null))
        assertTrue(ScreenMap.unmapped(oneOld, emptyList()).isEmpty())

        val two = listOf(ScreenComputer(mac, listOf(builtIn)), ScreenComputer(pc, null))
        assertEquals(2, ScreenMap.unmapped(two, emptyList()).size)
        val partly = listOf(saved("haitham's macbook", "apple-1", 10f))
        assertEquals(listOf(pc to ""), ScreenMap.unmapped(two, partly).map { it.computer to it.screen })
        assertTrue(ScreenMap.unmapped(two, partly + saved(pc, "", 90f)).isEmpty())
    }

    // MARK: - matching

    @Test
    fun nearestConnectedScreenWithin35Degrees() {
        val computers = listOf(ScreenComputer(mac, listOf(builtIn, dell)), ScreenComputer(pc, listOf(win1)))
        val map = listOf(saved(mac, "apple-1", 350f), saved(mac, "dell-2", 20f), saved(pc, win1.id, 90f, 5f))
        assertEquals("apple-1", ScreenMap.nearest(aim(355f), map, computers)?.screen)
        assertEquals("dell-2", ScreenMap.nearest(aim(12f), map, computers)?.screen)
        assertEquals(pc, ScreenMap.nearest(aim(80f), map, computers)?.computer)
        assertNull(ScreenMap.nearest(aim(200f), map, computers)) // pointing away
        assertNull(ScreenMap.nearest(null, map, computers))
    }

    @Test
    fun disconnectedComputersAndRemovedScreensDontCount() {
        val map = listOf(saved(mac, "apple-1", 0f), saved(pc, win1.id, 30f), saved(mac, "gone", 60f))
        // Office PC isn't connected: pointing at it finds nothing, even though the mac is 30° away.
        val onlyMac = listOf(ScreenComputer(mac, listOf(builtIn, dell)))
        assertNull(ScreenMap.nearest(aim(36f), map, onlyMac))
        assertEquals("apple-1", ScreenMap.nearest(aim(20f), map, onlyMac)?.screen)
        // "gone" is no longer reported by the mac.
        assertNull(ScreenMap.nearest(aim(60f), map, onlyMac))
    }

    @Test
    fun onlyOneScreenInTotalMeansNoAim() {
        val map = listOf(saved(mac, "apple-1", 0f))
        assertNull(ScreenMap.nearest(aim(0f), map, listOf(ScreenComputer(mac, listOf(builtIn)))))
    }

    @Test
    fun pseudoScreenMatchesAndUnknownScreensTrustSavedIds() {
        val map = listOf(saved(mac, "apple-1", 0f), saved(pc, "", 90f))
        val computers = listOf(ScreenComputer(mac, null), ScreenComputer(pc, null))
        assertEquals("" to pc, ScreenMap.nearest(aim(85f), map, computers)?.let { it.screen to it.computer })
        assertEquals("apple-1", ScreenMap.nearest(aim(5f), map, computers)?.screen)
        // Once the computer reports real screens, its old "" entry no longer counts.
        val upgraded = listOf(ScreenComputer(mac, listOf(builtIn, dell)), ScreenComputer(pc, listOf(win1)))
        assertNull(ScreenMap.nearest(aim(90f), map, upgraded))
    }

    // MARK: - storage

    @Test
    fun codecRoundTripsAndReplaceKeepsOthers() {
        val list = listOf(saved(mac, "apple-1", 214.5f, 8.25f), saved(pc, "", 10f, -3f))
        assertEquals(list, ScreenMap.decode(ScreenMap.encode(list)))
        assertTrue(ScreenMap.decode("not json").isEmpty())
        assertTrue(ScreenMap.decode(null).isEmpty())
        val o = JsonParser.parseString(ScreenMap.encode(list)).asJsonArray[0].asJsonObject
        assertEquals(setOf("computer", "screen", "name", "h", "e"), o.keySet())

        val replaced = ScreenMap.replace(list, listOf(saved("haitham's macbook", "apple-1", 1f)))
        assertEquals(2, replaced.size)
        assertEquals(1f, replaced.first { it.screen == "apple-1" }.h)
        assertEquals(10f, replaced.first { it.computer == pc }.h)
    }

    @Test
    fun meanHandlesWrapAround() {
        val m = ScreenMap.mean(listOf(aim(358f, 2f), aim(2f, 4f)))!!
        assertTrue(m.heading < 0.5f || m.heading > 359.5f)
        assertEquals(3f, m.elevation, 0.1f)
    }

    // MARK: - hold to capture

    @Test
    fun holdingStillFillsIn900ms() {
        val h = HoldToCapture()
        var t = 0L
        var p = 0f
        while (t <= 2000L && p < 1f) {
            p = h.update(aim(10f, 0.3f * ((t / 33) % 2)), t) // sub-degree sensor jitter
            t += 33L
        }
        assertEquals(1f, p)
        assertTrue("took ${t}ms", t in 880L..1000L)
    }

    @Test
    fun movingResetsProgress() {
        val h = HoldToCapture()
        var t = 0L
        repeat(15) { h.update(aim(10f), t); t += 33 }
        assertTrue(h.progress > 0.4f)
        h.update(aim(20f), t) // jumped 10°
        assertEquals(0f, h.progress)
        t += 33
        // The jump is still inside the last 0.5 s: no progress until it has left the window.
        h.update(aim(20f), t)
        assertEquals(0f, h.progress)
        t += 500
        h.update(aim(20f), t)
        t += 33
        assertTrue(h.update(aim(20f), t) > 0f)
    }

    @Test
    fun slowDriftUnder3DegreesStillCounts() {
        val h = HoldToCapture()
        var t = 0L
        var heading = 10f
        while (t < 1000L) {
            h.update(aim(heading), t)
            heading += 0.05f // 1.5°/s
            t += 33
        }
        assertEquals(1f, h.progress)
    }

    @Test
    fun aGapInSamplesDoesNotJumpToFull() {
        val h = HoldToCapture()
        h.update(aim(10f), 0L)
        assertTrue(h.update(aim(10f), 5_000L) <= 0.12f)
    }

    // MARK: - setup run

    @Test
    fun setupCapturesEachScreenInTurnAndNeedsANewDirection() {
        val targets = ScreenMap.targets(listOf(ScreenComputer(mac, listOf(builtIn, dell)), ScreenComputer(pc, null)))
        val run = PointSetupRun(targets)
        var t = 0L
        fun hold(a: AimMatcher.Aim, ms: Long): SavedScreenAim? {
            var got: SavedScreenAim? = null
            val end = t + ms
            while (t < end && got == null) {
                got = run.feed(a, t)
                t += 33
            }
            return got
        }
        val first = hold(aim(350f, 2f), 1500)
        assertNotNull(first)
        assertEquals("apple-1", first!!.screen)
        assertEquals("Built-in Retina Display", first.name)
        assertEquals(1, run.index)

        // Still pointing at the first screen: the second can't be captured there.
        assertNull(hold(aim(350f, 2f), 2000))
        assertTrue(run.blocked)
        assertEquals(1, run.index)

        val second = hold(aim(25f, 2f), 2000)
        assertEquals("dell-2", second?.screen)
        val third = hold(aim(90f, 0f), 2000)
        assertEquals("" to pc, third?.let { it.screen to it.computer })
        assertEquals(pc, third?.name)
        assertTrue(run.done)
        assertEquals(3, run.captured.size)
        assertNull(run.feed(aim(0f), t))
    }

    @Test
    fun setupLosesProgressWithoutACompassAndCanSkip() {
        val run = PointSetupRun(ScreenMap.targets(listOf(ScreenComputer(mac, null), ScreenComputer(pc, null))))
        repeat(10) { run.feed(aim(0f), it * 33L) }
        assertTrue(run.progress > 0f)
        run.feed(null, 400L)
        assertEquals(0f, run.progress)
        run.skipCurrent()
        assertEquals(pc, run.current?.computer)
        run.skipCurrent()
        assertTrue(run.done)
    }

    // MARK: - lights on the computers

    @Test
    fun aimLightSendsOnChangeResendsAndClears() {
        val l = AimLight()
        val a = ScreenTarget(mac, "apple-1", "Left screen", "", 2)
        val b = ScreenTarget(mac, "dell-2", "Right screen", "", 2)
        val c = ScreenTarget(pc, win1.id, "", "", 1)

        assertEquals(listOf(AimLight.Send(mac, "apple-1")), l.update(a, 0))
        assertTrue(l.update(a, 1000).isEmpty())
        assertEquals(listOf(AimLight.Send(mac, "apple-1")), l.update(a, 1500)) // keep-alive
        // Same computer, other screen: just light the new one.
        assertEquals(listOf(AimLight.Send(mac, "dell-2")), l.update(b, 1600))
        // Other computer: dark here, lit there.
        assertEquals(listOf(AimLight.Send(mac, null), AimLight.Send(pc, win1.id)), l.update(c, 1700))
        // Pointing at nothing: dark.
        assertEquals(listOf(AimLight.Send(pc, null)), l.update(null, 1800))
        assertTrue(l.update(null, 5000).isEmpty())
        assertEquals(listOf(AimLight.Send(mac, null), AimLight.Send(pc, null)), l.clear(listOf(mac, pc)))
        assertNull(l.current)
        assertTrue(JsonParser.parseString(AimLight.Send(pc, null).json()).asJsonObject.getAsJsonObject("data").get("screen").isJsonNull)
    }

    @Test
    fun setupLightThrottlesAndHidesThePreviousComputer() {
        val l = SetupLight()
        val a = ScreenTarget(mac, "apple-1", "", "", 1)
        val c = ScreenTarget(pc, "", "", "", 1)
        fun screenOf(s: SetupLight.Send) =
            JsonParser.parseString(s.json).asJsonObject.getAsJsonObject("data").get("screen").let { if (it.isJsonNull) null else it.asString }

        val first = l.update(a, 1, 2, 0f, 0)
        assertEquals(listOf(mac to "apple-1"), first.map { it.computer to screenOf(it) })
        assertTrue(l.update(a, 1, 2, 0.01f, 30).isEmpty())   // too small, too soon
        assertTrue(l.update(a, 1, 2, 0.2f, 60).isEmpty())    // big enough but too soon
        assertEquals(1, l.update(a, 1, 2, 0.2f, 120).size)
        assertTrue(l.update(a, 1, 2, 0.2f, 500).isEmpty())
        assertEquals(1, l.update(a, 1, 2, 0.2f, 1700).size)  // keep-alive
        assertTrue(l.update(a, 1, 2, 1f, 1750).isEmpty())    // too soon after the keep-alive
        assertEquals(1, l.update(a, 1, 2, 1f, 1810).size)    // the full ring goes out

        val next = l.update(c, 2, 2, 0f, 1900)
        assertEquals(listOf(mac to null, pc to ""), next.map { it.computer to screenOf(it) })
        val step = JsonParser.parseString(next[1].json).asJsonObject.getAsJsonObject("data")
        assertEquals(2, step.get("step").asInt)
        assertEquals(2, step.get("total").asInt)

        val end = l.end(listOf(mac, pc))
        assertEquals(listOf(mac to null, pc to null), end.map { it.computer to screenOf(it) })
        assertFalse(end.isEmpty())
    }
}
