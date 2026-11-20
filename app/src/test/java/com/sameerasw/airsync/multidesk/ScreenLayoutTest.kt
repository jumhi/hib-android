package com.sameerasw.airsync.multidesk

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `screenLayout` (SPACE.md §9.2): the message and "send only on change" per computer. */
class ScreenLayoutTest {

    private val mac = "Haitham’s MacBook"
    private val pc = "Office PC"

    @Test
    fun buildsScreenLayoutEnvelope() {
        val json = PointJson.screenLayout(
            listOf(
                SavedScreenAim(mac, "apple-1", "Built-in Retina Display", 214.0f, 8.5f),
                SavedScreenAim(pc, "", "", 120.25f, -3f)
            )
        )
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("screenLayout", root.get("type").asString)
        assertEquals("screenLayout", DesktopRouting.messageType(json))
        val screens = root.getAsJsonObject("data").getAsJsonArray("screens")
        assertEquals(2, screens.size())
        val first = screens[0].asJsonObject
        assertEquals(setOf("computer", "screen", "h", "e"), first.keySet())
        assertEquals(mac, first.get("computer").asString)
        assertEquals("apple-1", first.get("screen").asString)
        assertEquals(214.0, first.get("h").asDouble, 1e-6)
        assertEquals(8.5, first.get("e").asDouble, 1e-6)
        val second = screens[1].asJsonObject
        assertEquals(pc, second.get("computer").asString)
        assertEquals("", second.get("screen").asString)
        assertEquals(120.25, second.get("h").asDouble, 1e-6)
        assertEquals(-3.0, second.get("e").asDouble, 1e-6)
    }

    @Test
    fun emptyMapStillSendsAnEmptyList() {
        // "Forget all screens" must reach the computers too.
        val root = JsonParser.parseString(PointJson.screenLayout(emptyList())).asJsonObject
        assertEquals(0, root.getAsJsonObject("data").getAsJsonArray("screens").size())
    }

    @Test
    fun goesToEveryComputerWhenRouted() {
        assertEquals(DesktopRouting.Route.All, DesktopRouting.decide("screenLayout", pc, mac))
        assertEquals(DesktopRouting.Route.All, DesktopRouting.decide("screenLayout", null, null))
    }

    @Test
    fun ledgerSendsOnlyOnChange() {
        val ledger = LayoutLedger()
        // Nothing before the map has loaded.
        assertNull(ledger.pending(mac))

        val a = PointJson.screenLayout(listOf(SavedScreenAim(mac, "apple-1", "", 214f, 8.5f)))
        ledger.set(a)
        assertEquals(a, ledger.pending(mac))
        assertEquals(a, ledger.pending(pc))
        ledger.record(mac, a)
        assertNull(ledger.pending(mac))
        // Names match like everywhere else (case, curly apostrophe).
        assertNull(ledger.pending("haitham's macbook"))
        assertEquals(a, ledger.pending(pc))

        // The same map again (e.g. re-emitted) is not resent.
        ledger.set(a)
        assertNull(ledger.pending(mac))

        // Point setup or "Forget all screens": everyone needs the new one.
        val b = PointJson.screenLayout(emptyList())
        ledger.set(b)
        assertEquals(b, ledger.pending(mac))
        ledger.record(mac, b)
        assertNull(ledger.pending(mac))
    }

    @Test
    fun ledgerResendsAfterAReconnect() {
        val ledger = LayoutLedger()
        val a = PointJson.screenLayout(listOf(SavedScreenAim(pc, "\\\\.\\DISPLAY1", "", 90f, 0f)))
        ledger.set(a)
        ledger.record(pc, a)
        assertNull(ledger.pending(pc))
        ledger.forget(pc)
        assertEquals(a, ledger.pending(pc))
    }
}
