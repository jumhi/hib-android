package com.sameerasw.airsync.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone Remote (design §5): the gesture table, the remoteControl mapping and the zoomed view's math. */
class RemoteControlTest {

    private fun p(id: Long, x: Float, y: Float, down: Boolean = true) = TouchPoint(id, x, y, down)

    private fun RemoteGestures.run(vararg events: Pair<Long, List<TouchPoint>>): List<RemoteAction> =
        events.flatMap { (t, pts) -> onEvent(t, pts) }

    private fun moves(a: List<RemoteAction>) = a.filterIsInstance<RemoteAction.Move>()

    // MARK: - gestures

    @Test
    fun oneFingerSlideMovesTheCursor() {
        val g = RemoteGestures(speed = 1.8f)
        val a = g.run(
            0L to listOf(p(1, 100f, 100f)), 16L to listOf(p(1, 103f, 104f)), 32L to listOf(p(1, 106f, 108f)),
            48L to listOf(p(1, 109f, 112f)), 64L to listOf(p(1, 109f, 112f, false))
        )
        val m = moves(a)
        assertEquals(3, m.size)
        // Small moves get exactly the old ×1.8 feel.
        assertEquals(3f * 1.8f, m[0].dx, 1e-4f)
        assertEquals(4f * 1.8f, m[0].dy, 1e-4f)
        assertTrue(a.none { it is RemoteAction.Click })
    }

    @Test
    fun fastSlidesAccelerateGently() {
        val g = RemoteGestures(speed = 1f)
        val a = g.run(0L to listOf(p(1, 0f, 0f)), 16L to listOf(p(1, 46f, 0f)))
        val m = moves(a).single()
        assertTrue(m.dx > 46f && m.dx <= 46f * 1.6f + 1e-3f)
    }

    @Test
    fun tapIsALeftClick() {
        val g = RemoteGestures()
        val a = g.run(0L to listOf(p(1, 50f, 50f)), 80L to listOf(p(1, 52f, 51f)), 120L to listOf(p(1, 52f, 51f, false)))
        assertEquals(RemoteAction.Click("left"), a.last())
    }

    @Test
    fun twoFingerTapIsARightClick() {
        val g = RemoteGestures()
        val a = g.run(
            0L to listOf(p(1, 50f, 50f)),
            10L to listOf(p(1, 50f, 50f), p(2, 120f, 50f)),
            90L to listOf(p(1, 51f, 50f), p(2, 121f, 51f)),
            130L to listOf(p(1, 51f, 50f, false), p(2, 121f, 51f, false))
        )
        assertEquals(listOf<RemoteAction>(RemoteAction.Click("right")), a.filter { it is RemoteAction.Click })
        assertTrue(moves(a).isEmpty())
    }

    @Test
    fun twoFingerSlideScrollsWithNaturalSetting() {
        fun scroll(natural: Boolean): List<RemoteAction.Scroll> {
            val g = RemoteGestures(naturalScroll = natural)
            return g.run(
                0L to listOf(p(1, 50f, 100f), p(2, 120f, 100f)),
                16L to listOf(p(1, 50f, 80f), p(2, 120f, 80f)),
                32L to listOf(p(1, 50f, 60f), p(2, 120f, 60f)),
                48L to listOf(p(1, 50f, 60f, false), p(2, 120f, 60f, false))
            ).filterIsInstance<RemoteAction.Scroll>()
        }
        val natural = scroll(true)
        assertTrue(natural.isNotEmpty())
        assertTrue(natural.all { it.dy < 0f && it.dx == 0f })
        val classic = scroll(false)
        assertTrue(classic.all { it.dy > 0f })
        assertEquals(natural.sumOf { it.dy.toDouble() }, -classic.sumOf { it.dy.toDouble() }, 1e-4)
    }

    @Test
    fun scrollingIsNotARightClick() {
        val g = RemoteGestures()
        val a = g.run(
            0L to listOf(p(1, 50f, 100f), p(2, 120f, 100f)),
            16L to listOf(p(1, 50f, 70f), p(2, 120f, 70f)),
            40L to listOf(p(1, 50f, 70f, false), p(2, 120f, 70f, false))
        )
        assertTrue(a.none { it is RemoteAction.Click })
    }

    @Test
    fun doubleTapHoldAndSlideDrags() {
        val g = RemoteGestures(speed = 1f)
        val a = g.run(
            0L to listOf(p(1, 100f, 100f)),
            60L to listOf(p(1, 100f, 100f, false)),               // tap → click
            200L to listOf(p(1, 102f, 101f)),                      // second down within 300 ms, nearby
            260L to listOf(p(1, 108f, 101f)),                      // still within the slop: nothing yet
            300L to listOf(p(1, 130f, 101f)),                      // slides: button down, then the move
            340L to listOf(p(1, 150f, 101f)),
            400L to listOf(p(1, 150f, 101f, false))                // lift: button up
        )
        assertEquals(RemoteAction.Click("left"), a[0])
        val drag = a.drop(1)
        assertEquals(RemoteAction.Button("left", true), drag.first())
        assertEquals(RemoteAction.Button("left", false), drag.last())
        val dx = moves(drag).sumOf { it.dx.toDouble() }
        assertTrue(dx > 40.0)
        assertFalse(g.isDragging)
    }

    @Test
    fun doubleTapWithoutSlideIsADoubleClick() {
        val g = RemoteGestures()
        val a = g.run(
            0L to listOf(p(1, 100f, 100f)), 50L to listOf(p(1, 100f, 100f, false)),
            150L to listOf(p(1, 101f, 100f)), 200L to listOf(p(1, 101f, 100f, false))
        )
        assertEquals(listOf<RemoteAction>(RemoteAction.Click("left"), RemoteAction.Click("left")), a)
    }

    @Test
    fun aSlowSecondTapIsJustAMove() {
        val g = RemoteGestures()
        val a = g.run(
            0L to listOf(p(1, 100f, 100f)), 50L to listOf(p(1, 100f, 100f, false)),
            600L to listOf(p(1, 100f, 100f)), 650L to listOf(p(1, 140f, 100f)), 700L to listOf(p(1, 140f, 100f, false))
        )
        assertTrue(a.none { it is RemoteAction.Button })
        assertEquals(1, moves(a).size)
    }

    @Test
    fun pinchZoomsAndNeverSendsAnything() {
        val g = RemoteGestures()
        val a = g.run(
            0L to listOf(p(1, 100f, 200f), p(2, 200f, 200f)),
            16L to listOf(p(1, 80f, 200f), p(2, 220f, 200f)),
            32L to listOf(p(1, 60f, 200f), p(2, 240f, 200f)),
            48L to listOf(p(1, 60f, 200f, false), p(2, 240f, 200f, false))
        )
        val zooms = a.filterIsInstance<RemoteAction.Zoom>()
        assertTrue(zooms.isNotEmpty())
        val total = zooms.fold(1f) { acc, z -> acc * z.factor }
        assertEquals(180f / 100f, total, 1e-3f)
        assertEquals(150f, zooms.last().cx, 1e-3f)
        assertTrue(a.none { it is RemoteAction.Scroll || it is RemoteAction.Click || it is RemoteAction.Move })
        // And the mapping to messages sends nothing for it.
        assertTrue(a.flatMap { RemoteInput.messages(it) }.isEmpty())
    }

    @Test
    fun threeFingersPanTheView() {
        val g = RemoteGestures()
        val a = g.run(
            0L to listOf(p(1, 0f, 0f), p(2, 50f, 0f), p(3, 100f, 0f)),
            16L to listOf(p(1, 10f, 5f), p(2, 60f, 5f), p(3, 110f, 5f)),
            32L to listOf(p(1, 10f, 5f, false), p(2, 60f, 5f, false), p(3, 110f, 5f, false))
        )
        assertEquals(listOf<RemoteAction>(RemoteAction.Pan(10f, 5f)), a)
        assertTrue(RemoteInput.messages(a[0]).isEmpty())
    }

    @Test
    fun cancelReleasesAHeldButton() {
        val g = RemoteGestures()
        g.run(0L to listOf(p(1, 0f, 0f)), 40L to listOf(p(1, 0f, 0f, false)), 100L to listOf(p(1, 0f, 0f)), 150L to listOf(p(1, 40f, 0f)))
        assertTrue(g.isDragging)
        assertEquals(listOf<RemoteAction>(RemoteAction.Button("left", false)), g.cancel())
        assertFalse(g.isDragging)
    }

    // MARK: - remoteControl mapping (SPACE.md §15.2)

    private fun data(json: String) = JsonParser.parseString(json).asJsonObject.also {
        assertEquals("remoteControl", it.get("type").asString)
    }.getAsJsonObject("data")

    @Test
    fun mapsActionsToRemoteControl() {
        val move = data(RemoteInput.messages(RemoteAction.Move(3f, -2f)).single())
        assertEquals("mouse_move", move.get("action").asString)
        assertEquals(3.0, move.get("dx").asDouble, 0.0); assertEquals(-2.0, move.get("dy").asDouble, 0.0)

        val scroll = data(RemoteInput.messages(RemoteAction.Scroll(0f, 8f)).single())
        assertEquals("mouse_scroll", scroll.get("action").asString)
        assertEquals(8.0, scroll.get("dy").asDouble, 0.0)

        val click = RemoteInput.messages(RemoteAction.Click("right")).map { data(it) }
        assertEquals(2, click.size)
        assertEquals("mouse_click", click[0].get("action").asString)
        assertEquals("right", click[0].get("button").asString)
        assertTrue(click[0].get("isDown").asBoolean); assertFalse(click[1].get("isDown").asBoolean)

        val down = data(RemoteInput.messages(RemoteAction.Button("left", true)).single())
        assertEquals("left", down.get("button").asString); assertTrue(down.get("isDown").asBoolean)

        val key = data(RemoteInput.key(48, listOf("command")))
        assertEquals("keypress", key.get("action").asString)
        assertEquals(48, key.get("keycode").asInt)
        assertEquals("command", key.getAsJsonArray("modifiers")[0].asString)

        val type = data(RemoteInput.type("héllo"))
        assertEquals("type", type.get("action").asString)
        assertEquals("héllo", type.get("text").asString)
    }

    // MARK: - zoomed view

    @Test
    fun zoomIsClampedFromFitToSix() {
        var v = RemoteViewState()
        assertEquals(ViewRect.FULL, v.view)
        v = v.zoomBy(100f, 0.5f, 0.5f)
        assertEquals(6f, v.zoom, 0f)
        assertEquals(1f / 6f, v.view.w, 1e-6f)
        v = v.zoomBy(0.0001f, 0.5f, 0.5f)
        assertEquals(1f, v.zoom, 0f)
        assertEquals(ViewRect.FULL, v.view)
        assertEquals(v, v.zoomBy(Float.NaN, 0.5f, 0.5f))
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val v = RemoteViewState().zoomBy(2f, 0.25f, 0.25f)
        // The display point at 0.25 stays at 0.25 of the picture: x + 0.25 * w == 0.25.
        assertEquals(0.25f, v.x + 0.25f * v.view.w, 1e-5f)
        assertEquals(0.25f, v.y + 0.25f * v.view.h, 1e-5f)
        // The view never leaves the display.
        val edge = RemoteViewState().zoomBy(4f, 1f, 1f)
        assertEquals(0.75f, edge.x, 1e-5f)
        assertEquals(1f, edge.x + edge.view.w, 1e-5f)
    }

    @Test
    fun followKeepsTheCursorInsideTheMargin() {
        val v = RemoteViewState().zoomBy(4f, 0f, 0f) // view 0…0.25
        assertEquals(0f, v.x, 0f)
        // Cursor at 95 % of the view (past the 20 % margin on the right).
        val f = v.followCursor(0.95f, 0.5f)
        val w = f.view.w
        val cursorDisplayX = v.x + 0.95f * v.view.w
        val inView = (cursorDisplayX - f.x) / w
        assertTrue("cursor at $inView of the view", inView in 0.2f..0.8f + 1e-4f)
        assertEquals(0.8f, inView, 1e-4f)
        // Inside the margin: nothing moves.
        assertEquals(f, f.followCursor(0.5f, 0.5f))
        // At 1× nothing follows.
        assertEquals(RemoteViewState(), RemoteViewState().followCursor(0.99f, 0.99f))
    }

    @Test
    fun followClampsAtTheDisplayEdge() {
        val v = RemoteViewState().zoomBy(2f, 1f, 1f) // view 0.5…1
        val f = v.followCursor(1.4f, 1.4f)           // cursor off the view, past the display edge
        assertEquals(0.5f, f.x, 1e-5f)
        assertEquals(0.5f, f.y, 1e-5f)
    }

    @Test
    fun panStopsFollowingUntilPinchOut() {
        val v = RemoteViewState().zoomBy(3f, 0.5f, 0.5f)
        val panned = v.panBy(0.3f, 0f)
        assertFalse(panned.follow)
        assertTrue(panned.x < v.x)
        assertEquals(panned, panned.followCursor(0.99f, 0.5f))
        // Zooming in keeps it off; pinching out to 1× turns it back on.
        assertFalse(panned.zoomBy(1.2f, 0.5f, 0.5f).follow)
        assertTrue(panned.zoomBy(0.01f, 0.5f, 0.5f).follow)
        // Panning at 1× does nothing.
        assertEquals(RemoteViewState(), RemoteViewState().panBy(0.5f, 0.5f))
    }

    @Test
    fun smallViewChangesAreNotWorthAMessage() {
        assertFalse(RemoteViewState.differs(ViewRect(0.1f, 0.1f, 0.5f, 0.5f), ViewRect(0.1005f, 0.1f, 0.5f, 0.5f)))
        assertTrue(RemoteViewState.differs(ViewRect(0.1f, 0.1f, 0.5f, 0.5f), ViewRect(0.11f, 0.1f, 0.5f, 0.5f)))
    }
}
