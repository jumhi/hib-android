package com.sameerasw.airsync.multidesk

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SpaceTest {

    private val mac = "Haitham’s MacBook"
    private val pc = "Office PC"

    private fun c(
        id: String,
        inUse: Boolean = false,
        lastActiveAt: Long = 0L,
        lastUsedAt: Long = 0L,
        isPrimary: Boolean = false
    ) = TargetRule.Candidate(id, inUse, lastActiveAt, lastUsedAt, isPrimary)

    // MARK: - target rule (SPACE.md §1.4)

    @Test
    fun noConnectedComputerMeansNoTarget() {
        assertNull(TargetRule.pick(emptyList(), explicit = mac, aimed = pc))
    }

    @Test
    fun explicitPickBeatsEverything() {
        val list = listOf(c(mac, inUse = true, lastActiveAt = 100, isPrimary = true), c(pc))
        val p = TargetRule.pick(list, explicit = pc, aimed = mac)!!
        assertEquals(pc, p.id)
        assertEquals(TargetRule.Reason.EXPLICIT, p.reason)
    }

    @Test
    fun explicitPickMatchesStraightApostrophe() {
        val p = TargetRule.pick(listOf(c(mac), c(pc)), explicit = "haitham's macbook")!!
        assertEquals(mac, p.id)
    }

    @Test
    fun explicitPickOfOfflineComputerFallsThrough() {
        val p = TargetRule.pick(listOf(c(mac, isPrimary = true)), explicit = "Gone PC")!!
        assertEquals(mac, p.id)
        assertEquals(TargetRule.Reason.PRIMARY, p.reason)
    }

    @Test
    fun aimBeatsInUse() {
        val list = listOf(c(mac, inUse = true, lastActiveAt = 100), c(pc))
        val p = TargetRule.pick(list, aimed = pc)!!
        assertEquals(pc, p.id)
        assertEquals(TargetRule.Reason.AIM, p.reason)
    }

    @Test
    fun inUseBeatsLastUsedAndPrimary() {
        val list = listOf(c(mac, lastUsedAt = 9_000, isPrimary = true), c(pc, inUse = true, lastActiveAt = 100))
        val p = TargetRule.pick(list)!!
        assertEquals(pc, p.id)
        assertEquals(TargetRule.Reason.IN_USE, p.reason)
    }

    @Test
    fun twoInUseNewestActivityWins() {
        val list = listOf(c(mac, inUse = true, lastActiveAt = 500), c(pc, inUse = true, lastActiveAt = 900))
        assertEquals(pc, TargetRule.pick(list)!!.id)
        assertEquals(pc, TargetRule.inUseId(list))
    }

    @Test
    fun inUseTieGoesToLowerId() {
        val list = listOf(c("b-pc", inUse = true, lastActiveAt = 500), c("a-mac", inUse = true, lastActiveAt = 500))
        assertEquals("a-mac", TargetRule.inUseId(list))
    }

    @Test
    fun lastUsedBeatsPrimary() {
        val list = listOf(c(mac, isPrimary = true, lastUsedAt = 10), c(pc, lastUsedAt = 20))
        val p = TargetRule.pick(list)!!
        assertEquals(pc, p.id)
        assertEquals(TargetRule.Reason.LAST_USED, p.reason)
    }

    @Test
    fun lastUsedCountsKeyboardActivityToo() {
        // Neither in use now, but the Mac was typed on more recently than the PC was flicked to.
        val list = listOf(c(mac, lastActiveAt = 5_000), c(pc, lastUsedAt = 4_000, isPrimary = true))
        assertEquals(mac, TargetRule.pick(list)!!.id)
    }

    @Test
    fun primaryIsTheLastResort() {
        val p = TargetRule.pick(listOf(c(pc), c(mac, isPrimary = true)))!!
        assertEquals(mac, p.id)
        assertEquals(TargetRule.Reason.PRIMARY, p.reason)
    }

    @Test
    fun withoutPrimaryLowestIdWins() {
        assertEquals("Alpha", TargetRule.pick(listOf(c("beta"), c("Alpha")))!!.id)
    }

    @Test
    fun idsCompareByUtf8Bytes() {
        assertTrue(compareSpaceIds("Z", "a") < 0)   // uppercase sorts first
        assertTrue(compareSpaceIds("z", "é") < 0)   // non-ASCII after ASCII
        assertTrue(compareSpaceIds("PC", "PC 2") < 0)
        assertEquals(0, compareSpaceIds(mac, mac))
    }

    // MARK: - desktopActivity (SPACE.md §3.1)

    @Test
    fun activityComputesLastActiveFromIdleSeconds() {
        val a = DesktopActivityInfo.from(inUse = true, idleSeconds = 4, now = 100_000)
        assertEquals(96_000, a.lastActiveAt)
        assertTrue(a.isFreshInUse(100_000 + 60_000))
        assertFalse(a.isFreshInUse(100_000 + DesktopActivityInfo.STALE_AFTER_MS + 1))
        assertEquals(14, a.idleSecondsAt(110_000))
    }

    @Test
    fun unknownIdleStaysUnknown() {
        val a = DesktopActivityInfo.from(inUse = false, idleSeconds = -1, now = 50_000)
        assertEquals(0, a.lastActiveAt)
        assertEquals(-1, a.idleSecondsAt(90_000))
        assertFalse(a.isFreshInUse(50_000))
    }

    // MARK: - Flick sectors (SPACE.md §4)

    @Test
    fun oneBubbleTakesEveryUpwardDrag() {
        assertEquals(0, FlickSectors.sectorFor(-300f, -50f, 1))
        assertEquals(0, FlickSectors.sectorFor(0f, -200f, 1))
        assertNull(FlickSectors.sectorFor(0f, 200f, 1)) // downward
    }

    @Test
    fun twoBubblesSplitLeftAndRight() {
        assertEquals(0, FlickSectors.sectorFor(-200f, -200f, 2))  // up-left
        assertEquals(1, FlickSectors.sectorFor(200f, -200f, 2))   // up-right
        assertEquals(0, FlickSectors.sectorFor(-300f, -1f, 2))    // nearly flat left
        assertEquals(1, FlickSectors.sectorFor(300f, -1f, 2))
    }

    @Test
    fun straightUpMeansTheDefault() {
        assertNull(FlickSectors.sectorFor(0f, -400f, 2))
        assertNull(FlickSectors.sectorFor(30f, -400f, 2))  // ~4° off vertical
        assertNull(FlickSectors.sectorFor(-30f, -400f, 3))
    }

    @Test
    fun threeBubblesSplitIntoSixtyDegreeSectors() {
        fun at(deg: Double): Int? {
            val r = Math.toRadians(deg)
            return FlickSectors.sectorFor((Math.cos(r) * 100).toFloat(), (-Math.sin(r) * 100).toFloat(), 3)
        }
        assertEquals(2, at(10.0))    // right
        assertEquals(2, at(55.0))
        assertEquals(1, at(65.0))    // middle, outside the straight-up cone
        assertEquals(1, at(115.0))
        assertEquals(0, at(125.0))   // left
        assertEquals(0, at(175.0))
    }

    @Test
    fun shortDragsDoNotPointYet() {
        assertNull(FlickSectors.sectorFor(-10f, -10f, 2, minDistance = 50f))
        assertEquals(0, FlickSectors.sectorFor(-60f, -60f, 2, minDistance = 50f))
    }

    @Test
    fun sectorCentresMapBackToTheirBubble() {
        for (n in 2..5) {
            for (i in 0 until n) {
                val deg = FlickSectors.centreAngle(i, n)
                if (kotlin.math.abs(deg - 90.0) < FlickSectors.STRAIGHT_UP_CONE_DEG) continue
                val r = Math.toRadians(deg)
                assertEquals("n=$n i=$i", i, FlickSectors.sectorFor((Math.cos(r) * 100).toFloat(), (-Math.sin(r) * 100).toFloat(), n))
            }
        }
    }

    @Test
    fun noBubblesNoSector() {
        assertNull(FlickSectors.sectorFor(-100f, -100f, 0))
    }

    // MARK: - aim

    @Test
    fun aimAngleHandlesNorthWraparound() {
        val a = AimMatcher.angleBetween(AimMatcher.Aim(359f, 0f), AimMatcher.Aim(1f, 0f))
        assertEquals(2.0, a, 0.01)
        assertEquals(90.0, AimMatcher.angleBetween(AimMatcher.Aim(0f, 0f), AimMatcher.Aim(0f, 90f)), 0.01)
    }

    @Test
    fun aimNeedsTwoTaughtComputers() {
        val one = mapOf(mac to AimMatcher.Aim(10f, 0f))
        assertNull(AimMatcher.nearest(AimMatcher.Aim(10f, 0f), one))
    }

    @Test
    fun aimPicksNearestWithin35Degrees() {
        val taught = mapOf(mac to AimMatcher.Aim(10f, 0f), pc to AimMatcher.Aim(80f, 5f))
        assertEquals(mac, AimMatcher.nearest(AimMatcher.Aim(25f, 0f), taught))
        assertEquals(pc, AimMatcher.nearest(AimMatcher.Aim(70f, 0f), taught))
        assertNull(AimMatcher.nearest(AimMatcher.Aim(200f, 0f), taught)) // pointing away
        assertNull(AimMatcher.nearest(null, taught))
    }

    @Test
    fun aimOnlyLightsConnectedComputers() {
        val taught = mapOf(mac to AimMatcher.Aim(10f, 0f), pc to AimMatcher.Aim(30f, 0f))
        assertEquals(pc, AimMatcher.nearest(AimMatcher.Aim(12f, 0f), taught, eligible = listOf("office pc")))
    }

    @Test
    fun aimStoreRoundTrips() {
        val map = mapOf(mac to AimMatcher.Aim(214.5f, 8.25f), pc to AimMatcher.Aim(10f, -3f))
        assertEquals(map, AimMatcher.decode(AimMatcher.encode(map)))
        assertTrue(AimMatcher.decode("not json").isEmpty())
        assertTrue(AimMatcher.decode(null).isEmpty())
    }

    // MARK: - Play on… exclusivity (SPACE.md §3.5)

    @Test
    fun playOnStartsTargetAndStopsOthers() {
        val r = PlayExclusivity.playOn(pc, listOf(mac, pc, "Den PC"))
        assertEquals(pc, r.playingOn)
        assertEquals(
            listOf(
                PlayExclusivity.Command(pc, "start"),
                PlayExclusivity.Command(mac, "stop"),
                PlayExclusivity.Command("Den PC", "stop")
            ),
            r.commands
        )
    }

    @Test
    fun computerPlayHereStopsTheOthers() {
        val r = PlayExclusivity.onState(mac, active = true, playingOn = pc, connected = listOf(mac, pc))
        assertEquals(mac, r.playingOn)
        assertEquals(listOf(PlayExclusivity.Command(pc, "stop")), r.commands)
    }

    @Test
    fun inactiveFromThePlayingComputerClears() {
        val r = PlayExclusivity.onState("haitham's macbook", active = false, playingOn = mac, connected = listOf(mac, pc))
        assertNull(r.playingOn)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun inactiveFromAnotherComputerChangesNothing() {
        // The PC confirms it stopped after the phone started the Mac.
        val r = PlayExclusivity.onState(pc, active = false, playingOn = mac, connected = listOf(mac, pc))
        assertEquals(mac, r.playingOn)
        assertTrue(r.commands.isEmpty())
    }

    @Test
    fun toggleStopsWhenPlayingAndStartsTargetOtherwise() {
        val stop = PlayExclusivity.toggle(playingOn = mac, target = pc, connected = listOf(mac, pc))
        assertNull(stop.playingOn)
        assertEquals(setOf("stop"), stop.commands.map { it.action }.toSet())
        assertEquals(2, stop.commands.size)

        val start = PlayExclusivity.toggle(playingOn = null, target = pc, connected = listOf(mac, pc))
        assertEquals(pc, start.playingOn)
        assertEquals(PlayExclusivity.Command(pc, "start"), start.commands.first())

        val nothing = PlayExclusivity.toggle(playingOn = null, target = null, connected = emptyList())
        assertNull(nothing.playingOn)
        assertTrue(nothing.commands.isEmpty())
    }

    @Test
    fun droppedComputerStopsPlaying() {
        assertNull(PlayExclusivity.onDisconnected(mac, mac))
        assertEquals(pc, PlayExclusivity.onDisconnected(mac, pc))
    }

    // MARK: - desktopsList (SPACE.md §3.2)

    private fun entry(id: String, platform: String, connected: Boolean, inUse: Boolean = false, ips: List<String> = emptyList()) =
        SpaceJson.DesktopEntry(
            id = id, name = id, platform = platform,
            type = if (platform == "windows") "Windows PC" else "MacBook Pro",
            connected = connected, inUse = inUse, idleSeconds = if (inUse) 4 else -1, ips = ips, port = 6996
        )

    @Test
    fun desktopsListCarriesTheSpaceFields() {
        val key = SpaceKey.generate()
        val json = SpaceJson.desktopsList(
            you = pc,
            phone = SpaceJson.PhoneInfo("Haitham's S26", "SM-S948B", 80, false),
            spaceKey = key,
            inUse = mac,
            playingOn = null,
            desktops = listOf(
                entry(mac, "mac", connected = true, inUse = true, ips = listOf("10.107.0.216", "192.168.1.4")),
                entry(pc, "windows", connected = true)
            )
        )
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals("desktopsList", root.get("type").asString)
        val d = root.getAsJsonObject("data")
        assertEquals(pc, d.get("you").asString)
        val phone = d.getAsJsonObject("phone")
        assertEquals("phone", phone.get("id").asString)
        assertEquals("Haitham's S26", phone.get("name").asString)
        assertEquals("SM-S948B", phone.get("model").asString)
        assertEquals(80, phone.get("battery").asInt)
        assertFalse(phone.get("charging").asBoolean)
        val space = d.getAsJsonObject("space")
        assertEquals(key, space.get("key").asString)
        assertEquals(1, space.get("v").asInt)
        assertEquals(mac, d.get("inUse").asString)
        assertTrue(d.has("playingOn") && d.get("playingOn").isJsonNull)

        val desktops = d.getAsJsonArray("desktops")
        assertEquals(1, desktops.size()) // the recipient is never listed
        val m = desktops[0].asJsonObject
        assertEquals(mac, m.get("id").asString)
        assertEquals(mac, m.get("name").asString)
        assertEquals("mac", m.get("platform").asString)
        assertEquals("MacBook Pro", m.get("type").asString)
        assertTrue(m.get("connected").asBoolean)
        assertTrue(m.get("inUse").asBoolean)
        assertEquals(4, m.get("idleSeconds").asInt)
        assertEquals(listOf("10.107.0.216", "192.168.1.4"), m.getAsJsonArray("ips").map { it.asString })
        assertEquals(6996, m.get("port").asInt)
    }

    @Test
    fun desktopsListExcludesRecipientByNormalisedName() {
        val json = SpaceJson.desktopsList(
            you = "haitham's macbook",
            phone = SpaceJson.PhoneInfo("Phone", null, null, null),
            spaceKey = null,
            inUse = null,
            playingOn = pc,
            desktops = listOf(entry(mac, "mac", true), entry(pc, "windows", false))
        )
        val d = JsonParser.parseString(json).asJsonObject.getAsJsonObject("data")
        val ids = d.getAsJsonArray("desktops").map { it.asJsonObject.get("id").asString }
        assertEquals(listOf(pc), ids)
        assertFalse(d.has("space")) // not loaded yet: the computer keeps the key it has
        assertTrue(d.get("inUse").isJsonNull)
        assertEquals(pc, d.get("playingOn").asString)
        assertFalse(d.getAsJsonObject("phone").has("battery"))
    }

    @Test
    fun spaceKeyIs32RandomBytes() {
        val a = SpaceKey.generate()
        val b = SpaceKey.generate()
        assertEquals(32, Base64.getDecoder().decode(a).size)
        assertNotEquals(a, b)
        assertTrue(SpaceKey.isValid(a))
        assertFalse(SpaceKey.isValid("c2hvcnQ="))
        assertFalse(SpaceKey.isValid(null))
        assertFalse(SpaceKey.isValid("%%%"))
    }
}
