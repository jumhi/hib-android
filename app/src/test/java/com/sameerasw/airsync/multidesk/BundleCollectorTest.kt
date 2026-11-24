package com.sameerasw.airsync.multidesk

import com.sameerasw.airsync.utils.FlickBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bundles a computer flicks to the phone arrive as ONE notification (SPACE.md §14). */
class BundleCollectorTest {

    private var clock = 1_000_000L
    private val c = BundleCollector { clock }

    private fun start(bundle: String, index: Int, count: Int, kind: String = "image", size: Long = 100L, from: String = "Office PC") {
        c.onStart(FlickBundle(bundle, index, count), from, "$bundle-$index", kind, "f$index", "$kind/x", size)
    }

    @Test
    fun fivePhotosBecomeOneNotificationWhenTheLastArrives() {
        // flickStarts can come in any order; the bundle keeps its own order.
        listOf(3, 0, 4, 1, 2).forEach { start("b", it, 5) }
        for (i in 0 until 4) {
            val s = c.onFinished("b-$i", true, "content://x/$i")!!
            assertFalse(s.complete)
            assertEquals("${i + 1} of 5", s.detail)
        }
        val done = c.onFinished("b-4", true, "content://x/4")!!
        assertTrue(done.complete)
        assertEquals("5 photos from Office PC", done.title)
        assertEquals("Tap to see them all", done.detail)
        assertEquals((0 until 5).toList(), done.items.map { it.index })
        assertEquals((0 until 5).map { "content://x/$it" }, done.arrived.map { it.uri })
        // Shown once.
        assertTrue(c.claim("b"))
        assertFalse(c.claim("b"))
        assertNull(c.bundleOf("b-0"))
    }

    @Test
    fun aFailedFileStillLetsTheBundleFinish() {
        start("b", 0, 3)
        start("b", 1, 3)
        start("b", 2, 3)
        c.onFinished("b-0", true, "u0")
        c.onFinished("b-1", false, "ignored")
        val s = c.onFinished("b-2", true, "u2")!!
        assertTrue(s.complete)
        assertEquals(1, s.failed)
        assertEquals(listOf("u0", "u2"), s.arrived.map { it.uri })
        assertNull(s.items[1].uri)
        assertEquals("2 photos from Office PC", s.title)
        assertTrue(s.detail.startsWith("1 couldn't be received"))
    }

    @Test
    fun textAndLinksInABundleDontHoldItUp() {
        c.onNonFile(FlickBundle("m", 1, 3), "Studio")
        start("m", 0, 3, kind = "video", from = "Studio")
        start("m", 2, 3, kind = "image", from = "Studio")
        assertEquals(2, c.snapshot("m")!!.expected)
        c.onFinished("m-0", true, "v")
        val s = c.onFinished("m-2", true, "i")!!
        assertTrue(s.complete)
        assertEquals("2 photos and videos from Studio", s.title)
    }

    @Test
    fun waitsForEveryItemsStartBeforeFinishing() {
        // Only 2 of 3 flickStarts seen so far: two finished files aren't the whole bundle.
        start("w", 0, 3)
        start("w", 1, 3)
        c.onFinished("w-0", true, "a")
        assertFalse(c.onFinished("w-1", true, "b")!!.complete)
        start("w", 2, 3)
        assertTrue(c.onFinished("w-2", true, "c")!!.complete)
    }

    @Test
    fun progressIsForTheWholeBundle() {
        start("p", 0, 2, size = 1000)
        start("p", 1, 2, size = 3000)
        val s = c.onProgress("p-1", 1500)!!
        assertEquals(4000L, s.totalBytes)
        assertEquals(1500L, s.receivedBytes)
        assertEquals(0.375f, s.fraction, 0.0001f)
        // Over-reporting never passes the file's size.
        assertEquals(1.0f, c.onProgress("p-0", 99_999)!!.let { (it.receivedBytes - 1500).toFloat() / 1000f }, 0.0001f)
        assertEquals("Receiving progress title", "2 photos from Office PC", s.title)
    }

    @Test
    fun singleFlicksAndStrangersAreNotBundles() {
        assertNull(c.bundleOf("lonely"))
        assertNull(c.bundleOf(null))
        assertNull(c.onFinished("lonely", true, "u"))
        assertNull(c.onProgress("lonely", 10))
        assertFalse(c.claim("nope"))
    }

    @Test
    fun twoBundlesFromTwoComputersStaySeparate() {
        start("x", 0, 2, from = "Office PC")
        start("x", 1, 2, from = "Office PC")
        start("y", 0, 2, from = "MacBook")
        start("y", 1, 2, from = "MacBook")
        c.onFinished("x-0", true, "a")
        c.onFinished("y-0", true, "b")
        val y = c.onFinished("y-1", true, "c")!!
        assertTrue(y.complete)
        assertEquals("2 photos from MacBook", y.title)
        assertFalse(c.snapshot("x")!!.complete)
    }

    @Test
    fun aComputerThatLeavesFinishesItsBundlesWithWhatArrived() {
        start("g", 0, 3, from = "Office PC")
        start("g", 1, 3, from = "Office PC")
        start("g", 2, 3, from = "Office PC")
        start("h", 0, 2, from = "MacBook")
        c.onFinished("g-0", true, "a")
        val gone = c.abandon("office pc")
        assertEquals(1, gone.size)
        val s = gone.single()
        assertTrue(s.complete)
        assertEquals(1, s.arrived.size)
        assertEquals(2, s.failed)
        assertEquals("1 photo from Office PC", s.title)
        assertNotNull(c.snapshot("h"))
    }

    @Test
    fun oldBundlesAreForgotten() {
        start("old", 0, 2)
        clock += BundleCollector.KEEP_MS + 1
        start("new", 0, 2)
        assertNull(c.snapshot("old"))
        assertNull(c.bundleOf("old-0"))
        assertNotNull(c.snapshot("new"))
    }
}
