package com.sameerasw.airsync.multidesk

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADB setup without a terminal (SPACE.md §8): state JSON, request parsing, own-service pick, dedup, watch. */
class AdbSetupTest {

    private val phoneIp = "10.107.4.23"
    private val local = setOf(phoneIp, "127.0.0.1", "fe80::a1b2:c3ff:fed4:e5f6%wlan0", "::1")

    // MARK: - adbSetupState JSON

    @Test
    fun stateJsonMatchesTheSpec() {
        val s = AdbSetupState(true, false, connectPort = 42789)
        val root = JsonParser.parseString(AdbSetupJson.state(s)).asJsonObject
        assertEquals("adbSetupState", root.get("type").asString)
        val d = root.getAsJsonObject("data")
        assertEquals(setOf("developerOptions", "wirelessDebugging", "connectPort", "pairingPort", "pairingName"), d.keySet())
        assertTrue(d.get("developerOptions").asBoolean)
        assertFalse(d.get("wirelessDebugging").asBoolean)
        assertEquals(42789, d.get("connectPort").asInt)
        // Unknown values are explicit nulls, not missing keys.
        assertTrue(d.get("pairingPort").isJsonNull)
        assertTrue(d.get("pairingName").isJsonNull)
        assertTrue(AdbSetupJson.state(s).contains("\"pairingPort\":null"))
    }

    @Test
    fun stateJsonWithPairing() {
        val s = AdbSetupState(true, true, 37001, 41235, "hib-Office PC")
        val d = JsonParser.parseString(AdbSetupJson.state(s)).asJsonObject.getAsJsonObject("data")
        assertEquals(37001, d.get("connectPort").asInt)
        assertEquals(41235, d.get("pairingPort").asInt)
        assertEquals("hib-Office PC", d.get("pairingName").asString)
    }

    @Test
    fun stateTypeRoutesToEveryComputerAndRequestIsNotARequester() {
        assertEquals("adbSetupState", DesktopRouting.messageType(AdbSetupJson.state(AdbSetupState.OFF)))
        assertEquals(DesktopRouting.Route.All, DesktopRouting.decide(AdbSetupJson.STATE, null, "Office PC"))
        assertFalse(DesktopRouting.countsAsRequest(AdbSetupJson.REQUEST))
        val req = """{"type":"adbSetupRequest","data":{"open":null,"watch":true}}"""
        assertEquals("adbSetupRequest", DesktopRouting.messageType(req))
    }

    // MARK: - building the state

    @Test
    fun buildNullsPortsWhenWirelessDebuggingIsOff() {
        val connect = AdbService("adb-R5GL-abc", 37001, listOf(phoneIp))
        val pairing = AdbService("adb-R5GL-abc", 41235, listOf(phoneIp))
        assertEquals(
            AdbSetupState(true, false, null, null, null),
            AdbSetupState.build(true, wirelessSetting = false, connect = connect, pairing = pairing)
        )
        assertEquals(
            AdbSetupState(true, true, 37001, 41235, "adb-R5GL-abc"),
            AdbSetupState.build(true, wirelessSetting = true, connect = connect, pairing = pairing)
        )
        assertEquals(
            AdbSetupState(true, true, null, null, null),
            AdbSetupState.build(true, wirelessSetting = true, connect = null, pairing = null)
        )
    }

    @Test
    fun buildFallsBackToTheServiceWhenTheSettingIsUnreadable() {
        val connect = AdbService("adb-R5GL-abc", 37001)
        assertEquals(AdbSetupState(true, true, 37001), AdbSetupState.build(true, null, connect, null))
        assertEquals(AdbSetupState(true, false), AdbSetupState.build(true, null, null, null))
    }

    @Test
    fun buildDropsBadPorts() {
        val s = AdbSetupState.build(true, true, AdbService("a", 0), AdbService("b", 70000))
        assertNull(s.connectPort)
        assertNull(s.pairingPort)
        assertNull(s.pairingName)
    }

    // MARK: - adbSetupRequest

    @Test
    fun parsesRequests() {
        assertEquals(
            AdbSetupRequest(AdbSetupOpen.DEVELOPER, true),
            AdbSetupJson.parseRequest("""{"type":"adbSetupRequest","data":{"open":"developer","watch":true}}""")
        )
        assertEquals(
            AdbSetupRequest(AdbSetupOpen.WIRELESS, false),
            AdbSetupJson.parseRequest("""{"type":"adbSetupRequest","data":{"open":"wireless","watch":false}}""")
        )
        assertEquals(
            AdbSetupRequest(null, true),
            AdbSetupJson.parseRequest("""{"type":"adbSetupRequest","data":{"open":null,"watch":true}}""")
        )
        // No watch key: leave watching as it is.
        assertEquals(AdbSetupRequest(AdbSetupOpen.WIRELESS, null), AdbSetupJson.parseRequest("""{"data":{"open":"Wireless"}}"""))
        assertEquals(AdbSetupRequest(null, null), AdbSetupJson.parseRequest("""{"type":"adbSetupRequest","data":{}}"""))
        // Unknown screens are ignored, not guessed.
        assertEquals(AdbSetupRequest(null, true), AdbSetupJson.parseRequest("""{"data":{"open":"bootloader","watch":"true"}}"""))
        assertNull(AdbSetupJson.parseRequest("garbage"))
        assertNull(AdbSetupJson.parseRequest(""))
        assertNull(AdbSetupJson.parseRequest(null))
    }

    // MARK: - the phone's own service

    @Test
    fun normalizesAddresses() {
        // The canonical IPv6 form differs between the JVM and Android; only "same address → same string" matters.
        assertEquals(AdbOwnService.normalize("fe80::a1b2:c3ff:fed4:e5f6"), AdbOwnService.normalize("FE80::A1B2:C3FF:FED4:E5F6%wlan0"))
        assertEquals(AdbOwnService.normalize("::1"), AdbOwnService.normalize("0:0:0:0:0:0:0:1"))
        assertFalse(AdbOwnService.normalize("fe80::1").contains('%'))
        assertEquals("10.107.4.23", AdbOwnService.normalize("/10.107.4.23"))
        assertEquals("android.local", AdbOwnService.normalize("Android.local"))
    }

    @Test
    fun picksTheServiceOnThePhonesOwnAddress() {
        val other = AdbService("adb-OTHER-xyz", 40001, listOf("10.107.4.99"))
        val mine = AdbService("adb-R5GL-abc", 37001, listOf(phoneIp))
        assertEquals(mine, AdbOwnService.pick(listOf(other, mine), local))
        assertEquals(mine, AdbOwnService.pick(listOf(mine, other), local))
        // IPv6 link-local with a scope id still matches.
        val v6 = AdbService("adb-R5GL-abc", 37002, listOf("fe80::a1b2:c3ff:fed4:e5f6%23"))
        assertEquals(v6, AdbOwnService.pick(listOf(other, v6), local))
    }

    @Test
    fun neverPicksAnotherPhone() {
        val other = AdbService("adb-OTHER-xyz", 40001, listOf("10.107.4.99"))
        assertNull(AdbOwnService.pick(listOf(other), local))
        assertNull(AdbOwnService.pick(emptyList(), local))
    }

    @Test
    fun newestOwnServiceWins() {
        val old = AdbService("adb-R5GL-abc", 37001, listOf(phoneIp))
        val new = AdbService("adb-R5GL-abc", 38555, listOf(phoneIp))
        assertEquals(new, AdbOwnService.pick(listOf(old, new), local))
    }

    @Test
    fun loneServiceWithoutHostIsTakenButNotWhenThereAreOthers() {
        val bare = AdbService("adb-R5GL-abc", 37001)
        assertEquals(bare, AdbOwnService.pick(listOf(bare), local))
        val other = AdbService("adb-OTHER-xyz", 40001, listOf("10.107.4.99"))
        assertNull(AdbOwnService.pick(listOf(other, bare), local))
        assertNull(AdbOwnService.pick(listOf(bare, AdbService("adb-X", 40002)), local))
    }

    @Test
    fun pairingCodeServiceSharesTheConnectName() {
        val other = AdbService("adb-OTHER-xyz", 40001)
        val code = AdbService("adb-R5GL-abc", 41235)
        assertEquals(code, AdbOwnService.pick(listOf(other, code), local, ownName = "adb-R5GL-abc"))
        assertNull(AdbOwnService.pick(listOf(other, code), local, ownName = null))
    }

    // MARK: - change detection

    @Test
    fun ledgerSendsEachChangeOncePerComputer() {
        val ledger = AdbStateLedger()
        val a = AdbSetupState(true, false)
        val b = AdbSetupState(true, true, 37001)
        assertTrue(ledger.needs("Office PC", a))
        ledger.record("Office PC", a)
        assertFalse(ledger.needs("Office PC", a))
        assertFalse(ledger.needs("office pc ", a)) // same computer, normalized name
        assertTrue(ledger.needs("Haitham’s MacBook", a)) // another computer still needs it
        assertTrue(ledger.needs("Office PC", b))
        ledger.record("Office PC", b)
        assertFalse(ledger.needs("Office PC", b))
        // Back to an earlier state is a change too.
        assertTrue(ledger.needs("Office PC", a))
        // A computer that reconnects is told again.
        ledger.forget("Office PC")
        assertTrue(ledger.needs("Office PC", b))
        assertEquals(emptySet<String>(), ledger.computers())
    }

    // MARK: - watch timer

    @Test
    fun watchLastsFiveMinutesAndRenews() {
        var t = 1_000L
        val w = AdbWatch(now = { t })
        assertFalse(w.isActive())
        assertNull(w.endsAt())
        assertEquals(1_000L + 5 * 60_000L, w.renew("Office PC"))
        assertTrue(w.isActive())
        t += 4 * 60_000L
        assertTrue(w.isActive())
        w.renew("Office PC") // renewed by each request
        t += 4 * 60_000L
        assertTrue(w.isActive("office pc"))
        t += 60_000L + 1
        assertFalse(w.isActive())
        assertNull(w.endsAt())
    }

    @Test
    fun watchIsPerComputer() {
        var t = 0L
        val w = AdbWatch(now = { t })
        w.renew("Office PC")
        t += 60_000L
        w.renew("Haitham’s MacBook")
        assertEquals(60_000L + AdbWatch.WINDOW_MS, w.endsAt())
        // watch:false from one computer doesn't stop the other's.
        w.stop("Office PC")
        assertTrue(w.isActive())
        assertFalse(w.isActive("Office PC"))
        w.stop("Haitham's MacBook") // curly vs straight apostrophe: same computer
        assertFalse(w.isActive())
    }

    @Test
    fun openWireNames() {
        assertEquals(AdbSetupOpen.DEVELOPER, AdbSetupOpen.fromWire("developer"))
        assertEquals(AdbSetupOpen.WIRELESS, AdbSetupOpen.fromWire(" wireless "))
        assertNull(AdbSetupOpen.fromWire(null))
        assertNull(AdbSetupOpen.fromWire(""))
        assertEquals("developer", AdbSetupOpen.DEVELOPER.wire)
        assertEquals("wireless", AdbSetupOpen.WIRELESS.wire)
    }
}
