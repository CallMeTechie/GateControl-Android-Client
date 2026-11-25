package com.gatecontrol.android.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Jellyfin and WebDAV run on plain HTTP in the home network: without a network security config Android refuses every
 * http:// request of OkHttp and ExoPlayer ("CLEARTEXT communication … not permitted"), and Medien › Quellen found no
 * server at all. The config allows it; the code limits it to the home network ([MediaAddress]).
 */
class PhoneMediaNetworkPolicyTest {

    private fun file(path: String): File = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }

    @Test
    fun `the app declares its network security config`() {
        assertTrue(file("AndroidManifest.xml").readText().contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
    }

    @Test
    fun `cleartext is allowed, only the system's certificate authorities are trusted`() {
        val xml = file("res/xml/network_security_config.xml").readText()
        assertTrue(xml.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(xml.contains("<certificates src=\"system\" />"))
        assertFalse(xml.contains("src=\"user\""), "no user CAs")
    }

    @Test
    fun `plain http only to a host of the home network`() {
        assertEquals("http://192.168.2.151:8096", MediaAddress.normalize("http://192.168.2.151:8096"))
        assertNull(MediaAddress.normalize("http://media.example.com:8096"))
        assertNull(MediaAddress.normalizeWebDav("http://media.example.com/filme"))
    }
}
