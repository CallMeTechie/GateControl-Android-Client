package com.gatecontrol.android.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Push pictures are only ever fetched from the own server's /notify-media/. */
class NotifyMediaUrlsTest {

    private val server = "https://gc.example.com"
    private val path = "/notify-media/AbCdEfGhIjKlMnOpQrStUvWx.jpg"

    @Test
    fun `a notify-media path resolves against the own server`() {
        assertEquals("https://gc.example.com$path", NotifyMediaUrls.resolve(server, path))
        assertEquals("https://gc.example.com$path", NotifyMediaUrls.resolve("$server/", path))
        assertEquals("https://gc.example.com:8443$path", NotifyMediaUrls.resolve("https://gc.example.com:8443", path))
    }

    @Test
    fun `an absolute address on the same server is accepted`() {
        assertEquals("https://gc.example.com$path", NotifyMediaUrls.resolve(server, "https://gc.example.com$path"))
        assertEquals("https://gc.example.com:443$path", NotifyMediaUrls.resolve(server, "https://gc.example.com:443$path"))
    }

    @Test
    fun `other hosts, ports, schemes and paths are refused`() {
        assertNull(NotifyMediaUrls.resolve(server, "https://evil.example$path"))
        assertNull(NotifyMediaUrls.resolve(server, "//evil.example$path"))
        assertNull(NotifyMediaUrls.resolve(server, "http://gc.example.com$path"))
        assertNull(NotifyMediaUrls.resolve(server, "https://gc.example.com:8443$path"))
        assertNull(NotifyMediaUrls.resolve(server, "/avatars/AbCdEfGhIjKlMnOp.webp"))
        assertNull(NotifyMediaUrls.resolve(server, "/api/v1/client/me/profile"))
        assertNull(NotifyMediaUrls.resolve(server, "/notify-media/../api/x.jpg"))
        assertNull(NotifyMediaUrls.resolve(server, "https://gc.example.com$path?x=1"))
        assertNull(NotifyMediaUrls.resolve(server, "file:///sdcard/a.jpg"))
        assertNull(NotifyMediaUrls.resolve(server, null))
        assertNull(NotifyMediaUrls.resolve(server, ""))
        assertNull(NotifyMediaUrls.resolve("", path))
    }
}
