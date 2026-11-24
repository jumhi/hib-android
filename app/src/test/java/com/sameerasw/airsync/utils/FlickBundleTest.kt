package com.sameerasw.airsync.utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sameerasw.airsync.presentation.ui.activities.FanPage
import com.sameerasw.airsync.widget.FlickWidgetGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** Flick bundles (SPACE.md §14): the wire format, the fan-out patterns, the throw's arc, progress, recent media. */
class FlickBundleTest {

    private fun data(json: String): JsonObject {
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("flickStart", root.get("type").asString)
        return root.getAsJsonObject("data")
    }

    private fun start(id: String, bundle: FlickBundle?, screen: String? = "s2", aim: Pair<Float, Float>? = null) =
        FlickWire.flickStart(
            id = id, kind = "image", name = "$id.jpg", mime = "image/jpeg", size = 1234L,
            exitXRatio = 0.25f, velocity = 2600f, angle = -12f, thumbnailBase64 = "AAAA",
            screen = screen, aim = aim, bundle = bundle
        )

    // MARK: - wire

    @Test
    fun bundleTagIsOnEveryItemWithTheSameIdAndCount() {
        val tags = FlickBundle.tags("b-1", 5)
        assertEquals(5, tags.size)
        tags.forEachIndexed { i, t ->
            val d = data(start("f$i", t)).getAsJsonObject("bundle")
            assertEquals(setOf("id", "index", "count"), d.keySet())
            assertEquals("b-1", d.get("id").asString)
            assertEquals(i, d.get("index").asInt)
            assertEquals(5, d.get("count").asInt)
        }
    }

    @Test
    fun aBundleSharesScreenExitAndVelocity() {
        val all = FlickBundle.tags("b", 3).mapIndexed { i, t -> data(start("f$i", t)) }
        for (key in listOf("screen", "exitXRatio", "velocity", "angle")) {
            assertEquals(1, all.map { it.get(key).toString() }.toSet().size)
        }
        assertEquals(listOf("f0", "f1", "f2"), all.map { it.get("id").asString })
        assertEquals("s2", all[0].get("screen").asString)
        assertEquals(2600.0, all[0].get("velocity").asDouble, 0.001)
    }

    @Test
    fun singleFlickHasNoBundleAndOptionalFieldsStayOut() {
        assertEquals(listOf<FlickBundle?>(null), FlickBundle.tags("b", 1))
        val d = data(start("one", null, screen = null))
        assertFalse(d.has("bundle"))
        assertFalse(d.has("screen"))
        assertFalse(d.has("heading"))
        assertEquals("one", d.get("id").asString)
        assertEquals(1234L, d.get("size").asLong)
        assertEquals("AAAA", d.get("thumbnail").asString)
    }

    @Test
    fun aimAndTextGoOnTheWire() {
        val d = data(
            FlickWire.flickStart(
                "t", "text", "Text", "text/plain", 3, 0.5f, 1200f, 0f,
                text = "x".repeat(FlickWire.MAX_TEXT_CHARS + 50), aim = 91.5f to -4f
            )
        )
        assertEquals(FlickWire.MAX_TEXT_CHARS, d.get("text").asString.length)
        assertEquals(91.5, d.get("heading").asDouble, 0.001)
        assertEquals(-4.0, d.get("elevation").asDouble, 0.001)
        assertFalse(d.has("thumbnail"))
    }

    @Test
    fun bundleTagsFromTheWireAreChecked() {
        assertEquals(FlickBundle("b", 2, 5), FlickBundle.from("b", 2, 5))
        assertNull(FlickBundle.from(null, 0, 2))
        assertNull(FlickBundle.from("  ", 0, 2))
        assertNull(FlickBundle.from("b", 5, 5))
        assertNull(FlickBundle.from("b", -1, 5))
        assertNull(FlickBundle.from("b", 0, 0))
    }

    @Test
    fun bundleWords() {
        assertEquals("5 photos", BundleWords.noun(List(5) { "image" }))
        assertEquals("1 photo", BundleWords.noun(listOf("image")))
        assertEquals("2 videos", BundleWords.noun(listOf("video", "video")))
        assertEquals("3 photos and videos", BundleWords.noun(listOf("image", "video", "image")))
        assertEquals("4 items", BundleWords.noun(listOf("image", "file", "link", "text")))
        assertEquals("5 photos", BundleWords.noun(listOf("image", "image"), count = 5))
    }

    // MARK: - fan-out patterns (the same on every device)

    @Test
    fun fanRowsMatchTheSpec() {
        assertEquals(listOf(1), FlickBundleLayout.rows(1))
        assertEquals(listOf(2), FlickBundleLayout.rows(2))
        assertEquals(listOf(2, 1), FlickBundleLayout.rows(3))
        assertEquals(listOf(2, 2), FlickBundleLayout.rows(4))
        assertEquals(listOf(2, 1, 2), FlickBundleLayout.rows(5))
        assertEquals(listOf(3, 3), FlickBundleLayout.rows(6))
        assertEquals(listOf(2, 3, 2), FlickBundleLayout.rows(7))
        assertEquals(listOf(3, 2, 3), FlickBundleLayout.rows(8))
        assertEquals(listOf(3, 3, 3), FlickBundleLayout.rows(9))
        assertEquals(listOf(3, 3, 3), FlickBundleLayout.rows(14))
        for (n in 1..9) assertEquals(n, FlickBundleLayout.rows(n).sum())
        assertEquals(0, FlickBundleLayout.overflow(9))
        assertEquals(2, FlickBundleLayout.overflow(10))
        assertEquals(12, FlickBundleLayout.overflow(20))
        assertEquals(9, FlickBundleLayout.slotCount(20))
    }

    @Test
    fun fiveIsDiceFive() {
        val w = 100f
        val h = 130f
        val g = 10f
        val p = FlickBundleLayout.fan(5, w, h, g)
        assertEquals(5, p.size)
        // Two on top, one in the middle, two at the bottom.
        assertEquals(listOf(-140f, -140f, 0f, 140f, 140f), p.map { it.y })
        assertEquals(listOf(-55f, 55f, 0f, -55f, 55f), p.map { it.x })
    }

    @Test
    fun threeIsTwoOverOne() {
        val p = FlickBundleLayout.fan(3, 100f, 100f, 0f)
        assertEquals(listOf(Pt(-50f, -50f), Pt(50f, -50f), Pt(0f, 50f)), p)
    }

    @Test
    fun everyFanIsCentredAndCardsNeverOverlap() {
        for (n in 1..9) {
            val p = FlickBundleLayout.fan(n, 80f, 104f, 12f)
            val rows = FlickBundleLayout.rows(n)
            // Centred on the landing point vertically, and each row centred horizontally.
            assertTrue("n=$n", abs(p.map { it.y }.distinct().sum()) < 0.01f)
            var k = 0
            rows.forEach { count ->
                val row = p.subList(k, k + count)
                assertTrue("n=$n", abs(row.sumOf { it.x.toDouble() }) < 0.01)
                k += count
            }
            for (i in p.indices) for (j in i + 1 until p.size) {
                val dx = abs(p[i].x - p[j].x)
                val dy = abs(p[i].y - p[j].y)
                assertTrue("n=$n $i/$j overlap", dx >= 80f + 12f - 0.01f || dy >= 104f + 12f - 0.01f)
            }
        }
    }

    @Test
    fun fitCellRespectsWidthHeightAndMax() {
        // 3 across in 300 px with 10 px gaps → 93.33; height is plenty.
        assertEquals(93.33f, FlickBundleLayout.fitCell(9, 300f, 2000f, 10f, 1.3f, 500f), 0.01f)
        // 3 rows in 300 px tall → (300-20)/3/1.3 = 71.79.
        assertEquals(71.79f, FlickBundleLayout.fitCell(9, 2000f, 300f, 10f, 1.3f, 500f), 0.01f)
        assertEquals(120f, FlickBundleLayout.fitCell(2, 2000f, 2000f, 10f, 1.3f, 120f), 0.01f)
        assertEquals(0f, FlickBundleLayout.fitCell(0, 100f, 100f, 10f, 1.3f, 100f), 0.0f)
    }

    @Test
    fun stackPeeksAtAlternatingAngles() {
        val top = FlickBundleLayout.stackPose(0)
        assertEquals(0f, top.rotation, 0f)
        assertEquals(1f, top.scale, 0f)
        val r1 = FlickBundleLayout.stackPose(1).rotation
        val r2 = FlickBundleLayout.stackPose(2).rotation
        assertTrue(r1 < 0f && r2 > 0f)
        assertTrue(FlickBundleLayout.stackPose(3).scale < FlickBundleLayout.stackPose(1).scale)
    }

    @Test
    fun bigBundlesPageThroughEightAndPlus() {
        assertEquals(1, FanPage.pages(9))
        assertEquals(0, FanPage.plus(9, 0))
        assertEquals(9, FanPage.slots(9, 0))
        // 20 items: pages of 8, 8, 4; each with a "+N" card for the rest.
        assertEquals(3, FanPage.pages(20))
        assertEquals(9, FanPage.slots(20, 0))
        assertEquals(12, FanPage.plus(20, 0))
        assertEquals(5, FanPage.slots(20, 2))
        assertEquals(16, FanPage.plus(20, 2))
        val items = (0 until 20).toList()
        assertEquals((16 until 20).toList(), FanPage.items(items, 2))
        assertEquals(items, FanPage.items(items.take(9), 0) + (9 until 20))
    }

    // MARK: - the throw

    @Test
    fun cardsLeaveOneAfterAnother() {
        val n = 4
        val flight = 300
        val stagger = 50
        assertEquals(450, FlickBundleLayout.throwMs(n, flight, stagger))
        // At the start nothing has moved; at the end everything has landed.
        for (i in 0 until n) {
            assertEquals(0f, FlickBundleLayout.localT(0f, i, n, flight, stagger), 0f)
            assertEquals(1f, FlickBundleLayout.localT(1f, i, n, flight, stagger), 0f)
        }
        // A third of the way in, the lead card is ahead of the ones behind it.
        val t = 1f / 3f
        val progress = (0 until n).map { FlickBundleLayout.localT(t, it, n, flight, stagger) }
        assertEquals(progress.sortedDescending(), progress)
        assertTrue(progress[0] > progress[3])
        // Card 3 starts only after 150 ms.
        assertEquals(0f, FlickBundleLayout.localT(149f / 450f, 3, n, flight, stagger), 0f)
        assertEquals(1f, FlickBundleLayout.localT(300f / 450f, 0, n, flight, stagger), 0.001f)
    }

    @Test
    fun trailingCardsArcToTheSameEndOnAlternatingSides() {
        val a = Pt(0f, 0f)
        val b = Pt(0f, -1000f)
        for (i in 0..5) {
            assertEquals(a, FlickBundleLayout.trailPoint(a, b, 0f, i, 30f))
            val end = FlickBundleLayout.trailPoint(a, b, 1f, i, 30f)
            assertEquals(0f, end.x, 0.01f)
            assertEquals(-1000f, end.y, 0.01f)
        }
        // The lead card flies straight; 1 and 2 bend to opposite sides; 3 bends further than 1.
        val mid = { i: Int -> FlickBundleLayout.trailPoint(a, b, 0.5f, i, 30f) }
        assertEquals(0f, mid(0).x, 0.01f)
        assertTrue(mid(1).x * mid(2).x < 0f)
        assertTrue(abs(mid(3).x) > abs(mid(1).x))
        assertEquals(-500f, mid(1).y, 0.01f)
        // A zero-length throw stays put.
        assertEquals(a, FlickBundleLayout.trailPoint(a, a, 0.5f, 2, 30f))
    }

    // MARK: - the phone's preview strip

    @Test
    fun stripTilesFitSideBySide() {
        val tiles = FlickBundleLayout.strip(3, 1000f, 400f, pad = 10f, gap = 20f)
        assertEquals(3, tiles.size)
        val w = (1000f - 20f - 40f) / 3f
        tiles.forEachIndexed { i, t ->
            assertEquals(w, t.width, 0.01f)
            assertEquals(380f, t.height, 0.01f)
            assertEquals(10f + i * (w + 20f), t.left, 0.01f)
        }
        assertEquals(990f, tiles.last().right, 0.01f)
        assertEquals(5, FlickBundleLayout.strip(7, 1000f, 400f, 0f, 10f).size)
        assertEquals(3, FlickBundleLayout.stripOverflow(7))
        assertEquals(0, FlickBundleLayout.stripOverflow(5))
        assertTrue(FlickBundleLayout.strip(0, 1000f, 400f, 0f, 10f).isEmpty())
    }

    // MARK: - progress for the whole bundle

    @Test
    fun bundleProgressByBytes() {
        val ids = listOf("a", "b", "c", "d")
        val sizes = mapOf("a" to 100L, "b" to 300L, "c" to 100L, "d" to 500L)
        val t = mapOf(
            "a" to FlickTransfer("a", 100, 100, FlickTransfer.State.DONE),
            "b" to FlickTransfer("b", 300, 150, FlickTransfer.State.SENDING),
            "c" to FlickTransfer("c", 100, 0, FlickTransfer.State.FAILED)
            // d: not started yet (queued)
        )
        val p = BundleProgress.of(ids, sizes, t)
        assertEquals(4, p.total)
        assertEquals(1, p.done)
        assertEquals(1, p.failed)
        assertFalse(p.finished)
        assertEquals((100 + 150 + 100) / 1000f, p.fraction, 0.001f)

        val all = BundleProgress.of(ids, sizes, ids.associateWith { FlickTransfer(it, sizes.getValue(it), sizes.getValue(it), FlickTransfer.State.DONE) })
        assertTrue(all.finished)
        assertEquals(1f, all.fraction, 0.0001f)
        assertTrue(BundleProgress.of(emptyList(), emptyMap(), emptyMap()).finished)
    }

    // MARK: - recent media (widget and tile)

    @Test
    fun recentMediaRowsBecomeFlickItems() {
        val photo = RecentMedia.map(42, RecentMedia.MEDIA_TYPE_IMAGE, "image/jpeg", "IMG_1.jpg", 2048, 1700000000, "DCIM/Camera/", "Camera")
        assertNotNull(photo!!)
        assertEquals("image", photo.kind)
        assertEquals("content://media/external/images/media/42", photo.uri)
        assertFalse(photo.isScreenshot)

        val video = RecentMedia.map(7, RecentMedia.MEDIA_TYPE_VIDEO, "video/mp4", "clip.mp4", 9, 1, "DCIM/Camera/", "Camera")!!
        assertEquals("video", video.kind)
        assertEquals("content://media/external/video/media/7", video.uri)

        // Screenshots: Pixel (Pictures/Screenshots), Samsung (DCIM/Screenshots), or by album name.
        assertTrue(RecentMedia.map(1, 1, "image/png", "s.png", 1, 1, "Pictures/Screenshots/", null)!!.isScreenshot)
        assertTrue(RecentMedia.map(2, 1, "image/jpeg", "s.jpg", 1, 1, "DCIM/Screenshots/", "Screenshots")!!.isScreenshot)
        assertTrue(RecentMedia.map(3, 1, "image/png", "s.png", 1, 1, null, "screenshots")!!.isScreenshot)
        // A screen recording is a video, not a screenshot.
        assertFalse(RecentMedia.map(4, 3, "video/mp4", "r.mp4", 1, 1, "DCIM/Screen recordings/", "Screenshots")!!.isScreenshot)
    }

    @Test
    fun recentMediaFallbacksAndRejects() {
        // Media type missing: the MIME type decides.
        assertEquals("image", RecentMedia.map(5, 0, "image/heic", "a.heic", 1, 1, null, null)!!.kind)
        assertEquals("video", RecentMedia.map(6, 0, "VIDEO/MP4", "b.mp4", 1, 1, null, null)!!.kind)
        assertNull(RecentMedia.map(8, 0, "application/pdf", "c.pdf", 1, 1, null, null))
        assertNull(RecentMedia.map(0, 1, "image/jpeg", "zero.jpg", 1, 1, null, null))
        // No name or MIME type: sensible defaults; negative sizes clamp to 0.
        val bare = RecentMedia.map(9, 1, null, " ", -5, 1, null, null)!!
        assertEquals("photo_9.jpg", bare.name)
        assertEquals("image/jpeg", bare.mime)
        assertEquals(0L, bare.size)
        assertEquals("video/mp4", RecentMedia.map(10, 3, "", null, 1, 1, null, null)!!.mime)
    }

    @Test
    fun widgetGridFitsSixToNine() {
        assertEquals(2 to 4, FlickWidgetGrid.of(320, 260))   // 4×3 cells: 8 photos
        assertEquals(3 to 3, FlickWidgetGrid.of(320, 330))   // tall: 3×3 = 9
        assertEquals(2 to 3, FlickWidgetGrid.of(250, 250))   // 6 photos
        assertEquals(1 to 3, FlickWidgetGrid.of(180, 160))   // smallest: one row
        val (r, c) = FlickWidgetGrid.of(1000, 1000)
        assertTrue(r * c <= FlickWidgetGrid.MAX_SHOWN)
        assertTrue(hypot(r.toDouble(), c.toDouble()) > 0)
    }
}
