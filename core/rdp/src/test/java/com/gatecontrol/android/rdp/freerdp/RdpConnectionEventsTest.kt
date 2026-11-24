package com.gatecontrol.android.rdp.freerdp

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpConnectionEventsTest {

    private val instance = 4711L
    private val other = 4712L

    @AfterEach
    fun tearDown() {
        RdpConnectionEvents.unregister(instance)
        RdpConnectionEvents.unregister(other)
    }

    @Test
    fun `a failed connection reaches the session's flow`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.PreConnect)
        RdpConnectionEvents.register(instance, flow)

        RdpConnectionEvents.OnConnectionFailure(instance)

        val e = flow.value
        assertTrue(e is RdpSessionEvent.ConnectionFailure)
        assertEquals(instance, (e as RdpSessionEvent.ConnectionFailure).instance)
    }

    @Test
    fun `the disconnect after a failure does not hide it`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.PreConnect)
        RdpConnectionEvents.register(instance, flow)

        RdpConnectionEvents.OnConnectionFailure(instance)
        RdpConnectionEvents.OnDisconnecting(instance)
        RdpConnectionEvents.OnDisconnected(instance)

        assertTrue(flow.value is RdpSessionEvent.ConnectionFailure)
    }

    @Test
    fun `a disconnect without failure is forwarded`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.SettingsChanged(1280, 720, 32))
        RdpConnectionEvents.register(instance, flow)

        RdpConnectionEvents.OnDisconnected(instance)

        assertTrue(flow.value is RdpSessionEvent.Disconnected)
    }

    @Test
    fun `pre-connect and success leave the settings event in place`() {
        val settings = RdpSessionEvent.SettingsChanged(1280, 720, 32)
        val flow = MutableStateFlow<RdpSessionEvent>(settings)
        RdpConnectionEvents.register(instance, flow)

        RdpConnectionEvents.OnPreConnect(instance)
        RdpConnectionEvents.OnConnectionSuccess(instance)

        assertEquals(settings, flow.value)
    }

    @Test
    fun `events of another instance do not reach this session`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.PreConnect)
        RdpConnectionEvents.register(instance, flow)

        RdpConnectionEvents.OnConnectionFailure(other)
        RdpConnectionEvents.OnDisconnected(other)

        assertEquals(RdpSessionEvent.PreConnect, flow.value)
    }

    @Test
    fun `after unregister nothing is delivered`() {
        val flow = MutableStateFlow<RdpSessionEvent>(RdpSessionEvent.PreConnect)
        RdpConnectionEvents.register(instance, flow)
        RdpConnectionEvents.unregister(instance)

        RdpConnectionEvents.OnConnectionFailure(instance)

        assertEquals(RdpSessionEvent.PreConnect, flow.value)
    }
}
