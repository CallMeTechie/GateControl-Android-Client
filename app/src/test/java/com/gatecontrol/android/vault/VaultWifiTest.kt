package com.gatecontrol.android.vault

import com.gatecontrol.android.vault.VaultWifi.Raw
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultWifiTest {

    private fun sec(cap: String) = VaultWifi.securityOf(cap, "Offen")

    @Test
    fun `security follows the capabilities in the words the QR code understands`() {
        assertEquals("WPA2", sec("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA2", sec("[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA3", sec("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA2/WPA3", sec("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals("WPA", sec("[WPA-PSK-TKIP][ESS]"))
        assertEquals("WEP", sec("[WEP][ESS]"))
        assertEquals("WPA2-Enterprise", sec("[RSN-EAP/SHA1-CCMP][ESS]"))
        assertEquals("Offen", sec("[ESS]"))
        // Enhanced Open: encrypted, but no password to store
        assertEquals("Offen", sec("[RSN-OWE-CCMP][ESS]"))
        assertEquals("Open", VaultWifi.securityOf("", "Open"))
    }

    @Test
    fun `signal bars from dBm`() {
        assertEquals(listOf(4, 3, 2, 1, 0), listOf(-40, -60, -70, -85, -95).map(VaultWifi::level))
    }

    @Test
    fun `one row per name, hidden left out, connected first then by signal`() {
        val rows = VaultWifi.rows(
            listOf(
                Raw("Nachbar", "[WPA2-PSK-CCMP]", -80),
                Raw("Zuhause", "[RSN-PSK+SAE-CCMP]", -70),
                Raw("Zuhause", "[RSN-PSK+SAE-CCMP]", -50),
                Raw("", "[WPA2-PSK-CCMP]", -30),
                Raw("Café", "[ESS]", -60),
                Raw("Gast", "[WPA2-PSK-CCMP]", -60),
            ),
            connected = "Nachbar",
            open = "Offen",
        )
        assertEquals(listOf("Nachbar", "Zuhause", "Café", "Gast"), rows.map { it.ssid })
        assertEquals(listOf(true, false, false, false), rows.map { it.connected })
        // the strongest access point counts
        assertEquals(4, rows[1].level)
        assertEquals("WPA2/WPA3", rows[1].security)
        assertEquals("Offen", rows[2].security)
    }
}
