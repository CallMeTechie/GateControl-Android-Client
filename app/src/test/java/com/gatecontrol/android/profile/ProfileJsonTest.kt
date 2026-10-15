package com.gatecontrol.android.profile

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProfileJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val full = """
        { "ok": true,
          "user": { "id": 7, "name": "Anna Müller", "initials": "AM", "color": "green", "colorHex": "#22C55E",
                    "avatarUrl": "/avatars/AbCdEfGhIjKlMnOpQrStUv12.webp" },
          "palette": [ {"key":"blue","hex":"#3B82F6"}, {"key":"green","hex":"#22C55E"}, {"key":"bad"}, {"key":"red","hex":"#E5484D"} ] }
    """

    @Test
    fun `profile as the contract sends it`() {
        val p = ProfileJson.parse(json(full))!!
        assertEquals(7, p.id)
        assertEquals("Anna Müller", p.name)
        assertEquals("AM", p.initials)
        assertEquals("green", p.color)
        assertEquals("#22C55E", p.colorHex)
        assertEquals("/avatars/AbCdEfGhIjKlMnOpQrStUv12.webp", p.avatarUrl)
        assertEquals(listOf("blue", "green", "red"), p.palette.map { it.key })
        assertEquals(listOf("blue", "green"), AvatarPalette.selectable(p.palette).map { it.key })
    }

    @Test
    fun `missing pieces fall back, broken answers give no profile`() {
        val p = ProfileJson.parse(json("""{"ok":true,"user":{"id":3,"name":"Tim","avatarUrl":null}}"""))!!
        assertEquals("TI", p.initials)
        assertEquals("lime", p.color, "default palette[id % 10]")
        assertEquals("#84CC16", p.colorHex)
        assertNull(p.avatarUrl)
        assertNull(ProfileJson.parse(json("""{"ok":true,"user":{"name":"no id"}}""")))
        assertNull(ProfileJson.parse(json("""{"ok":false,"code":"NO_USER"}""")))
        assertNull(ProfileJson.parse(json("""{"ok":true}""")))
    }

    @Test
    fun `avatar urls must be capability paths`() {
        assertNotNull(ProfileJson.avatarUrl("/avatars/AbCdEfGhIjKlMnOpQrStUv.png"))
        assertNotNull(ProfileJson.avatarUrl("https://gc.example.com/avatars/AbCdEfGhIjKlMnOpQrStUv.jpg"))
        assertNull(ProfileJson.avatarUrl("/avatars/short.png"))
        assertNull(ProfileJson.avatarUrl("/avatars/AbCdEfGhIjKlMnOpQrStUv.svg"))
        assertNull(ProfileJson.avatarUrl("/api/v1/users/1"))
        assertNull(ProfileJson.avatarUrl("javascript:alert(1)"))
        assertNull(ProfileJson.avatarUrl("https://gc.example.com/avatars/AbCdEfGhIjKlMnOpQrStUv.png?x=1"))
        assertNull(ProfileJson.avatarUrl("/avatars/../AbCdEfGhIjKlMnOpQrStUv.png"))
    }

    @Test
    fun `cached copy belongs to one login`() {
        val p = ProfileJson.parse(json(full))!!
        val raw = ProfileJson.encode(p, "owner-a")
        assertEquals(p, ProfileJson.decode(raw, "owner-a"))
        assertNull(ProfileJson.decode(raw, "owner-b"))
        assertNull(ProfileJson.decode("not json", "owner-a"))
        assertNull(ProfileJson.decode(null, "owner-a"))
    }

    @Test
    fun `avatar addresses resolve only against the own server`() {
        val path = "/avatars/AbCdEfGhIjKlMnOpQrStUv.webp"
        assertEquals("https://gc.example.com$path", AvatarUrls.resolve("https://gc.example.com/", path))
        assertEquals("https://gc.example.com/gc$path", AvatarUrls.resolve("https://gc.example.com/gc", path))
        assertEquals(
            "https://GC.example.com:443$path",
            AvatarUrls.resolve("https://gc.example.com", "https://GC.example.com:443$path"),
        )
        assertNull(AvatarUrls.resolve("https://gc.example.com", "https://evil.example.org$path"))
        assertNull(AvatarUrls.resolve("https://gc.example.com", "http://gc.example.com$path"))
        assertNull(AvatarUrls.resolve("https://gc.example.com", "//evil.example.org$path"))
        assertNull(AvatarUrls.resolve("", path))
        assertNull(AvatarUrls.resolve("https://gc.example.com", null))
    }

    @Test
    fun `error answers map to what the app shows`() {
        assertEquals(ProfileRepository.CODE_TOO_LARGE, ProfileRepository.codeOf(413, null))
        assertEquals(ProfileRepository.CODE_RATE_LIMITED, ProfileRepository.codeOf(429, null))
        assertEquals(ProfileRepository.CODE_NO_USER, ProfileRepository.codeOf(403, "NO_USER"))
        assertEquals(ProfileRepository.CODE_INVALID, ProfileRepository.codeOf(400, "INVALID_IMAGE"))
        assertEquals(ProfileRepository.CODE_UNSUPPORTED, ProfileRepository.codeOf(404, null))
        assertEquals(ProfileRepository.CODE_FAILED, ProfileRepository.codeOf(500, null))
        assertEquals(ProfileRepository.CODE_UNSUPPORTED_TYPE, ProfileRepository.codeOf(415, "UNSUPPORTED_TYPE"))
        assertEquals(ProfileRepository.CODE_INVALID_COLOR, ProfileRepository.codeOf(400, "INVALID_COLOR"))
        assertEquals(ProfileRepository.CODE_RATE_LIMITED, ProfileRepository.codeOf(429, "RATE_LIMITED"))
    }
}
