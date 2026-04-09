package com.gatecontrol.android.rdp.freerdp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GateControlUiEventListenerTest {

    @Test
    fun `OnSettingsChanged emits SettingsChanged event`() = runTest {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { _, _ -> 0 },
            authenticate = { _, _ -> false }
        )

        listener.OnSettingsChanged(1920, 1080, 32)

        val event = flow.value
        assertTrue(event is RdpSessionEvent.SettingsChanged)
        val s = event as RdpSessionEvent.SettingsChanged
        assertEquals(1920, s.width)
        assertEquals(1080, s.height)
        assertEquals(32, s.bpp)
    }

    @Test
    fun `OnGraphicsResize emits GraphicsResize event`() = runTest {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { _, _ -> 0 },
            authenticate = { _, _ -> false }
        )

        listener.OnGraphicsResize(800, 600, 32)

        assertTrue(flow.value is RdpSessionEvent.GraphicsResize)
    }

    @Test
    fun `OnVerifiyCertificateEx delegates to verifyCertificate callback`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        var receivedUnknown: RdpSessionEvent.VerifyCertificate? = null
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { unknown, _ ->
                receivedUnknown = unknown
                1
            },
            authenticate = { _, _ -> false }
        )

        val verdict = listener.OnVerifiyCertificateEx(
            "host.example.com", 3389L, "CN", "subj", "issuer", "ab:cd", 0L
        )

        assertEquals(1, verdict)
        assertEquals("host.example.com", receivedUnknown?.host)
        assertEquals("ab:cd", receivedUnknown?.fingerprint)
    }

    @Test
    fun `OnAuthenticate delegates to authenticate callback and writes back credentials`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.Idle)
        val listener = GateControlUiEventListener(
            eventFlow = flow,
            verifyCertificate = { _, _ -> 0 },
            authenticate = { usernameBuf, passwordBuf ->
                usernameBuf.setLength(0); usernameBuf.append("prompted-user")
                passwordBuf.setLength(0); passwordBuf.append("prompted-pass")
                true
            }
        )

        val usernameBuf = StringBuilder()
        val domainBuf = StringBuilder()
        val passwordBuf = StringBuilder()
        val accepted = listener.OnAuthenticate(usernameBuf, domainBuf, passwordBuf)

        assertTrue(accepted)
        assertEquals("prompted-user", usernameBuf.toString())
        assertEquals("prompted-pass", passwordBuf.toString())
    }
}
