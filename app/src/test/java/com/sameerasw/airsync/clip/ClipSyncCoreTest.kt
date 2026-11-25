package com.sameerasw.airsync.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The phone's one clipboard sync component (SPACE.md §16), each test reproducing a finding of
 * docs/superpowers/specs/2026-11-25-clipboard-findings.md.
 */
class ClipSyncCoreTest {

    private class FakePort(var links: List<String>) : ClipPort {
        val sent = mutableListOf<Pair<String, Clip>>()
        val applied = mutableListOf<Clip>()
        /** What the phone's clipboard holds after our writes (with the tag Android keeps in the description). */
        var clipboard: LocalClip? = null
        var stamp = 0L

        override fun readyLinks(): List<String> = links
        override fun send(linkId: String, clip: Clip): Boolean { sent += linkId to clip; return true }
        override fun apply(clip: Clip): Boolean {
            applied += clip
            clipboard = LocalClip(clip.text, clip.png, appliedTag = clip.id, stamp = ++stamp)
            return true
        }
        fun sentFromPhone() = sent.filter { it.second.origin == "phone" }
    }

    private var now = 1_764_072_000_000L
    private var ids = 0
    private val logs = mutableListOf<String>()

    private fun core(port: FakePort) = ClipSyncCore(
        port = port,
        clock = { now },
        newId = { "phone-id-${++ids}" },
        log = { logs += it }
    )

    // Finding 3: the Mac sends each clip over WebSocket and BLE; the phone set it twice, the first
    // callback cleared the dedupe and the second callback sent the clip back to every computer.
    @Test
    fun doubleDeliveryIsAppliedOnceAndNeverEchoed() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        val clip = ClipIn("hello", null, id = "macclip1", ts = now, origin = "Mac")

        assertEquals(ClipSyncCore.Outcome.APPLIED, core.onInbound("Mac", clip, "phone"))
        core.onLocal(port.clipboard!!, ClipSyncCore.Trigger.CALLBACK)
        assertEquals(ClipSyncCore.Outcome.SEEN, core.onInbound("Mac", clip, "ble"))
        core.onLocal(port.clipboard!!, ClipSyncCore.Trigger.CALLBACK)

        assertEquals(1, port.applied.size)
        assertTrue("nothing goes back out as a phone copy", port.sentFromPhone().isEmpty())
        assertEquals(listOf("PC"), port.sent.map { it.first })
    }

    @Test
    fun doubleDeliveryOfAnOldFormatClipIsAppliedOnce() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        core.onInbound("Mac", ClipIn("hello", null), "phone")
        core.onLocal(port.clipboard!!, ClipSyncCore.Trigger.CALLBACK)
        assertEquals(ClipSyncCore.Outcome.SEEN, core.onInbound("Mac", ClipIn("hello", null), "ble"))
        core.onLocal(port.clipboard!!, ClipSyncCore.Trigger.CALLBACK)
        assertEquals(1, port.applied.size)
        assertTrue(port.sentFromPhone().isEmpty())
    }

    // Finding 2: a late clip from the Mac (copied before) overwrote a newer copy made on the PC.
    @Test
    fun staleClipNeverOverwritesANewerOne() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        core.onInbound("PC", ClipIn("Y", null, id = "y", ts = now, origin = "PC"), "phone")
        port.sent.clear()

        val late = ClipIn("X", null, id = "x", ts = now - 2_000, origin = "Mac")
        assertEquals(ClipSyncCore.Outcome.OLDER, core.onInbound("Mac", late, "phone"))
        assertEquals(listOf("Y"), port.applied.map { it.text })
        assertTrue("an older clip is not relayed either", port.sent.isEmpty())
    }

    @Test
    fun clockSkewUnderOneAndAHalfSecondsStillApplies() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        core.onInbound("PC", ClipIn("Y", null, id = "y", ts = now, origin = "PC"), "phone")
        assertEquals(
            ClipSyncCore.Outcome.APPLIED,
            core.onInbound("Mac", ClipIn("X", null, id = "x", ts = now - 1_400, origin = "Mac"), "phone")
        )
    }

    @Test
    fun phoneCopyIsNewerThanAnAppliedClip() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        core.onInbound("Mac", ClipIn("A", null, id = "a", ts = now, origin = "Mac"), "phone")
        now += 5_000
        core.onLocal(LocalClip("B", null, stamp = now), ClipSyncCore.Trigger.CALLBACK)
        val late = ClipIn("C", null, id = "c", ts = now - 3_000, origin = "Mac")
        assertEquals(ClipSyncCore.Outcome.OLDER, core.onInbound("Mac", late, "phone"))
    }

    // Copying the same text again is a new clip, and must sync again.
    @Test
    fun sameTextCopiedAgainIsANewClip() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        assertEquals(ClipSyncCore.Outcome.COPIED, core.onLocal(LocalClip("hi", null, stamp = 1), ClipSyncCore.Trigger.CALLBACK))
        now += 10_000
        assertEquals(ClipSyncCore.Outcome.COPIED, core.onLocal(LocalClip("hi", null, stamp = 2), ClipSyncCore.Trigger.CALLBACK))
        assertEquals(2, port.sent.size)
        assertNotEquals(port.sent[0].second.id, port.sent[1].second.id)
    }

    @Test
    fun copyingAppliedTextAgainOnThePhoneSyncs() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        core.onInbound("Mac", ClipIn("hi", null, id = "m1", ts = now, origin = "Mac"), "phone")
        core.onLocal(port.clipboard!!, ClipSyncCore.Trigger.CALLBACK) // our own write: not a copy
        port.sent.clear()
        now += 10_000
        // The user copies the same words in another app: no tag, new description timestamp.
        assertEquals(ClipSyncCore.Outcome.COPIED, core.onLocal(LocalClip("hi", null, stamp = 99), ClipSyncCore.Trigger.CALLBACK))
        assertEquals(setOf("Mac", "PC"), port.sent.map { it.first }.toSet())
    }

    @Test
    fun theSameChangeSeenTwiceIsSentOnce() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        core.onLocal(LocalClip("hi", null, stamp = 7), ClipSyncCore.Trigger.CALLBACK)
        // e.g. the log watcher's focus read and then the app coming to the front
        assertEquals(ClipSyncCore.Outcome.UNCHANGED, core.onLocal(LocalClip("hi", null, stamp = 7), ClipSyncCore.Trigger.POLL))
        assertEquals(1, port.sent.size)
    }

    // Relay: forward id/ts/origin unchanged, never back to the origin or the link it came from.
    @Test
    fun relayNeverGoesBackToOriginOrSourceLink() {
        val port = FakePort(listOf("Mac", "PC", "Laptop"))
        val core = core(port)
        // A clip the Mac made, reaching the phone through the PC (e.g. after a Roam crossing).
        val inc = ClipIn("x", null, id = "abc", ts = now - 100, origin = "Mac")
        core.onInbound("PC", inc, "phone")
        assertEquals(listOf("Laptop"), port.sent.map { it.first })
        val out = port.sent.single().second
        assertEquals("abc", out.id)
        assertEquals(now - 100, out.ts)
        assertEquals("Mac", out.origin)
    }

    @Test
    fun originMatchIgnoresCaseAndApostropheStyleWhenToldTo() {
        val port = FakePort(listOf("Haitham’s MacBook", "PC"))
        val core = ClipSyncCore(port, clock = { now }, sameDevice = { a, b ->
            a.replace('’', '\'').trim().lowercase() == b.replace('’', '\'').trim().lowercase()
        })
        core.onInbound("PC", ClipIn("x", null, id = "i", ts = now, origin = "haitham's macbook"), "phone")
        assertTrue(port.sent.isEmpty())
    }

    // Finding 7: nothing was re-sent to a computer whose link came back.
    @Test
    fun catchUpSendsTheCurrentClipWhenALinkBecomesReady() {
        val port = FakePort(emptyList())
        val core = core(port)
        core.onLocal(LocalClip("while offline", null, stamp = now), ClipSyncCore.Trigger.CALLBACK)
        assertTrue(port.sent.isEmpty())
        now += 60_000
        port.links = listOf("PC")
        assertTrue(core.onLinkReady("PC"))
        val (to, clip) = port.sent.single()
        assertEquals("PC", to)
        assertEquals("phone", clip.origin)
        assertEquals("phone-id-1", clip.id)
        assertEquals(now - 60_000, clip.ts)
    }

    @Test
    fun catchUpSkipsOldClipsAndTheOrigin() {
        val port = FakePort(emptyList())
        val core = core(port)
        core.onInbound("Mac", ClipIn("m", null, id = "m", ts = now, origin = "Mac"), "phone")
        assertFalse("never back to its origin", core.onLinkReady("Mac"))
        assertTrue(core.onLinkReady("PC"))
        now += 10 * 60_000 + 1
        assertFalse("older than 10 minutes", core.onLinkReady("Laptop"))
    }

    // Old computers send {"text":…} only (§16.1: derive id/ts/origin).
    @Test
    fun oldFormatMessagesGetDerivedFields() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        assertEquals(ClipSyncCore.Outcome.APPLIED, core.onInbound("Mac", ClipIn("old", null), "phone"))
        val out = port.sent.single()
        assertEquals("PC", out.first)
        assertEquals("Mac", out.second.origin)
        assertEquals(now, out.second.ts)
        assertEquals(ClipIds.derived("old".toByteArray()), out.second.id)
        assertEquals(22, out.second.id.length) // 16 bytes base64url, no padding
    }

    @Test
    fun oldFormatSameTextLaterAppliesAgainAfterSomethingElse() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        core.onInbound("Mac", ClipIn("A", null), "phone")
        now += 5_000
        core.onLocal(LocalClip("B", null, stamp = now), ClipSyncCore.Trigger.CALLBACK)
        now += 5_000
        assertEquals(ClipSyncCore.Outcome.APPLIED, core.onInbound("Mac", ClipIn("A", null), "phone"))
    }

    @Test
    fun seenIdsKeepTheLast64() {
        val port = FakePort(emptyList())
        val core = core(port)
        for (i in 0 until 65) {
            now += 10
            core.onInbound("Mac", ClipIn("t$i", null, id = "id$i", ts = now, origin = "Mac"), "phone")
        }
        now += 10
        // id0 has fallen out; id64 is still known.
        assertEquals(ClipSyncCore.Outcome.SEEN, core.onInbound("Mac", ClipIn("t64", null, id = "id64", ts = now, origin = "Mac"), "phone"))
        assertEquals(ClipSyncCore.Outcome.APPLIED, core.onInbound("Mac", ClipIn("t0", null, id = "id0", ts = now, origin = "Mac"), "phone"))
    }

    @Test
    fun syncOffNeitherSendsNorApplies() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        core.enabled = false
        assertEquals(ClipSyncCore.Outcome.OFF, core.onInbound("Mac", ClipIn("x", null, id = "x", ts = now, origin = "Mac"), "phone"))
        assertEquals(ClipSyncCore.Outcome.OFF, core.onLocal(LocalClip("y", null, stamp = 1), ClipSyncCore.Trigger.CALLBACK))
        assertTrue(port.applied.isEmpty() && port.sent.isEmpty())
        // An explicit "Send clipboard" still goes.
        assertEquals(ClipSyncCore.Outcome.COPIED, core.onLocal(LocalClip("y", null, stamp = 1), ClipSyncCore.Trigger.EXPLICIT))
        assertEquals(1, port.sent.size)
    }

    @Test
    fun sizeLimits() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        val big = "a".repeat(1_000_001)
        assertEquals(ClipSyncCore.Outcome.SIZE, core.onLocal(LocalClip(big, null, stamp = 1), ClipSyncCore.Trigger.CALLBACK))
        val png = Base64.getEncoder().encodeToString(ByteArray(8_000_001))
        assertEquals(ClipSyncCore.Outcome.SIZE, core.onInbound("Mac", ClipIn(null, png, id = "p", ts = now, origin = "Mac"), "phone"))
        val okPng = Base64.getEncoder().encodeToString(ByteArray(1000) { it.toByte() })
        assertEquals(ClipSyncCore.Outcome.APPLIED, core.onInbound("Mac", ClipIn(null, okPng, id = "q", ts = now, origin = "Mac"), "phone"))
        assertTrue(logs.any { it.startsWith("[clip] ignored(size) id=") })
    }

    @Test
    fun logLinesFollowTheFormatAndNeverCarryContent() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        core.onInbound("Mac", ClipIn("secret words", null, id = "abcdefghij", ts = now, origin = "Mac"), "phone")
        core.onLocal(LocalClip("other secret", null, stamp = 5), ClipSyncCore.Trigger.CALLBACK)
        assertTrue(logs.contains("[clip] applied id=abcdef from=Mac via=phone"))
        assertTrue(logs.contains("[clip] copied id=phone- from=phone via=phone"))
        assertTrue(logs.none { it.contains("secret") })
        val pattern = Regex("""\[clip] (copied|sent|applied|ignored\((seen|older|off|size)\)) id=\S{1,6} from=\S+ via=(phone|roam|ble)""")
        assertTrue(logs.all { pattern.matches(it) })
    }

    @Test
    fun ownImageWriteIsNotACopy() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        core.onInbound("Mac", ClipIn(null, png, id = "img", ts = now, origin = "Mac"), "phone")
        assertEquals(ClipSyncCore.Outcome.OWN_APPLY, core.onLocal(port.clipboard!!, ClipSyncCore.Trigger.CALLBACK))
        assertTrue(port.sentFromPhone().isEmpty())
    }

    @Test
    fun aStaleClipAlreadyOnThePhoneAtStartIsNotPushed() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        val outcome = core.onLocal(LocalClip("from an hour ago", null, stamp = now - 3_600_000), ClipSyncCore.Trigger.POLL)
        assertEquals(ClipSyncCore.Outcome.UNCHANGED, outcome)
        assertTrue(port.sent.isEmpty())
    }

    @Test
    fun explicitSendToOneComputerCountsWhoGotIt() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        assertEquals(1, core.explicit(LocalClip("x", null), only = "PC"))
        assertEquals(listOf("PC"), port.sent.map { it.first })
        // The same words again on purpose: a new clip, sent again.
        assertEquals(2, core.explicit(LocalClip("x", null)))
        assertNotEquals(port.sent[0].second.id, port.sent[1].second.id)
    }

    @Test
    fun ownWritesOutsideASyncAreNotCopies() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        core.noteOwnWrite("widget-get")
        assertTrue(core.isOwn("widget-get"))
        assertEquals(ClipSyncCore.Outcome.OWN_APPLY, core.onLocal(LocalClip("pulled", null, appliedTag = "widget-get", stamp = 3), ClipSyncCore.Trigger.CALLBACK))
        assertTrue(core.isKnownStamp(3))
        assertTrue(port.sent.isEmpty())
    }

    @Test
    fun screenshotsKeepTheirOlderMessageShape() {
        val port = FakePort(listOf("Mac"))
        val core = core(port)
        val jpg = Base64.getEncoder().encodeToString(byteArrayOf(9, 9))
        core.explicit(LocalClip(null, jpg, mime = "image/jpeg", name = "Screenshot.jpg", legacyImage = true))
        val sent = port.sent.single().second
        assertTrue(sent.legacyImage)
        assertEquals("image/jpeg", sent.mime)
        assertEquals("phone", sent.origin)
    }

    @Test
    fun relayedOldImageStaysAnOldImageMessage() {
        val port = FakePort(listOf("Mac", "PC"))
        val core = core(port)
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(5, 6, 7))
        core.onInbound("Mac", ClipIn(null, b64, mime = "image/jpeg", name = "a.jpg", legacyImage = true), "phone")
        val (to, clip) = port.sent.single()
        assertEquals("PC", to)
        assertTrue(clip.legacyImage)
        assertEquals(b64, clip.png)
        assertEquals(ClipIds.derived(byteArrayOf(5, 6, 7)), clip.id)
    }

    @Test
    fun recentlyAppliedCoversTheWriteWindow() {
        val port = FakePort(emptyList())
        val core = core(port)
        assertFalse(core.recentlyApplied(1_000))
        core.onInbound("Mac", ClipIn("x", null, id = "x", ts = now, origin = "Mac"), "phone")
        assertTrue(core.recentlyApplied(1_000))
        now += 1_500
        assertFalse(core.recentlyApplied(1_000))
    }
}
