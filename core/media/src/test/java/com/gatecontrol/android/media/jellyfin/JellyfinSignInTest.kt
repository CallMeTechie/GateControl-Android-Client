package com.gatecontrol.android.media.jellyfin

import com.gatecontrol.android.media.MediaClientInfo
import com.gatecontrol.android.media.MediaResult
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Signing in at a Jellyfin source of the server: the server, Quick Connect (the code goes to the plugin), the header. */
class JellyfinSignInTest {

    private lateinit var server: MockWebServer
    private lateinit var signIn: JellyfinSignIn

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        signIn = JellyfinSignIn(server.url("/").toString(), OkHttpClient(), MediaClientInfo("GateControl TV", "Fire TV", "dev-1", "1.0.0"))
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private val authAnswer = """{"AccessToken":"tok","ServerId":"srv1","User":{"Id":"u1","Name":"Anna"}}"""

    @Test
    fun `the server's public info`() = runTest {
        server.enqueue(json("""{"ServerName":"DS918+","Version":"10.10.3","Id":"srv1","StartupWizardCompleted":true}"""))
        val s = (signIn.server() as MediaResult.Ok).value
        assertEquals("DS918+", s.name)
        assertTrue(s.supported)
        assertEquals("/System/Info/Public", server.takeRequest().requestUrl!!.encodedPath)
        server.enqueue(json("""{"ServerName":"Alt","Version":"10.8.13","Id":"srv2"}"""))
        assertFalse((signIn.server() as MediaResult.Ok).value.supported)
        server.enqueue(json("""{"nope":1}"""))
        assertTrue(signIn.server() is MediaResult.Failed)
    }

    @Test
    fun `Quick Connect - code, wait, signed in`() = runTest {
        server.enqueue(json("true"))
        assertTrue(signIn.quickConnectEnabled())
        server.enqueue(json("""{"Secret":"sec","Code":"482913","Authenticated":false}"""))
        val q = (signIn.quickConnect() as MediaResult.Ok).value
        assertEquals("482913", q.code)
        assertFalse(q.toString().contains("sec"))
        server.enqueue(json("""{"Authenticated":false}"""))
        assertEquals(false, signIn.confirmed(q).valueOrNull())
        server.enqueue(json("""{"Authenticated":true}"""))
        assertEquals(true, signIn.confirmed(q).valueOrNull())
        server.enqueue(json(authAnswer))
        val auth = (signIn.signIn(q) as MediaResult.Ok).value
        assertEquals("tok", auth.token)
        assertEquals("u1", auth.userId)
        assertEquals("srv1", auth.serverId)
        assertFalse(auth.toString().contains("tok"))

        server.takeRequest()
        assertEquals("POST", server.takeRequest().method)
        assertEquals("sec", server.takeRequest().requestUrl!!.queryParameter("secret"))
        server.takeRequest()
        val last = server.takeRequest()
        assertEquals("/Users/AuthenticateWithQuickConnect", last.requestUrl!!.encodedPath)
        assertEquals("sec", JsonParser.parseString(last.body.readUtf8()).asJsonObject.get("Secret").asString)
    }

    @Test
    fun `Quick Connect switched off on the server`() = runTest {
        server.enqueue(json("false"))
        assertFalse(signIn.quickConnectEnabled())
        server.enqueue(json("{}", code = 401))
        assertEquals(MediaResult.Unauthorized, signIn.quickConnect())
    }

    @Test
    fun `the header values are URL-encoded - a device name with a middle dot is not refused`() = runTest {
        val named = JellyfinSignIn(
            server.url("/").toString(),
            OkHttpClient(),
            MediaClientInfo("GateControl", "GateControl · Pixel von Müller", "gc-7Hq2mXk4Rb9tLp0sVw3N", "1.155.0"),
        )
        server.enqueue(json("""{"Secret":"sec","Code":"482913"}"""))
        named.quickConnect()
        val header = server.takeRequest().getHeader("Authorization")!!
        assertTrue(header.contains("Device=\"GateControl%20%C2%B7%20Pixel%20von%20M%C3%BCller\""), header)
        assertTrue(header.contains("DeviceId=\"gc-7Hq2mXk4Rb9tLp0sVw3N\""), header)
        assertTrue(header.contains("Client=\"GateControl\""), header)
        assertTrue(header.all { it.code < 128 }, "only ASCII in the header")
        assertEquals("a%20b%2C%22c%22!'()~", JellyfinClient.headerValue("a b,\"c\"!'()~"))
    }
}
