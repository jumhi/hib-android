package com.sameerasw.airsync.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** SPACE.md §15.5: touchpad and direct modes, held keys, shortcut row, sticky modifiers, unlock texts. */
class RemoteControlsTest {

    private fun p(id: Long, x: Float, y: Float, down: Boolean = true) = TouchPoint(id, x, y, down)
    private fun PointerRecogniser.run(vararg events: Pair<Long, List<TouchPoint>>): List<PadAction> =
        events.flatMap { (t, pts) -> onEvent(t, pts) }
    private fun near(x: Float, y: Float, got: Pair<Float, Float>) {
        assertEquals(x, got.first, 1e-5f)
        assertEquals(y, got.second, 1e-5f)
    }
    private fun data(json: String) = JsonParser.parseString(json).asJsonObject.getAsJsonObject("data")

    // MARK: - acceleration curve

    @Test
    fun accelIsOneToOneWhenSlow() {
        assertEquals(1f, PointerAccel.gain(0f), 0f)
        assertEquals(1f, PointerAccel.gain(PointerAccel.SLOW), 0f)
        assertEquals(1f, PointerAccel.gain(Float.NaN), 0f)
        assertEquals(1f, PointerAccel.gain(-3f), 0f)
    }

    @Test
    fun accelIsMonotonicAndBounded() {
        var last = 0f
        var v = 0f
        while (v < 10f) {
            val g = PointerAccel.gain(v)
            assertTrue("gain($v)=$g fell below $last", g >= last - 1e-6f)
            assertTrue(g in 1f..PointerAccel.MAX_GAIN)
            last = g
            v += 0.01f
        }
        assertEquals(PointerAccel.MAX_GAIN, PointerAccel.gain(PointerAccel.FAST), 1e-4f)
        assertEquals(PointerAccel.MAX_GAIN, PointerAccel.gain(1000f), 1e-4f)
        assertEquals(PointerAccel.MAX_GAIN, PointerAccel.gain(Float.POSITIVE_INFINITY), 1e-4f)
    }

    @Test
    fun reachRaisesOnlyTheTop() {
        assertEquals(1f, PointerAccel.gain(0.05f, reach = 1.5f), 0f)
        assertEquals(PointerAccel.MAX_GAIN * 1.5f, PointerAccel.gain(5f, reach = 1.5f), 1e-4f)
        assertEquals(1f, PointerAccel.reachFor(1920), 0f)
        assertTrue(PointerAccel.reachFor(5120) > 1.2f)
        assertTrue(PointerAccel.reachFor(100_000) <= PointerAccel.MAX_REACH)
    }

    @Test
    fun accelMoveKeepsDirectionAndPrecision() {
        // 2 dp in 16 ms (0.125 dp/ms, slow): almost exactly 1:1.
        val (sx, sy) = PointerAccel.move(2f, 0f, 16, speed = 1f)
        assertEquals(2f, sx, 0.05f)
        assertEquals(0f, sy, 0f)
        // A fast flick goes much further than its finger distance, in the same direction.
        val (fx, fy) = PointerAccel.move(-30f, 40f, 16, speed = 1f)
        assertTrue(fx < -30f * 4 && fy > 40f * 4)
        assertEquals(-0.6f, fx / hypot(fx, fy), 1e-4f)
        // dt is clamped so a hiccup in event timing doesn't fake a fast flick.
        val (zx, _) = PointerAccel.move(1f, 0f, 0, speed = 1f)
        assertTrue(zx < 2f)
    }

    // MARK: - touchpad gestures

    @Test
    fun touchpadTapIsALeftClickWithoutMoving() {
        val g = TouchpadGestures()
        val a = g.run(0L to listOf(p(1, 50f, 50f)), 30L to listOf(p(1, 51f, 50f)), 90L to listOf(p(1, 51f, 50f, false)))
        assertEquals(listOf<PadAction>(PadAction.Click(PadAction.LEFT)), a)
    }

    @Test
    fun touchpadLongStillPressIsNotAClick() {
        val g = TouchpadGestures()
        val a = g.run(0L to listOf(p(1, 50f, 50f)), 600L to listOf(p(1, 50f, 50f, false)))
        assertTrue(a.none { it is PadAction.Click })
    }

    @Test
    fun touchpadSlideMovesWithHeldBackSlop() {
        val g = TouchpadGestures()
        val a = g.run(
            0L to listOf(p(1, 0f, 0f)), 8L to listOf(p(1, 3f, 0f)), 16L to listOf(p(1, 6f, 0f)),
            24L to listOf(p(1, 12f, 0f)), 32L to listOf(p(1, 18f, 0f)), 40L to listOf(p(1, 18f, 0f, false))
        )
        val m = a.filterIsInstance<PadAction.Move>()
        assertTrue(m.isNotEmpty())
        // Nothing is lost: the first move carries what was held inside the tap slop.
        assertTrue(m.sumOf { it.dx.toDouble() } >= 18.0)
        assertTrue(m.all { it.dy == 0f && it.dx > 0f })
        assertTrue(a.none { it is PadAction.Click })
    }

    @Test
    fun touchpadTwoFingerTapIsARightClick() {
        val g = TouchpadGestures()
        val a = g.run(
            0L to listOf(p(1, 50f, 50f)), 10L to listOf(p(1, 50f, 50f), p(2, 90f, 50f)),
            80L to listOf(p(1, 51f, 50f), p(2, 90f, 51f)),
            120L to listOf(p(1, 51f, 50f, false), p(2, 90f, 51f)), 130L to listOf(p(2, 90f, 51f, false))
        )
        assertEquals(listOf<PadAction>(PadAction.Click(PadAction.RIGHT)), a)
    }

    @Test
    fun touchpadTwoFingerSlideScrollsAndFlings() {
        val g = TouchpadGestures(naturalScroll = true)
        val a = g.run(
            0L to listOf(p(1, 50f, 100f), p(2, 90f, 100f)),
            8L to listOf(p(1, 50f, 90f), p(2, 90f, 90f)),
            16L to listOf(p(1, 50f, 70f), p(2, 90f, 70f)),
            24L to listOf(p(1, 50f, 50f), p(2, 90f, 50f)),
            30L to listOf(p(1, 50f, 50f, false), p(2, 90f, 50f, false))
        )
        val s = a.filterIsInstance<PadAction.Scroll>()
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { it.dy < 0f && it.dx == 0f })
        assertTrue(a.none { it is PadAction.Click || it is PadAction.Move })
        val f = a.filterIsInstance<PadAction.Fling>().single()
        assertTrue(f.vy < 0f)
        // Natural scrolling off flips the sign.
        val h = TouchpadGestures(naturalScroll = false)
        val b = h.run(0L to listOf(p(1, 0f, 100f), p(2, 40f, 100f)), 8L to listOf(p(1, 0f, 80f), p(2, 40f, 80f)))
        assertTrue(b.filterIsInstance<PadAction.Scroll>().single().dy > 0f)
    }

    @Test
    fun touchpadScrollThatStopsBeforeLiftDoesNotFling() {
        val g = TouchpadGestures()
        val a = g.run(
            0L to listOf(p(1, 50f, 100f), p(2, 90f, 100f)),
            8L to listOf(p(1, 50f, 80f), p(2, 90f, 80f)),
            16L to listOf(p(1, 50f, 60f), p(2, 90f, 60f)),
            400L to listOf(p(1, 50f, 60f, false), p(2, 90f, 60f, false))
        )
        assertTrue(a.none { it is PadAction.Fling })
    }

    @Test
    fun touchpadDoubleTapAndHoldDrags() {
        val g = TouchpadGestures()
        val a = g.run(
            0L to listOf(p(1, 50f, 50f)), 60L to listOf(p(1, 50f, 50f, false)),
            160L to listOf(p(1, 52f, 50f)), 170L to listOf(p(1, 70f, 50f)), 180L to listOf(p(1, 90f, 50f)),
            220L to listOf(p(1, 90f, 50f, false))
        )
        assertEquals(PadAction.Click(PadAction.LEFT), a.first())
        val down = a.indexOf(PadAction.Button(PadAction.LEFT, true))
        val up = a.indexOf(PadAction.Button(PadAction.LEFT, false))
        assertTrue(down in 1 until up)
        assertTrue(a.subList(down, up).any { it is PadAction.Move })
        assertEquals(up, a.lastIndex)
    }

    @Test
    fun touchpadDoubleTapHoldStartsTheDragOnTheTimer() {
        val g = TouchpadGestures()
        g.run(0L to listOf(p(1, 50f, 50f)), 60L to listOf(p(1, 50f, 50f, false)), 160L to listOf(p(1, 50f, 50f)))
        val due = g.nextTimerAt()
        assertNotNull(due)
        assertEquals(listOf<PadAction>(PadAction.Button(PadAction.LEFT, true)), g.onTimer(due!!))
        assertNull(g.nextTimerAt())
        assertEquals(listOf<PadAction>(PadAction.Button(PadAction.LEFT, false)), g.onEvent(900L, listOf(p(1, 50f, 50f, false))))
    }

    @Test
    fun touchpadQuickDoubleTapIsTwoClicks() {
        val g = TouchpadGestures()
        val a = g.run(
            0L to listOf(p(1, 50f, 50f)), 60L to listOf(p(1, 50f, 50f, false)),
            150L to listOf(p(1, 51f, 50f)), 200L to listOf(p(1, 51f, 50f, false))
        )
        assertEquals(listOf(PadAction.Click(PadAction.LEFT), PadAction.Click(PadAction.LEFT)), a)
    }

    @Test
    fun touchpadThreeFingerSwipeSwitchesApps() {
        val g = TouchpadGestures()
        val three = { x: Float -> listOf(p(1, x, 50f), p(2, x + 30f, 50f), p(3, x + 60f, 50f)) }
        val a = g.run(
            0L to three(0f), 10L to three(20f), 20L to three(50f), 30L to three(90f), 40L to three(140f),
            50L to three(200f), 60L to three(100f), 70L to three(0f),
            80L to three(0f).map { it.copy(pressed = false) }
        )
        val steps = a.filterIsInstance<PadAction.Switch>()
        assertTrue(steps.size >= 3)
        assertTrue(steps.first().forward)
        assertTrue(steps.any { !it.forward })
        assertEquals(PadAction.SwitchEnd, a.last())
        assertTrue(a.none { it is PadAction.Move || it is PadAction.Scroll || it is PadAction.Click })
    }

    @Test
    fun touchpadThreeFingerTapDoesNothing() {
        val g = TouchpadGestures()
        val three = listOf(p(1, 0f, 50f), p(2, 30f, 50f), p(3, 60f, 50f))
        val a = g.run(0L to three, 60L to three.map { it.copy(pressed = false) })
        assertTrue(a.isEmpty())
    }

    @Test
    fun touchpadCancelReleasesWhatIsHeld() {
        val g = TouchpadGestures()
        val three = { x: Float -> listOf(p(1, x, 50f), p(2, x + 30f, 50f), p(3, x + 60f, 50f)) }
        g.run(0L to three(0f), 10L to three(60f))
        assertEquals(listOf<PadAction>(PadAction.SwitchEnd), g.cancel())
        assertTrue(g.cancel().isEmpty())
    }

    // MARK: - direct gestures

    @Test
    fun directTapClicksWhereTheFingerWent() {
        val g = DirectGestures()
        val a = g.run(0L to listOf(p(1, 120f, 80f)), 70L to listOf(p(1, 122f, 81f, false)))
        assertEquals(listOf(PadAction.MoveTo(120f, 80f), PadAction.Click(PadAction.LEFT)), a)
    }

    @Test
    fun directLongPressIsARightClick() {
        val g = DirectGestures()
        g.onEvent(0L, listOf(p(1, 10f, 20f)))
        assertEquals(DirectGestures.LONG_PRESS_MS, g.nextTimerAt())
        assertEquals(listOf(PadAction.MoveTo(10f, 20f), PadAction.Click(PadAction.RIGHT)), g.onTimer(DirectGestures.LONG_PRESS_MS))
        assertTrue(g.onEvent(700L, listOf(p(1, 10f, 20f, false))).isEmpty())
    }

    @Test
    fun directDragIsALeftDragByAbsoluteMoves() {
        val g = DirectGestures()
        val a = g.run(
            0L to listOf(p(1, 10f, 10f)), 20L to listOf(p(1, 30f, 10f)), 40L to listOf(p(1, 60f, 15f)),
            60L to listOf(p(1, 60f, 15f, false))
        )
        assertEquals(PadAction.MoveTo(10f, 10f), a[0])
        assertEquals(PadAction.Button(PadAction.LEFT, true), a[1])
        assertEquals(PadAction.MoveTo(60f, 15f), a[a.size - 2])
        assertEquals(PadAction.Button(PadAction.LEFT, false), a.last())
        assertNull(g.nextTimerAt())
    }

    @Test
    fun directTwoFingersScrollOrPinch() {
        val s = DirectGestures().run(
            0L to listOf(p(1, 50f, 100f), p(2, 90f, 100f)),
            8L to listOf(p(1, 50f, 80f), p(2, 90f, 80f)),
            16L to listOf(p(1, 50f, 60f), p(2, 90f, 60f))
        )
        assertTrue(s.filterIsInstance<PadAction.Scroll>().isNotEmpty())
        assertTrue(s.none { it is PadAction.Click || it is PadAction.MoveTo })
        val z = DirectGestures().run(
            0L to listOf(p(1, 100f, 100f), p(2, 140f, 100f)),
            8L to listOf(p(1, 80f, 100f), p(2, 160f, 100f)),
            16L to listOf(p(1, 60f, 100f), p(2, 180f, 100f))
        )
        val zooms = z.filterIsInstance<PadAction.Zoom>()
        assertTrue(zooms.isNotEmpty() && zooms.all { it.factor > 1f })
        assertTrue(z.none { it is PadAction.Scroll })
    }

    // MARK: - picture gestures (touchpad mode)

    @Test
    fun pictureTapExpandsAndSwipeDownShrinks() {
        assertEquals(listOf<PadAction>(PadAction.Expand),
            PictureGestures().run(0L to listOf(p(1, 10f, 10f)), 80L to listOf(p(1, 10f, 10f, false))))
        assertEquals(listOf<PadAction>(PadAction.Shrink),
            PictureGestures().run(0L to listOf(p(1, 10f, 10f)), 40L to listOf(p(1, 12f, 60f)), 80L to listOf(p(1, 14f, 120f, false))))
        assertEquals(listOf<PadAction>(PadAction.Expand),
            PictureGestures().run(0L to listOf(p(1, 10f, 120f)), 80L to listOf(p(1, 12f, 10f, false))))
    }

    // MARK: - absolute mapping

    @Test
    fun absMapsTheWholePicture() {
        // 160×90 box, 16:9 picture: no bars.
        near(0.5f, 0.5f, AbsMap.toDisplay(80f, 45f, 160f, 90f, 16f / 9f, ViewRect.FULL))
        near(0f, 0f, AbsMap.toDisplay(0f, 0f, 160f, 90f, 16f / 9f, ViewRect.FULL))
        near(1f, 1f, AbsMap.toDisplay(160f, 90f, 160f, 90f, 16f / 9f, ViewRect.FULL))
    }

    @Test
    fun absCorrectsForLetterboxAndPillarbox() {
        // A 32:9 G9 in a 320×180 box: picture is 320×90, bars of 45 above and below.
        val (x, y) = AbsMap.toDisplay(160f, 45f + 22.5f, 320f, 180f, 32f / 9f, ViewRect.FULL)
        assertEquals(0.5f, x, 1e-5f)
        assertEquals(0.25f, y, 1e-5f)
        // A tap on a bar clamps to the edge.
        assertEquals(0f, AbsMap.toDisplay(160f, 10f, 320f, 180f, 32f / 9f, ViewRect.FULL).second, 0f)
        // A 4:3 picture in a 200×100 box: 133.3 wide, bars left and right.
        val (px, _) = AbsMap.toDisplay(100f - 133.333f / 4f, 50f, 200f, 100f, 4f / 3f, ViewRect.FULL)
        assertEquals(0.25f, px, 1e-3f)
        val fit = AbsMap.fit(200f, 100f, 4f / 3f)
        assertEquals(33.333f, fit.left, 1e-2f)
        assertEquals(0f, fit.top, 0f)
    }

    @Test
    fun absCorrectsForZoomAndPan() {
        // 2× zoom showing the display's lower-right quarter.
        val view = ViewRect(0.5f, 0.5f, 0.5f, 0.5f)
        near(0.75f, 0.75f, AbsMap.toDisplay(80f, 45f, 160f, 90f, 16f / 9f, view))
        near(0.5f, 0.5f, AbsMap.toDisplay(0f, 0f, 160f, 90f, 16f / 9f, view))
        // 4× at (0.1, 0.3), with letterboxing.
        val v4 = ViewRect(0.1f, 0.3f, 0.25f, 0.25f)
        val (x, y) = AbsMap.toDisplay(160f, 45f + 90f, 320f, 180f, 32f / 9f, v4)
        assertEquals(0.1f + 0.5f * 0.25f, x, 1e-5f)
        assertEquals(0.3f + 1f * 0.25f, y, 1e-5f)
    }

    @Test
    fun absHandlesEmptyBoxes() {
        near(0.5f, 0.5f, AbsMap.toDisplay(0f, 0f, 0f, 0f, 16f / 9f, ViewRect.FULL))
        near(0.5f, 0.5f, AbsMap.toDisplay(5f, 5f, 10f, 10f, Float.NaN, ViewRect.FULL))
    }

    // MARK: - wire messages

    @Test
    fun absoluteMoveAndHeldKeyMessages() {
        val m = data(RemoteInput.moveAbs(0.42f, 0.17f, "disp-1"))
        assertEquals("mouse_move_abs", m.get("action").asString)
        assertEquals(0.42, m.get("x").asDouble, 1e-6)
        assertEquals(0.17, m.get("y").asDouble, 1e-6)
        assertEquals("disp-1", m.get("display").asString)
        val k = data(RemoteInput.hold(55, true))
        assertEquals("key", k.get("action").asString)
        assertEquals(55, k.get("keycode").asInt)
        assertTrue(k.get("isDown").asBoolean)
        assertFalse(data(RemoteInput.hold(55, false)).get("isDown").asBoolean)
    }

    // MARK: - coalescing

    @Test
    fun movesWithinAFrameAreSummedNeverQueued() {
        val c = InputCoalescer(intervalMs = 8)
        assertTrue(c.addMove(1f, 2f, now = 100))        // idle: send at once
        assertEquals(1, c.drain(100).size)
        assertFalse(c.addMove(3f, 0f, now = 103))       // inside the frame: held
        assertFalse(c.addMove(4f, 1f, now = 105))
        assertEquals(3L, c.waitMs(105))
        val out = c.drain(108)
        assertEquals(1, out.size)
        val d = data(out[0])
        assertEquals(7.0, d.get("dx").asDouble, 1e-6)
        assertEquals(1.0, d.get("dy").asDouble, 1e-6)
        assertTrue(c.drain(120).isEmpty())
    }

    @Test
    fun absoluteMovesKeepOnlyTheLatest() {
        val c = InputCoalescer(intervalMs = 8)
        c.drain(0)
        c.setAbs(0.1f, 0.1f, "", now = 2)
        c.setAbs(0.3f, 0.4f, "", now = 4)
        c.addScroll(0f, -5f, now = 5)
        val out = c.drain(9)
        assertEquals(2, out.size)
        assertEquals(0.3, data(out[0]).get("x").asDouble, 1e-6)
        assertEquals("mouse_scroll", data(out[1]).get("action").asString)
    }

    @Test
    fun inertiaDecaysToAStop() {
        var v = 2f
        var total = 0f
        var steps = 0
        while (abs(v) > ScrollInertia.STOP && steps < 1000) {
            val (d, nv) = ScrollInertia.step(v, 16)
            assertTrue(abs(nv) < abs(v))
            total += d; v = nv; steps++
        }
        assertTrue(steps in 5..200)
        assertEquals(2f * ScrollInertia.TAU_MS, total, 2f * ScrollInertia.TAU_MS * 0.1f)
    }

    // MARK: - keyboard

    @Test
    fun usLayoutTable() {
        assertEquals(UsKeyboard.Stroke(0, false), UsKeyboard.stroke('a'))
        assertEquals(UsKeyboard.Stroke(0, true), UsKeyboard.stroke('A'))
        assertEquals(UsKeyboard.Stroke(1, false), UsKeyboard.stroke('s'))
        assertEquals(UsKeyboard.Stroke(3, false), UsKeyboard.stroke('f'))
        assertEquals(UsKeyboard.Stroke(18, false), UsKeyboard.stroke('1'))
        assertEquals(UsKeyboard.Stroke(18, true), UsKeyboard.stroke('!'))
        assertEquals(UsKeyboard.Stroke(29, true), UsKeyboard.stroke(')'))
        assertEquals(UsKeyboard.Stroke(44, false), UsKeyboard.stroke('/'))
        assertEquals(UsKeyboard.Stroke(44, true), UsKeyboard.stroke('?'))
        assertEquals(UsKeyboard.Stroke(50, true), UsKeyboard.stroke('~'))
        assertEquals(UsKeyboard.Stroke(49, false), UsKeyboard.stroke(' '))
        assertEquals(UsKeyboard.Stroke(36, false), UsKeyboard.stroke('\n'))
        assertEquals(UsKeyboard.Stroke(48, false), UsKeyboard.stroke('\t'))
        assertNull(UsKeyboard.stroke('é'))
        assertNull(UsKeyboard.stroke("😀".first()))
        // Every printable ASCII character has a key, and letters agree with the macOS ANSI layout.
        (32..126).forEach { assertNotNull("char $it", UsKeyboard.stroke(it.toChar())) }
        val ansi = "asdfhgzxcv?bqweryt".toList()
        ansi.forEachIndexed { code, c -> if (c != '?') assertEquals(code, UsKeyboard.stroke(c)!!.keycode) }
    }

    @Test
    fun stickyModifierStateMachine() {
        var s = StickyMods()
        assertTrue(s.active.isEmpty())
        // Tap: one-shot.
        s = s.tap("command", 0)
        assertEquals(StickyMods.State.ONCE, s.state("command"))
        val (mods, after) = s.consume()
        assertEquals(listOf("command"), mods)
        assertEquals(StickyMods.State.OFF, after.state("command"))
        // Double-tap: locked until tapped again; consuming keeps it.
        s = StickyMods().tap("option", 0).tap("option", 200)
        assertEquals(StickyMods.State.LOCKED, s.state("option"))
        val (m2, kept) = s.consume()
        assertEquals(listOf("option"), m2)
        assertEquals(StickyMods.State.LOCKED, kept.state("option"))
        assertEquals(StickyMods.State.OFF, kept.tap("option", 5_000).state("option"))
        // A slow second tap turns a one-shot off instead.
        assertEquals(StickyMods.State.OFF, StickyMods().tap("shift", 0).tap("shift", 900).state("shift"))
        // Mixed: a locked one stays, a one-shot one goes.
        val mix = StickyMods().tap("command", 0).tap("command", 100).tap("shift", 1_000)
        val (m3, rest) = mix.consume()
        assertEquals(setOf("command", "shift"), m3.toSet())
        assertEquals(listOf("command"), rest.active)
        assertTrue(rest.locked("command"))
        assertTrue(StickyMods().tap("command", 0).clear().active.isEmpty())
    }

    @Test
    fun typedTextWithModifiersBecomesKeypresses() {
        // No modifier: plain text.
        val (plain, _) = KeyCompose.typed("hi", StickyMods())
        assertEquals(listOf("type"), plain.map { data(it).get("action").asString })
        // ⌘ once + "s" → ⌘S, the rest is text again.
        val (out, after) = KeyCompose.typed("sa", StickyMods().tap("command", 0))
        assertEquals(2, out.size)
        val k = data(out[0])
        assertEquals("keypress", k.get("action").asString)
        assertEquals(1, k.get("keycode").asInt)
        assertEquals(listOf("command"), k.getAsJsonArray("modifiers").map { it.asString })
        assertEquals("a", data(out[1]).get("text").asString)
        assertTrue(after.active.isEmpty())
        // Locked ⌃ + "C" → ⌃⇧C for every character.
        val (locked, still) = KeyCompose.typed("Cc", StickyMods().tap("ctrl", 0).tap("ctrl", 100))
        assertEquals(2, locked.size)
        assertEquals(listOf("ctrl", "shift"), data(locked[0]).getAsJsonArray("modifiers").map { it.asString })
        assertEquals(listOf("ctrl"), data(locked[1]).getAsJsonArray("modifiers").map { it.asString })
        assertTrue(still.locked("ctrl"))
        // A character with no US key still goes, as text.
        val (odd, _) = KeyCompose.typed("é", StickyMods().tap("command", 0))
        assertEquals("type", data(odd.single()).get("action").asString)
        // Strip keys use the modifiers too.
        val (esc, cleared) = KeyCompose.key(MacKey.ESCAPE, StickyMods().tap("option", 0))
        assertEquals(listOf("option"), data(esc).getAsJsonArray("modifiers").map { it.asString })
        assertTrue(cleared.active.isEmpty())
    }

    @Test
    fun stickyModifiersAroundAClick() {
        val (before, after) = KeyCompose.holdAround(listOf("command", "shift", "win"))
        assertEquals(listOf(55, 56), before.map { data(it).get("keycode").asInt })
        assertTrue(before.all { data(it).get("isDown").asBoolean })
        assertEquals(listOf(56, 55), after.map { data(it).get("keycode").asInt })
        assertTrue(after.none { data(it).get("isDown").asBoolean })
        assertEquals(MacKey.OPTION, KeyCompose.holdKeycode("alt"))
        assertEquals(MacKey.CONTROL, KeyCompose.holdKeycode("ctrl"))
    }

    // MARK: - shortcut row

    @Test
    fun shortcutRowIsPlatformAware() {
        val mac = Shortcuts.row(isWindows = false).associateBy { it.id }
        val win = Shortcuts.row(isWindows = true).associateBy { it.id }
        assertEquals(mac.keys, win.keys)
        listOf("switch", "copy", "paste", "undo", "save", "find", "close", "quit", "search", "overview", "esc",
            "left", "up", "down", "right").forEach { assertTrue(it, it in mac) }
        assertTrue(mac.getValue("switch").switcher)
        assertEquals("⌘Tab", mac.getValue("switch").keys)
        assertEquals("Alt+Tab", win.getValue("switch").keys)
        assertEquals("⌘C", mac.getValue("copy").keys)
        assertEquals("Ctrl+C", win.getValue("copy").keys)
        assertEquals("Mission Control", mac.getValue("overview").title)
        assertEquals("Task view", win.getValue("overview").title)
        assertEquals("⌘Q", mac.getValue("quit").keys)
        assertEquals("Alt+F4", win.getValue("quit").keys)
    }

    @Test
    fun shortcutMessages() {
        fun msg(isWindows: Boolean, id: String) =
            data(Shortcuts.messages(Shortcuts.row(isWindows).first { it.id == id }.combo!!).single())
        fun mods(o: com.google.gson.JsonObject) = o.getAsJsonArray("modifiers").map { it.asString }
        val copy = msg(false, "copy")
        assertEquals("keypress", copy.get("action").asString)
        assertEquals(8, copy.get("keycode").asInt)
        assertEquals(listOf("command"), mods(copy))
        assertEquals(listOf("ctrl"), mods(msg(true, "copy")))
        assertEquals(1, msg(false, "save").get("keycode").asInt)
        assertEquals(3, msg(true, "find").get("keycode").asInt)
        assertEquals(12, msg(false, "quit").get("keycode").asInt)
        assertEquals(118, msg(true, "quit").get("keycode").asInt)
        assertEquals(listOf("alt"), mods(msg(true, "quit")))
        assertEquals(49, msg(false, "search").get("keycode").asInt)
        assertEquals(listOf("command"), mods(msg(false, "search")))
        assertEquals(listOf("win"), mods(msg(true, "search")))
        assertEquals(126, msg(false, "overview").get("keycode").asInt)
        assertEquals(listOf("ctrl"), mods(msg(false, "overview")))
        assertEquals(48, msg(true, "overview").get("keycode").asInt)
        assertEquals(listOf("win"), mods(msg(true, "overview")))
        assertEquals(53, msg(false, "esc").get("keycode").asInt)
        assertTrue(mods(msg(true, "left")).isEmpty())
    }

    @Test
    fun appSwitcherHoldsTheModifier() {
        fun keys(list: List<String>) = list.map { data(it) }.map { it.get("keycode").asInt to it.get("isDown").asBoolean }
        val mac = AppSwitcher(isWindows = false)
        assertEquals(listOf(55 to true, 48 to true, 48 to false), keys(mac.step(true)))
        assertTrue(mac.open)
        assertEquals(listOf(48 to true, 48 to false), keys(mac.step(true)))
        assertEquals(listOf(56 to true, 48 to true, 48 to false, 56 to false), keys(mac.step(false)))
        assertEquals(listOf(123 to true, 123 to false), keys(mac.tap(MacKey.LEFT)))
        assertEquals(listOf(55 to false), keys(mac.end()))
        assertFalse(mac.open)
        assertTrue(mac.end().isEmpty())
        val win = AppSwitcher(isWindows = true)
        assertEquals(listOf(58 to true, 48 to true, 48 to false), keys(win.step(true)))
        assertEquals(listOf(58 to false), keys(win.end()))
        assertTrue(win.tap(MacKey.TAB).isEmpty())  // nothing held: no stray Tab
    }

    @Test
    fun customCombosLabelAndRoundTrip() {
        val c = CustomCombo(listOf("shift", "command"), 1, "S")
        assertEquals("⇧⌘S", c.label(isWindows = false))
        assertEquals("Ctrl+Alt+Del", CustomCombo(listOf("alt", "ctrl"), 117, "Del").label(isWindows = true))
        val list = listOf(c, CustomCombo(listOf("win"), 37, "L"))
        assertEquals(list, CustomCombo.decode(CustomCombo.encode(list)))
        assertTrue(CustomCombo.decode(null).isEmpty())
        assertTrue(CustomCombo.decode("not json").isEmpty())
        assertEquals(Combo(1, listOf("shift", "command")), c.combo)
    }

    @Test
    fun modifierKeysPerPlatform() {
        assertEquals(listOf("command", "option", "shift", "ctrl"), ModKey.entries.map { it.wire(false) })
        assertEquals(listOf("ctrl", "alt", "shift", "win"), ModKey.entries.map { it.wire(true) })
        assertEquals(listOf("⌘", "⌥", "⇧", "⌃"), ModKey.entries.map { it.label(false) })
        assertEquals(listOf("Ctrl", "Alt", "Shift", "Win"), ModKey.entries.map { it.label(true) })
    }

    // MARK: - unlock texts

    @Test
    fun unlockErrorTexts() {
        val m = { code: String -> UnlockFlowState.message(code, "Mac") }
        assertEquals(
            "H.I.B Input on Mac needs Accessibility again. On the Mac: Privacy & Security → Accessibility → " +
                "select H.I.B Input → remove (−) → allow it again.", m("permission")
        )
        assertEquals("Couldn't unlock Mac. Try again, or unlock it at the desk.", m("failed"))
        assertEquals("Mac's password changed — set up Unlock from phone again there.", m("wrongPassword"))
        assertEquals("Couldn't unlock Mac. Try again, or unlock it at the desk.", m("somethingNew"))
    }
}
