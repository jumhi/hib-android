package com.sameerasw.airsync.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.spec.SecretKeySpec

/** Phone Remote (SPACE.md §15.3): the `/screen` frame header, its encryption and the Tailscale dial order. */
class ScreenStreamTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

    // MARK: - header

    /** The 22-byte little-endian header written by hand, exactly as §15.3 lays it out. */
    private fun header(kind: Int, seq: Long, pts: Long, cx: Float, cy: Float, visible: Int, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(22 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(kind.toByte()); b.putInt(seq.toInt()); b.putLong(pts); b.putFloat(cx); b.putFloat(cy); b.put(visible.toByte())
        b.put(payload)
        return b.array()
    }

    @Test
    fun headerIs22BytesLittleEndian() {
        assertEquals(22, ScreenFrame.HEADER_SIZE)
        val bytes = ScreenFrame.encode(2, 0x01020304L, 0x1122334455667788L, 0.5f, 0.25f, true, byteArrayOf(9))
        assertEquals(23, bytes.size)
        assertEquals(2.toByte(), bytes[0])
        // seq LE
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), bytes.copyOfRange(1, 5))
        // pts LE
        assertEquals(0x88.toByte(), bytes[5]); assertEquals(0x11.toByte(), bytes[12])
        assertEquals(1.toByte(), bytes[21])
        assertEquals(9.toByte(), bytes[22])
    }

    @Test
    fun parsesAllFourKinds() {
        val annexB = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3)
        for (kind in 1..4) {
            val f = ScreenFrame.parse(header(kind, 7L + kind, 1_000_000L * kind, 0.1f * kind, 0.2f, 1, annexB))!!
            assertEquals(kind, f.kind)
            assertEquals(7L + kind, f.seq)
            assertEquals(1_000_000L * kind, f.ptsMicros)
            assertEquals(0.1f * kind, f.cursorX, 0f)
            assertEquals(0.2f, f.cursorY, 0f)
            assertTrue(f.cursorVisible)
            assertArrayEquals(annexB, f.payload)
            assertEquals(kind != ScreenFrame.KIND_JPEG, f.isVideo)
        }
    }

    @Test
    fun nanCursorIsHidden() {
        val f = ScreenFrame.parse(header(3, 1, 0, Float.NaN, Float.NaN, 1, ByteArray(0)))!!
        assertFalse(f.cursorVisible)
        val g = ScreenFrame.parse(header(3, 1, 0, 0.5f, Float.NaN, 1, ByteArray(0)))!!
        assertFalse(g.cursorVisible)
    }

    @Test
    fun cursorFlagOffIsHiddenAndOffViewPositionsSurvive() {
        assertFalse(ScreenFrame.parse(header(3, 1, 0, 0.5f, 0.5f, 0, ByteArray(0)))!!.cursorVisible)
        val off = ScreenFrame.parse(header(3, 1, 0, -0.3f, 1.7f, 1, ByteArray(0)))!!
        assertTrue(off.cursorVisible)
        assertEquals(-0.3f, off.cursorX, 0f)
        assertEquals(1.7f, off.cursorY, 0f)
    }

    @Test
    fun seqIsUnsigned32() {
        val f = ScreenFrame.parse(header(3, 0xFFFF_FFF0L, 0, 0f, 0f, 0, ByteArray(0)))!!
        assertEquals(0xFFFF_FFF0L, f.seq)
    }

    @Test
    fun rejectsShortOrUnknown() {
        assertNull(ScreenFrame.parse(ByteArray(21)))
        assertNull(ScreenFrame.parse(header(0, 1, 0, 0f, 0f, 0, ByteArray(0))))
        assertNull(ScreenFrame.parse(header(5, 1, 0, 0f, 0f, 0, ByteArray(0))))
        // Exactly a header, no payload: valid (an empty frame).
        assertEquals(0, ScreenFrame.parse(header(3, 1, 0, 0f, 0f, 0, ByteArray(0)))!!.payload.size)
    }

    @Test
    fun encodeParseRoundTrip() {
        val payload = ByteArray(1000) { (it * 7).toByte() }
        val f = ScreenFrame.parse(ScreenFrame.encode(1, 42, 123456789L, 0.75f, 0.125f, true, payload))!!
        assertEquals(1, f.kind); assertEquals(42L, f.seq); assertEquals(123456789L, f.ptsMicros)
        assertEquals(0.75f, f.cursorX, 0f); assertEquals(0.125f, f.cursorY, 0f)
        assertArrayEquals(payload, f.payload)
    }

    // MARK: - crypto

    @Test
    fun binaryFramesAreRawNonceCipherTag() {
        val plain = ScreenFrame.encode(2, 1, 2, 0f, 0f, false, ByteArray(100) { it.toByte() })
        val sealed = RemoteCrypto.seal(plain, key)
        assertEquals(12 + plain.size + 16, sealed.size)
        assertArrayEquals(plain, RemoteCrypto.open(sealed, key))
    }

    @Test
    fun textFramesAreBase64OfTheSameLayout() {
        val json = """{"type":"screenOpen","data":{"display":""}}"""
        val text = RemoteCrypto.sealText(json, key)
        // base64 of nonce12‖ct‖tag16 …
        val raw = java.util.Base64.getDecoder().decode(text)
        assertEquals(12 + json.toByteArray().size + 16, raw.size)
        // … so the binary opener reads it once decoded, and the text opener reads it directly.
        assertEquals(json, String(RemoteCrypto.open(raw, key)!!))
        assertEquals(json, RemoteCrypto.openText(text, key))
    }

    @Test
    fun textOpenerToleratesLineBreaks() {
        val json = """{"type":"screenPong","data":{"t":5}}"""
        val text = RemoteCrypto.sealText(json, key)
        val wrapped = text.chunked(20).joinToString("\n")
        assertEquals(json, RemoteCrypto.openText(wrapped, key))
    }

    @Test
    fun wrongKeyOrTamperingFails() {
        val sealed = RemoteCrypto.seal(byteArrayOf(1, 2, 3), key)
        assertNull(RemoteCrypto.open(sealed, otherKey))
        val tampered = sealed.copyOf().also { it[14] = (it[14] + 1).toByte() }
        assertNull(RemoteCrypto.open(tampered, key))
        assertNull(RemoteCrypto.open(ByteArray(27), key))
        assertNull(RemoteCrypto.openText("not base64 !!!", key))
        assertNull(RemoteCrypto.openText(RemoteCrypto.sealText("{}", key), otherKey))
    }

    @Test
    fun noncesAreFresh() {
        val a = RemoteCrypto.seal(byteArrayOf(1), key)
        val b = RemoteCrypto.seal(byteArrayOf(1), key)
        assertNotEquals(a.copyOfRange(0, 12).toList(), b.copyOfRange(0, 12).toList())
    }

    // MARK: - Annex-B

    @Test
    fun splitsSpsAndPps() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0, 0, 1, 0x68, 0xce.toByte(), 0x3c)
        val (s, p) = H264Decoder.splitConfig(sps + pps)
        assertArrayEquals(sps, s)
        assertArrayEquals(pps, p)
        val (s2, p2) = H264Decoder.splitConfig(sps)
        assertArrayEquals(sps, s2)
        assertNull(p2)
    }

    // MARK: - Tailscale

    @Test
    fun tailscaleRanges() {
        assertTrue(TailscaleAddrs.isTailscale("100.64.0.1"))
        assertTrue(TailscaleAddrs.isTailscale("100.101.102.103"))
        assertTrue(TailscaleAddrs.isTailscale("100.127.255.254"))
        assertFalse(TailscaleAddrs.isTailscale("100.63.255.255"))
        assertFalse(TailscaleAddrs.isTailscale("100.128.0.1"))
        assertFalse(TailscaleAddrs.isTailscale("192.168.1.20"))
        assertFalse(TailscaleAddrs.isTailscale("100.64.0"))
        assertTrue(TailscaleAddrs.isTailscale("fd7a:115c:a1e0::1"))
        assertTrue(TailscaleAddrs.isTailscale("[fd7a:115c:a1e0:ab12:4843:cd96:6258:b240]"))
        assertTrue(TailscaleAddrs.isTailscale("FD7A:115C:A1E0::53%tun0"))
        assertFalse(TailscaleAddrs.isTailscale("fd7a:115c:a1e1::1"))
        assertFalse(TailscaleAddrs.isTailscale("fe80::1"))
        assertFalse(TailscaleAddrs.isTailscale("fd00::1"))
    }

    @Test
    fun lanFirstThenTailscaleOneSecondLater() {
        val plan = TailscaleAddrs.plan(
            lan = listOf("192.168.1.20", "fe80::1%wlan0"),
            tailscale = listOf("100.101.102.103", "fd7a:115c:a1e0::1", "192.168.1.20", "8.8.8.8")
        )
        assertEquals(
            listOf(
                TailscaleAddrs.Dial("192.168.1.20", 0L),
                TailscaleAddrs.Dial("fe80::1%wlan0", 0L),
                TailscaleAddrs.Dial("100.101.102.103", 1_000L),
                TailscaleAddrs.Dial("fd7a:115c:a1e0::1", 1_000L)
            ),
            plan
        )
    }

    @Test
    fun aTailscaleAddressAlreadyKnownAsLanStaysImmediate() {
        val plan = TailscaleAddrs.plan(listOf("100.101.102.103"), listOf("100.101.102.103"))
        assertEquals(listOf(TailscaleAddrs.Dial("100.101.102.103", 0L)), plan)
        assertEquals(listOf(TailscaleAddrs.Dial("100.64.0.9", 1_000L)), TailscaleAddrs.plan(emptyList(), listOf("100.64.0.9")))
    }

    // MARK: - messages

    @Test
    fun parsesRemoteHello() {
        val json = """{"type":"remoteHello","data":{"v":1,"id":"PC-INTERN","platform":"windows","screen":true,"control":true,
            "unlock":"managed","locked":false,
            "displays":[{"id":"\\\\.\\DISPLAY1","name":"Odyssey G9","w":5120,"h":1440,"main":true},{"id":"b","name":"Side","w":1920,"h":1080,"main":false}],
            "addrs":["100.101.102.103","fd7a:115c:a1e0::1","192.168.1.4"]}}"""
        val h = RemoteJson.hello(json, "fallback")!!
        assertEquals("PC-INTERN", h.id)
        assertEquals("windows", h.platform)
        assertTrue(h.screen); assertTrue(h.control); assertFalse(h.locked)
        assertEquals(RemoteHello.UNLOCK_MANAGED, h.unlock)
        assertEquals(2, h.displays.size)
        assertEquals("\\\\.\\DISPLAY1", h.mainDisplay!!.id)
        assertEquals(5120, h.mainDisplay!!.w)
        // Only Tailscale addresses are remembered.
        assertEquals(listOf("100.101.102.103", "fd7a:115c:a1e0::1"), h.addrs)
    }

    @Test
    fun helloDefaultsForOlderOrPartialMessages() {
        val h = RemoteJson.hello("""{"type":"remoteHello","data":{}}""", "Mac")!!
        assertEquals("Mac", h.id)
        assertFalse(h.screen)
        assertEquals(RemoteHello.UNLOCK_UNSUPPORTED, h.unlock)
        assertTrue(h.displays.isEmpty())
        assertNull(RemoteJson.hello("nope", "Mac"))
    }

    @Test
    fun buildsScreenOpenAndControlMessages() {
        val open = JsonParser.parseString(
            RemoteJson.screenOpen("", 2400, 1080, 60, "h264", ViewRect(0f, 0f, 1f, 1f), "tailscale")
        ).asJsonObject
        assertEquals("screenOpen", open.get("type").asString)
        val d = open.getAsJsonObject("data")
        assertEquals("", d.get("display").asString)
        assertEquals(2400, d.get("maxW").asInt); assertEquals(1080, d.get("maxH").asInt)
        assertEquals(60, d.get("fps").asInt); assertEquals("h264", d.get("codec").asString)
        assertEquals(1.0, d.getAsJsonObject("view").get("w").asDouble, 0.0)
        assertEquals("tailscale", d.get("route").asString)
        val lan = JsonParser.parseString(RemoteJson.screenOpen("x", 1, 1, 30, "jpeg", ViewRect.FULL, null)).asJsonObject
        assertFalse(lan.getAsJsonObject("data").has("route"))

        val view = JsonParser.parseString(RemoteJson.screenView(ViewRect(0.2f, 0.1f, 0.5f, 0.5f))).asJsonObject
        assertEquals("screenView", view.get("type").asString)
        assertEquals(0.2, view.getAsJsonObject("data").get("x").asDouble, 1e-6)
        assertEquals("screenDisplay", RemoteJson.type(RemoteJson.screenDisplay("b")))
        assertEquals("screenKeyframe", RemoteJson.type(RemoteJson.screenKeyframe()))
        assertEquals(1234L, RemoteJson.pongT("""{"type":"screenPong","data":{"t":1234}}"""))
        val stats = JsonParser.parseString(RemoteJson.screenStats(58, 23, 4)).asJsonObject.getAsJsonObject("data")
        assertEquals(58, stats.get("fps").asInt); assertEquals(23, stats.get("rttMs").asInt); assertEquals(4, stats.get("decodeMs").asInt)
        val opened = RemoteJson.opened("""{"type":"screenOpened","data":{"display":"a","w":1920,"h":540,"codec":"h264"}}""")!!
        assertEquals(RemoteJson.Opened("a", 1920, 540, "h264"), opened)
        assertEquals("locked", RemoteJson.pausedReason("""{"type":"screenPaused","data":{"reason":"locked"}}"""))
    }

    @Test
    fun parsesFocusLockAndUnlockReplies() {
        assertEquals(RemoteFocus(true, true), RemoteJson.focus("""{"type":"remoteFocus","data":{"text":true,"secure":true}}"""))
        assertEquals(RemoteFocus(false, false), RemoteJson.focus("""{"type":"remoteFocus","data":{"text":false}}"""))
        assertEquals(true, RemoteJson.locked("""{"type":"remoteLockState","data":{"locked":true}}"""))
        assertEquals("abc=", RemoteJson.challengeNonce("""{"type":"unlockChallenge","data":{"nonce":"abc="}}"""))
        assertEquals(UnlockResult(false, "busy"), RemoteJson.unlockResult("""{"type":"unlockResult","data":{"ok":false,"error":"busy"}}"""))
        assertEquals(UnlockResult(true, null), RemoteJson.unlockResult("""{"type":"unlockResult","data":{"ok":true,"error":null}}"""))
        assertEquals("Mac", RemoteJson.enrollName("""{"type":"unlockEnrollRequest","data":{"name":"Mac"}}"""))
        assertTrue(RemoteJson.enrollOk("""{"type":"unlockEnrollResult","data":{"ok":true}}"""))
    }
}
