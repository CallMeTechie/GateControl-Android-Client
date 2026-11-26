package com.gatecontrol.android.support

import com.gatecontrol.android.common.LoginOwner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerUrlRecoveryLogicTest {

    private val token = "gc_token_example"
    private val wg = """
        [Interface]
        PrivateKey = AAAA
        Address = 10.8.0.2/32

        [Peer]
        PublicKey = BBBB
        Endpoint = gate.example.com:51820
    """.trimIndent()

    @Test
    fun `endpoint host is read from the wireguard config`() {
        assertEquals("gate.example.com", ServerUrlRecoveryLogic.endpointHost(wg))
        assertEquals("2001:db8::1", ServerUrlRecoveryLogic.endpointHost("Endpoint = [2001:db8::1]:51820"))
        assertNull(ServerUrlRecoveryLogic.endpointHost("[Interface]\nAddress = 10.0.0.2/32"))
        assertNull(ServerUrlRecoveryLogic.endpointHost(null))
    }

    @Test
    fun `candidates come from push server, portal origin and endpoint`() {
        val c = ServerUrlRecoveryLogic.candidates("https://push.example.com/", "https://portal.example.com:8443/app", wg)
        assertTrue("https://push.example.com" in c)
        assertTrue("https://portal.example.com:8443" in c)
        assertTrue("https://gate.example.com" in c)
        assertEquals(c.toSet().size, c.size)
    }

    @Test
    fun `only a candidate matching a stored login hash is used`() {
        val candidates = ServerUrlRecoveryLogic.candidates(null, null, wg)
        val owner = LoginOwner.key("https://gate.example.com", token)
        assertEquals("https://gate.example.com", ServerUrlRecoveryLogic.verified(candidates, token, setOf("", owner)))
    }

    @Test
    fun `no match, no owner or no token restores nothing`() {
        val candidates = ServerUrlRecoveryLogic.candidates("https://old.example.com", null, wg)
        assertNull(ServerUrlRecoveryLogic.verified(candidates, token, setOf(LoginOwner.key("https://other.example.com", token))))
        assertNull(ServerUrlRecoveryLogic.verified(candidates, token, setOf("")))
        assertNull(ServerUrlRecoveryLogic.verified(candidates, token, emptySet()))
        assertNull(ServerUrlRecoveryLogic.verified(candidates, "", setOf(LoginOwner.key("https://gate.example.com", ""))))
    }

    @Test
    fun `a hash of another token does not verify`() {
        val candidates = ServerUrlRecoveryLogic.candidates(null, null, wg)
        assertNull(ServerUrlRecoveryLogic.verified(candidates, token, setOf(LoginOwner.key("https://gate.example.com", "other"))))
    }
}
