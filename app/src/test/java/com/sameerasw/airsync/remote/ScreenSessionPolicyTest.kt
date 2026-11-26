package com.sameerasw.airsync.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone Remote (SPACE.md §15.3): the `/screen` session lives while the Remote page wants the picture (page visible,
 * app resumed, computer picked, picture on), not while a particular surface exists. Surface churn (Touchpad ↔
 * Direct, picture layout changes, the keyboard reshaping the page, the locked card) only re-binds the decoder and
 * asks for a keyframe.
 */
class ScreenSessionPolicyTest {

    private class Surf(val id: Int) { override fun toString() = "surface$id" }

    private class FakeIo : ScreenSessionPolicy.Io<Surf> {
        var opens = 0
        /** Closes of a session that was open (what the computer logs as "phone closed it"). */
        var closes = 0
        var keyframes = 0
        var displaySwitches = mutableListOf<String>()
        var bound: Surf? = null
        var openNow = false
        var openSurface: Surf? = null
        var refuse = false

        override fun open(want: RemoteStream.Want, surface: Surf?): Boolean {
            if (refuse) return false
            opens++
            openNow = true
            openSurface = surface
            bound = surface
            return true
        }
        override fun close() {
            if (openNow) closes++
            openNow = false
            bound = null
        }
        override fun bind(surface: Surf?) { bound = surface }
        override fun keyframe() { keyframes++ }
        override fun switchDisplay(display: String) { displaySwitches += display }
    }

    private val mac = RemoteStream.Want("Mac", "", 2400, 1080, 60)

    @Test
    fun surfaceChurnNeverReopensTheSocket() {
        val io = FakeIo()
        val p = ScreenSessionPolicy(io)
        var s = Surf(0)
        p.attach(s)
        p.setWant(mac, up = false)
        assertEquals(1, io.opens)
        // The user flips Touchpad/Direct, taps the picture, the keyboard opens, the locked card comes and goes:
        // each time the TextureView is destroyed and a new one appears.
        repeat(25) { i ->
            p.detach(s)
            assertNull(io.bound)
            s = Surf(i + 1)
            p.attach(s)
            assertSame(s, io.bound)
        }
        assertEquals("one session for the whole visit", 1, io.opens)
        assertEquals("the phone never closed it", 0, io.closes)
        assertTrue(io.openNow)
        assertEquals("each new surface asks for a keyframe", 25, io.keyframes)
    }

    @Test
    fun newSurfaceBeforeOldOneIsDestroyedKeepsTheNewOne() {
        val io = FakeIo()
        val p = ScreenSessionPolicy(io)
        val old = Surf(1)
        val new = Surf(2)
        p.attach(old)
        p.setWant(mac, up = false)
        p.attach(new)
        p.detach(old) // late callback from the view that left
        assertSame(new, p.surface)
        assertSame(new, io.bound)
        assertEquals(1, io.opens)
        assertEquals(0, io.closes)
    }

    @Test
    fun opensWithoutASurfaceAndBindsWhenOneAppears() {
        val io = FakeIo()
        val p = ScreenSessionPolicy(io)
        p.setWant(mac, up = false)
        assertEquals("the page wants the picture: dial now", 1, io.opens)
        assertNull(io.openSurface)
        val s = Surf(1)
        p.attach(s)
        assertSame(s, io.bound)
        assertEquals(1, io.opens)
        assertEquals(1, io.keyframes)
    }

    @Test
    fun lifetimeFollowsWhatThePageWants() {
        val io = FakeIo()
        val p = ScreenSessionPolicy(io)
        p.attach(Surf(1))
        p.setWant(mac, up = false)
        p.setWant(null, up = true) // page left, app paused, picture off or no computer
        assertEquals(1, io.closes)
        assertFalse(p.isOpen)
        p.setWant(mac, up = false)
        assertEquals(2, io.opens)
        p.setWant(mac.copy(computer = "PC"), up = true)
        assertEquals("another computer is another session", 3, io.opens)
        assertEquals(2, io.closes)
    }

    @Test
    fun displayChangeSwitchesInPlaceWhileLiveOrPaused() {
        val io = FakeIo()
        val p = ScreenSessionPolicy(io)
        p.setWant(mac, up = false)
        p.setWant(mac.copy(display = "2"), up = true)
        assertEquals(listOf("2"), io.displaySwitches)
        assertEquals(1, io.opens)
        assertEquals(0, io.closes)
    }

    @Test
    fun droppedSessionRetriesOnlyWhileWanted() {
        val io = FakeIo()
        val p = ScreenSessionPolicy(io)
        p.setWant(mac, up = false)
        p.ended()
        assertFalse(p.isOpen)
        p.reopen()
        assertEquals(2, io.opens)
        p.setWant(null, up = false)
        p.reopen()
        assertEquals(2, io.opens)
    }

    @Test
    fun refusedOpenStaysClosed() {
        val io = FakeIo().apply { refuse = true }
        val p = ScreenSessionPolicy(io)
        p.setWant(mac, up = false)
        assertFalse(p.isOpen)
        p.attach(Surf(1))
        assertEquals(0, io.keyframes)
    }
}
