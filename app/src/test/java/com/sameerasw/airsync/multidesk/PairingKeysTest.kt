package com.sameerasw.airsync.multidesk

import com.sameerasw.airsync.domain.model.ConnectedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone must never use one computer's pairing key for another (the PC's key ended up on the Mac). */
class PairingKeysTest {
    private val mac = "‏⁨⁨MacBook Pro⁩⁩ ⁨haitham⁩‏"
    private val pcRecord = ConnectedDevice(
        name = "PC-INTERN", ipAddress = "10.102.1.172", port = "6996",
        lastConnected = 1L, isPlus = true, symmetricKey = "PC-KEY", model = "WindowsPC", deviceType = "Windows PC"
    )

    @Test
    fun handshake_record_takes_the_key_and_address_the_socket_actually_used() {
        // The Mac answered on the main link, but the saved "last computer" was still the PC.
        val r = PairingKeys.handshakeRecord(pcRecord, mac, socketKey = "MAC-KEY", socketIp = "10.107.0.216", socketPort = 6996)
        assertEquals(mac, r.name)
        assertEquals("MAC-KEY", r.symmetricKey)
        assertEquals("10.107.0.216", r.ipAddress)
        assertEquals("6996", r.port)
    }

    @Test
    fun handshake_record_keeps_the_saved_values_when_the_socket_has_none() {
        val r = PairingKeys.handshakeRecord(pcRecord, "", socketKey = null, socketIp = null, socketPort = null)
        assertEquals("PC-INTERN", r.name)
        assertEquals("PC-KEY", r.symmetricKey)
        assertEquals("10.102.1.172", r.ipAddress)
    }

    @Test
    fun handshake_record_ignores_a_list_of_addresses_still_being_tried() {
        val r = PairingKeys.handshakeRecord(pcRecord, mac, socketKey = "MAC-KEY", socketIp = "10.107.0.216,192.168.1.5", socketPort = 6996)
        assertEquals("10.102.1.172", r.ipAddress)
        assertEquals("MAC-KEY", r.symmetricKey)
    }

    @Test
    fun verified_key_comes_from_the_paired_computers_list_by_name() {
        val paired = listOf(
            PairedDesktop(name = "PC-INTERN", ips = listOf("10.102.1.172"), port = 6996, key = "PC-KEY"),
            PairedDesktop(name = mac, ips = listOf("10.107.0.216"), port = 6996, key = "MAC-KEY"),
        )
        assertEquals("MAC-KEY", PairingKeys.verifiedKey(paired, mac))
        assertEquals("PC-KEY", PairingKeys.verifiedKey(paired, "pc-intern"))
        assertNull(PairingKeys.verifiedKey(paired, "Someone else"))
        assertNull(PairingKeys.verifiedKey(listOf(PairedDesktop(name = mac, ips = emptyList(), port = 6996, key = "")), mac))
    }
}
