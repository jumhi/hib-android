package com.sameerasw.airsync.multidesk

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Point to act (SPACE.md §11): the shared point target, the labelled aim light and the Desk Deck hand-over. */
class PointActTest {

    private val mac = "Haitham’s MacBook"
    private val pc = "Office PC"

    private val builtIn = ComputerScreen("apple-1", "Built-in Retina Display", primary = true, x = 0, y = 0, w = 1512, h = 982)
    private val win1 = ComputerScreen("\\\\.\\DISPLAY1", "\\\\.\\DISPLAY1", primary = true, x = 0, y = 0, w = 1920, h = 1080)

    private val computers = listOf(ScreenComputer(mac, listOf(builtIn)), ScreenComputer(pc, listOf(win1)))
    private val saved = listOf(
        SavedScreenAim(mac, builtIn.id, builtIn.name, 0f, 0f),
        SavedScreenAim(pc, win1.id, "", 90f, 0f)
    )

    private fun aim(h: Float, e: Float = 0f) = AimMatcher.Aim(h, e)
    private fun candidates(macInUse: Boolean = false) = listOf(
        TargetRule.Candidate(mac, inUse = macInUse, lastActiveAt = if (macInUse) 1000L else 0L, isPrimary = true),
        TargetRule.Candidate(pc)
    )

    private fun data(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonObject("data")

    // MARK: - target selection

    @Test
    fun pointingAtAScreenPicksItsComputer() {
        val c = PointPick.choose(aim(85f, 4f), saved, computers, candidates(macInUse = true))!!
        assertEquals(pc, c.computer)
        assertEquals(TargetRule.Reason.AIM, c.reason)
        assertTrue(c.aimed)
        assertEquals(win1.id, c.screen!!.screen)
    }

    @Test
    fun pointingAtNothingFallsBackToTheTargetRuleWithNothingLit() {
        // 180° is far from both saved screens (> 35°): the computer in use decides, nothing is lit.
        val away = PointPick.choose(aim(180f), saved, computers, candidates(macInUse = true))!!
        assertEquals(mac, away.computer)
        assertEquals(TargetRule.Reason.IN_USE, away.reason)
        assertFalse(away.aimed)
        assertNull(away.screen)

        // No compass reading yet: the target rule too (here the main computer).
        val noReading = PointPick.choose(null, saved, computers, candidates())!!
        assertEquals(mac, noReading.computer)
        assertEquals(TargetRule.Reason.PRIMARY, noReading.reason)
        assertNull(noReading.screen)
    }

    @Test
    fun aimBeatsTheComputerInUseAndOnlyConnectedComputersCount() {
        assertEquals(mac, PointPick.choose(aim(10f), saved, computers, candidates())!!.computer)
        // The PC's screen is aimed at but the PC isn't connected (not a candidate, not in the computer list).
        val macOnly = listOf(computers[0])
        val c = PointPick.choose(aim(90f), saved, macOnly, listOf(candidates()[0]))!!
        assertEquals(mac, c.computer)
        assertNull(c.screen)
        assertNull(PointPick.choose(aim(90f), saved, emptyList(), emptyList()))
    }

    @Test
    fun oneScreenInTotalMeansNoAim() {
        val one = listOf(ScreenComputer(mac, listOf(builtIn)))
        val c = PointPick.choose(aim(0f), saved, one, listOf(candidates()[0]))!!
        assertEquals(mac, c.computer)
        assertFalse(c.aimed)
    }

    // MARK: - flickAim label

    @Test
    fun flickAimCarriesTheLabelWithAScreenOnly() {
        val lit = data(PointJson.flickAim("dell-2", PointLabels.DESK_DECK))
        assertEquals("dell-2", lit.get("screen").asString)
        assertEquals("Desk Deck", lit.get("label").asString)

        val plain = data(PointJson.flickAim("dell-2"))
        assertFalse(plain.has("label"))  // Flick: the computer's default "Flick lands here"

        val off = data(PointJson.flickAim(null, PointLabels.REMOTE))
        assertTrue(off.get("screen").isJsonNull)
        assertFalse(off.has("label"))

        assertFalse(data(PointJson.flickAim("a", "  ")).has("label"))
        assertEquals("Media", data(AimLight.Send(pc, "a", PointLabels.MEDIA).json()).get("label").asString)
        assertEquals(listOf("Desk Deck", "Remote", "Scenes", "Media"),
            listOf(PointLabels.DESK_DECK, PointLabels.REMOTE, PointLabels.SCENES, PointLabels.MEDIA))
    }

    // MARK: - re-send / null bookkeeping

    @Test
    fun sessionLightsTheAimedScreenWithTheNewestLabel() {
        val s = PointSession()
        val atMac = PointChoice(mac, TargetRule.Reason.AIM, ScreenTarget(mac, builtIn.id, "", "", 1))
        val atPc = PointChoice(pc, TargetRule.Reason.AIM, ScreenTarget(pc, win1.id, "", "", 1))
        val fallback = PointChoice(mac, TargetRule.Reason.IN_USE)

        // Nothing is open: no tracking, nothing sent, the target rule decides.
        assertTrue(s.update(atMac, 0).isEmpty())
        assertEquals("fallback", s.targetId { "fallback" })

        val remote = s.hold(PointLabels.REMOTE)
        assertTrue(s.active)
        assertEquals(listOf(AimLight.Send(mac, builtIn.id, "Remote")), s.update(atMac, 0))
        assertEquals(mac, s.targetId { "fallback" })
        assertTrue(s.update(atMac, 1000).isEmpty())
        assertEquals(listOf(AimLight.Send(mac, builtIn.id, "Remote")), s.update(atMac, 1500))  // keep-alive

        // The open player comes on top: same screen, new label, sent at once.
        val media = s.hold(PointLabels.MEDIA)
        assertEquals(listOf(AimLight.Send(mac, builtIn.id, "Media")), s.update(atMac, 1600))
        assertTrue(s.update(atMac, 1700).isEmpty())

        // Pointing at the PC: dark on the Mac, lit on the PC.
        assertEquals(listOf(AimLight.Send(mac, null), AimLight.Send(pc, win1.id, "Media")), s.update(atPc, 1800))
        assertEquals(pc, s.targetId { "fallback" })

        // Player closed: back to the Remote's label.
        assertFalse(s.release(media))
        assertEquals("Remote", s.label)
        assertEquals(listOf(AimLight.Send(pc, win1.id, "Remote")), s.update(atPc, 1900))

        // Pointing at nothing: the PC goes dark, the target rule decides.
        assertEquals(listOf(AimLight.Send(pc, null)), s.update(fallback, 2000))
        assertEquals(mac, s.targetId { "fallback" })
        assertTrue(s.update(fallback, 5000).isEmpty())

        // Last screen leaves: dark everywhere, no live target.
        s.update(atPc, 6000)
        assertTrue(s.release(remote))
        assertFalse(s.active)
        assertEquals(listOf(AimLight.Send(mac, null), AimLight.Send(pc, null)), s.stop(listOf(mac, pc)))
        assertNull(s.choice)
        assertNull(s.lit)
        assertEquals("fallback", s.targetId { "fallback" })
        assertTrue(s.update(atPc, 7000).isEmpty())
        assertFalse(s.release(remote))  // a second release is harmless
    }

    @Test
    fun flickStillLightsWithoutALabel() {
        val l = AimLight()
        val a = ScreenTarget(mac, builtIn.id, "", "", 1)
        assertEquals(listOf(AimLight.Send(mac, builtIn.id)), l.update(a, 0))
        assertFalse(data(l.update(a, 1500).single().json()).has("label"))
    }

    // MARK: - press-time pick (Home Scenes card)

    @Test
    fun aPressPicksWhereThePhonePointsAndGlowsOnce() {
        val owners = mapOf(mac to listOf("focus", "movie"), pc to listOf("movie"))

        // Pointing at the PC when pressed: the PC runs it, its screen glows once with "Scenes", then dark.
        val atPc = PointPick.choose(aim(92f), saved, computers, candidates(macInUse = true))!!
        assertEquals(pc, PointPress.sceneTarget(atPc, "movie", owners))
        val (on, off) = PointPress.glow(atPc, PointLabels.SCENES)!!
        assertEquals(AimLight.Send(pc, win1.id, "Scenes"), on)
        assertEquals(AimLight.Send(pc, null), off)
        assertEquals("Scenes", data(on.json()).get("label").asString)
        assertTrue(data(off.json()).get("screen").isJsonNull)

        // Aimed at a computer without that scene: nothing is sent there (the card says so).
        assertNull(PointPress.sceneTarget(atPc, "focus", owners))
        assertEquals(pc, PointPress.sceneTarget(atPc, "focus", owners, unknown = listOf("focus")))

        // Not pointing at a screen: target rule, no glow; a scene only another computer has runs there.
        val away = PointPick.choose(aim(200f), saved, computers, candidates())!!
        assertNull(PointPress.glow(away, PointLabels.SCENES))
        assertEquals(mac, PointPress.sceneTarget(away, "movie", owners))
        val pcFirst = PointChoice(pc, TargetRule.Reason.IN_USE)
        assertEquals(mac, PointPress.sceneTarget(pcFirst, "focus", owners))
        assertNull(PointPress.sceneTarget(null, "movie", owners))
        assertEquals(400L, PointPress.WAIT_MS)
    }

    // MARK: - Desk Deck hand-over

    @Test
    fun deckOpenMovesWithTheTarget() {
        val d = DeckHandover()
        assertNull(d.reopen())
        assertTrue(d.close().isEmpty())

        assertEquals(listOf(DeckHandover.Send(mac, true)), d.moveTo(mac))
        assertTrue(d.moveTo("haitham's macbook").isEmpty())  // same computer, other spelling
        assertEquals(DeckHandover.Send(mac, true), d.reopen())

        assertEquals(listOf(DeckHandover.Send(mac, false), DeckHandover.Send(pc, true)), d.moveTo(pc))
        assertEquals(pc, d.current)

        // Only the deck's computer feeds its live state.
        assertTrue(d.accepts(pc))
        assertFalse(d.accepts(mac))
        assertTrue(d.accepts(null))

        assertEquals(listOf(DeckHandover.Send(pc, false)), d.close())
        assertNull(d.current)
        assertTrue(d.accepts(mac))

        // Nothing connected any more: it closes where it was.
        d.moveTo(mac)
        assertEquals(listOf(DeckHandover.Send(mac, false)), d.moveTo(null))
        assertNull(d.current)
    }

    @Test
    fun deckOpenJsonMatchesTheMac() {
        val open = JsonParser.parseString(DeckHandover.Send(pc, true).json()).asJsonObject
        assertEquals("deckOpen", open.get("type").asString)
        assertTrue(open.getAsJsonObject("data").get("open").asBoolean)
        assertFalse(data(DeckHandover.Send(pc, false).json()).get("open").asBoolean)
    }
}
