package com.gatecontrol.android.vault

import com.google.gson.JsonParser
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Wi-Fi QR code and the share copy (plugin gatecontrol-vault 1.4.0): the same rules as the portal tab
 * (VaultLogic.wifiOf) and client-core (utils/vault.js wifiOf), the QR code readable again, the server's answers.
 */
class VaultWifiQrTest {

    private fun wifi(vararg f: Pair<String, String>) = EntryData(type = "wifi", title = "WLAN", fields = mapOf(*f))
    private val bs = "\\"

    @Test
    fun `security, password and hidden like every other app`() {
        assertEquals(VaultWifiQr.Wifi("S", "WPA", "p", false), VaultWifiQr.of(wifi("ssid" to "S", "password" to "p", "security" to "WPA2")))
        assertEquals("SAE", VaultWifiQr.of(wifi("ssid" to "S", "password" to "p", "security" to "WPA3"))!!.security)
        assertEquals("WPA", VaultWifiQr.of(wifi("ssid" to "S", "password" to "p", "security" to "WPA2/WPA3"))!!.security)
        assertEquals("WEP", VaultWifiQr.of(wifi("ssid" to "S", "password" to "p", "security" to "WEP"))!!.security)
        assertEquals(VaultWifiQr.Wifi("S", "nopass", "", false), VaultWifiQr.of(wifi("ssid" to "S", "password" to "p", "security" to "Offen")))
        assertEquals("nopass", VaultWifiQr.of(wifi("ssid" to "S"))!!.security)
        assertTrue(VaultWifiQr.of(wifi("ssid" to "S", "hidden" to "ja"))!!.hidden)
        assertNull(VaultWifiQr.of(wifi("password" to "p")), "no network name")
    }

    @Test
    fun `the editor's hidden network switch is stored as true and dropped when off`() {
        assertTrue(listOf("true", "TRUE", "1", "ja", "yes", " yes ").all(VaultWifiQr::hiddenOn))
        assertFalse(listOf("", "false", "0", "nein").any(VaultWifiQr::hiddenOn))
        val on = wifi("ssid" to "Keller-AP", "password" to "geheim", "hidden" to VaultWifiQr.hiddenValue(true))
        assertEquals("WIFI:T:WPA;S:Keller-AP;P:geheim;H:true;;", VaultWifiQr.text(on))
        val off = on.copy(fields = on.fields + ("hidden" to VaultWifiQr.hiddenValue(false)))
        assertFalse(JsonParser.parseString(off.toJson()).asJsonObject.getAsJsonObject("fields").has("hidden"), "an empty field is not saved")
        assertEquals("true", JsonParser.parseString(on.toJson()).asJsonObject.getAsJsonObject("fields").get("hidden").asString)
        assertEquals("WIFI:T:WPA;S:Keller-AP;P:geheim;;", VaultWifiQr.text(off))
    }

    @Test
    fun `the text escapes backslash, semicolon, comma, colon and quote`() {
        assertEquals("WIFI:T:WPA;S:Gäste${bs};WLAN;P:Korrekt${bs}:Pferd;;", VaultWifiQr.text(wifi("ssid" to "Gäste;WLAN", "password" to "Korrekt:Pferd", "security" to "WPA2")))
        assertEquals(
            "WIFI:T:nopass;S:a${bs}\"b${bs},c$bs$bs;H:true;;",
            VaultWifiQr.text(VaultWifiQr.Wifi("a\"b,c$bs", "nopass", "", true)),
        )
        assertNull(VaultWifiQr.text(wifi("password" to "p")))
    }

    @Test
    fun `the QR code reads back as the text`() {
        val text = VaultWifiQr.text(wifi("ssid" to "Backes-5G Grüße", "password" to "Korrekt Pferd:Batterie;", "security" to "WPA2"))!!
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val got = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(m.width, m.height, px))), mapOf(DecodeHintType.CHARACTER_SET to "UTF-8"))
        assertEquals(text, got.text)
    }

    @Test
    fun `the server's shares, what is still the same, what a writer may start`() {
        val share = """{"id":"${"a".repeat(24)}","itemId":"Aa1111111111111111111a","vaultId":"Vt9bJm0n2QwErTyUiOpAsD","target":"wall","ssid":"Gäste;WLAN",
            "security":"WPA","password":"Korrekt:Pferd","hidden":false,"expiresAt":"2026-10-24T10:00:00.000Z","createdBy":{"id":2,"name":"Anna"},"canEnd":true}"""
        val o = JsonParser.parseString("""{"ok":true,"shares":[$share,{"id":"x"},{"id":"${"b".repeat(24)}","itemId":"Aa1111111111111111111a","target":"tv","ssid":"x","security":"WPA"}],"targets":{"wall":true,"portal":true}}""").asJsonObject
        val r = VaultJson.wifiShares(o)!!
        assertEquals(1, r.shares.size)
        val s = r.shares[0]
        assertEquals(listOf("wall", "Gäste;WLAN", "WPA", "Korrekt:Pferd", "Anna"), listOf(s.target, s.ssid, s.security, s.password, s.createdBy))
        assertTrue(s.canEnd)
        assertTrue(r.wall)
        val entry = wifi("ssid" to "Gäste;WLAN", "password" to "Korrekt:Pferd", "security" to "WPA2")
        assertTrue(VaultWifiQr.same(entry, s))
        assertFalse(VaultWifiQr.same(wifi("ssid" to "Gäste;WLAN", "password" to "neu", "security" to "WPA2"), s))
        assertEquals(listOf("portal"), VaultWifiQr.starts(r, writable = true))
        assertEquals(listOf("portal"), VaultWifiQr.starts(VWifiShares(emptyList(), wall = false, portal = true), writable = true))
        assertEquals(listOf("wall", "portal"), VaultWifiQr.starts(VWifiShares(emptyList(), wall = true, portal = true), writable = true))
        assertEquals(emptyList<String>(), VaultWifiQr.starts(r, writable = false))
        assertNull(VaultJson.wifiShares(JsonParser.parseString("""{"ok":true}""").asJsonObject))
        val body = VaultJson.wifiShareBody("Aa1111111111111111111a", "portal", "5m", VaultWifiQr.of(entry)!!)
        assertEquals("""{"itemId":"Aa1111111111111111111a","target":"portal","duration":"5m","ssid":"Gäste;WLAN","security":"WPA","password":"Korrekt:Pferd","hidden":false}""", body.toString())
    }
}
