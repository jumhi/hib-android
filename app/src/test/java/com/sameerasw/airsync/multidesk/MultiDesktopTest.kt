package com.sameerasw.airsync.multidesk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDesktopTest {

    private fun desk(name: String, ips: List<String> = listOf("192.168.1.2"), key: String = "k", type: String? = null) =
        PairedDesktop(name = name, ips = ips, port = 6996, key = key, type = type, addedAt = 1L)

    // MARK: paired list

    @Test
    fun pairingANewComputerKeepsTheOldOne() {
        val mac = desk("Haitham's MacBook", type = "MacBook Pro")
        val pc = desk("Haitham's PC", type = "Windows PC")
        val list = PairedDesktops.upsert(PairedDesktops.upsert(emptyList(), mac), pc)
        assertEquals(listOf("Haitham's MacBook", "Haitham's PC"), list.map { it.name })
        assertEquals("mac", list[0].platform)
        assertEquals("windows", list[1].platform)
    }

    @Test
    fun rePairingSameNameMergesIpsAndReplacesKey() {
        val old = desk("PC", ips = listOf("192.168.1.5", "10.0.0.2"), key = "old", type = "Windows PC")
        val again = desk("pc ", ips = listOf("192.168.1.9"), key = "new", type = null)
        val list = PairedDesktops.upsert(listOf(old), again)
        assertEquals(1, list.size)
        assertEquals(listOf("192.168.1.9", "192.168.1.5", "10.0.0.2"), list[0].ips)
        assertEquals("new", list[0].key)
        assertEquals("Windows PC", list[0].type) // unknown type doesn't wipe the known one
    }

    @Test
    fun blankKeyKeepsExistingKey() {
        val list = PairedDesktops.upsert(listOf(desk("Mac", key = "secret")), desk("Mac", key = ""))
        assertEquals("secret", list[0].key)
    }

    @Test
    fun curlyApostropheNamesMatch() {
        assertTrue(PairedDesktops.sameName("Haitham’s Mac", "haitham's mac"))
        val list = PairedDesktops.remove(listOf(desk("Haitham’s Mac"), desk("PC")), "Haitham's Mac")
        assertEquals(listOf("PC"), list.map { it.name })
    }

    @Test
    fun ipListIsCapped() {
        val many = (1..20).map { "10.0.0.$it" }
        assertEquals(PairedDesktops.MAX_IPS, PairedDesktops.mergeIps(many, emptyList()).size)
    }

    // MARK: QR

    @Test
    fun parsesPairingQrWithSeveralIps() {
        val qr = PairingQr.parse("airsync://192.168.1.20,10.211.55.2:6996?name=Haitham%E2%80%99s%20PC?plus=true?key=q3J0b2tl+/=")
        assertNotNull(qr)
        qr!!
        assertEquals(listOf("192.168.1.20", "10.211.55.2"), qr.ips)
        assertEquals(6996, qr.port)
        assertEquals("Haitham’s PC", qr.name)
        assertTrue(qr.isPlus)
        assertEquals("q3J0b2tl+/=", qr.key)
    }

    @Test
    fun rejectsNonPairingQr() {
        assertNull(PairingQr.parse("https://example.com"))
        assertNull(PairingQr.parse("airsync://:6996?name=x"))
    }

    // MARK: routing

    @Test
    fun phoneStateGoesToEveryComputer() {
        for (t in listOf("notification", "status", "clipboardUpdate", "call_event")) {
            assertEquals(DesktopRouting.Route.All, DesktopRouting.decide(t, "PC", "Mac"))
        }
    }

    @Test
    fun synchronousRepliesGoBackToTheAsker() {
        assertEquals(DesktopRouting.Route.Only("PC"), DesktopRouting.decide("pong", "PC", null))
        assertEquals(DesktopRouting.Route.Only("PC"), DesktopRouting.decide("browseData", "PC", "Mac"))
    }

    @Test
    fun asyncRepliesGoToTheRecentRequester() {
        assertEquals(DesktopRouting.Route.Only("PC"), DesktopRouting.decide("approvalResponse", null, "PC"))
        assertEquals(DesktopRouting.Route.Primary, DesktopRouting.decide("approvalResponse", null, null))
    }

    @Test
    fun phoneInitiatedActionsGoToThePrimary() {
        assertEquals(DesktopRouting.Route.Primary, DesktopRouting.decide("remoteControl", null, "PC"))
        assertEquals(DesktopRouting.Route.Primary, DesktopRouting.decide("device", null, null))
    }

    @Test
    fun backgroundChatterIsNotARequest() {
        assertTrue(!DesktopRouting.countsAsRequest("ping"))
        assertTrue(!DesktopRouting.countsAsRequest("status"))
        assertTrue(DesktopRouting.countsAsRequest("approvalRequest"))
        assertTrue(DesktopRouting.countsAsRequest("desktopsRequest"))
    }

    @Test
    fun readsTypeFromTheHeadOnly() {
        val big = "{\"type\":\"fileChunk\",\"data\":{\"chunk\":\"" + "A".repeat(100_000) + "\"}}"
        assertEquals("fileChunk", DesktopRouting.messageType(big))
        assertEquals("ping", DesktopRouting.messageType("{\"type\": \"ping\",\"data\":{}}"))
        assertNull(DesktopRouting.messageType("{\"data\":{}}"))
    }

    // MARK: relay table

    @Test
    fun relayEntriesArePrunedAfterCompletionOrStall() {
        var now = 0L
        val t = RelayTable { now }
        t.start("a", "Mac", "PC", "a.pdf", 10)
        t.start("b", "PC", "Mac", "b.png", 10)
        t.markComplete("a")
        now = 61_000
        val dropped = t.prune(keepMs = 60_000, stallMs = 120_000)
        assertEquals(listOf("a"), dropped.map { it.id })
        assertEquals(1, t.size())
        now = 200_000
        assertEquals(listOf("b"), t.prune().map { it.id })
    }

    @Test
    fun relayLookupByPeer() {
        val t = RelayTable { 0L }
        t.start("a", "Mac", "PC", "a", 1)
        t.start("b", "Laptop", "Mac", "b", 1)
        t.start("c", "Laptop", "PC", "c", 1)
        assertEquals(setOf("a", "b"), t.involving("Mac").map { it.id }.toSet())
    }
}
