package com.gatecontrol.android.media.jellyfin

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** The server search in the home network: the answers, the broadcast address, a whole search on this machine. */
class JellyfinDiscoveryTest {

    @Test
    fun `an answer of a server in the home network`() {
        val hint = JellyfinDiscovery.parse("""{"Address":"http://192.168.1.20:8096","Id":"a1b2c3","Name":"DS918+","EndpointAddress":null}""")
        assertEquals(JellyfinServerHint("http://192.168.1.20:8096", "a1b2c3", "DS918+"), hint)
    }

    @Test
    fun `a server naming its public address is taken where it answered from`() {
        val hint = JellyfinDiscovery.parse("""{"Address":"https://media.example.test","Id":"a1","Name":"NAS"}""", from = "192.168.1.20")
        assertEquals("http://192.168.1.20:8096", hint?.address)
        // and not at all when the sender is not in the home network either
        assertNull(JellyfinDiscovery.parse("""{"Address":"https://media.example.test","Id":"a1","Name":"NAS"}""", from = "203.0.113.5"))
    }

    @Test
    fun `Jellyfin in Docker names its container address - taken where it answered from`() {
        val answer = """{"Address":"http://172.17.0.2:8096","Id":"a1","Name":"DS918+"}"""
        // the phone is in 192.168.2.0/24: 172.17.0.2 is private but not reachable from here
        assertEquals("http://192.168.2.151:8096", JellyfinDiscovery.parse(answer, from = "192.168.2.151", lan = listOf("192.168.2.37" to 24))?.address)
        // the networks unknown: the sender (it answered, so it is reachable) beats a named address it is not
        assertEquals("http://192.168.2.151:8096", JellyfinDiscovery.parse(answer, from = "192.168.2.151")?.address)
        // the named port and base path stay
        val mapped = """{"Address":"http://172.17.0.2:8920/jellyfin","Id":"a1","Name":"NAS"}"""
        assertEquals("http://192.168.2.151:8920/jellyfin", JellyfinDiscovery.parse(mapped, from = "192.168.2.151", lan = listOf("192.168.2.37" to 24))?.address)
    }

    @Test
    fun `a named address in the own network is kept, also when another one of the server answered`() {
        val answer = """{"Address":"http://192.168.2.151:8096","Id":"a1","Name":"NAS"}"""
        assertEquals("http://192.168.2.151:8096", JellyfinDiscovery.parse(answer, from = "192.168.2.152", lan = listOf("192.168.2.37" to 24))?.address)
        assertEquals("http://192.168.2.151:8096", JellyfinDiscovery.parse(answer, from = "192.168.2.151", lan = listOf("192.168.2.37" to 24))?.address)
        // a name is kept as named
        assertEquals("http://nas.local:8096", JellyfinDiscovery.parse("""{"Address":"http://nas.local:8096","Id":"a1"}""", from = "192.168.2.151", lan = listOf("192.168.2.37" to 24))?.address)
    }

    @Test
    fun `no answer from anything else`() {
        assertNull(JellyfinDiscovery.parse("who is JellyfinServer?"))
        assertNull(JellyfinDiscovery.parse("[1,2]"))
        assertNull(JellyfinDiscovery.parse("""{"Address":"http://192.168.1.20:8096","Name":"no id"}"""))
        assertNull(JellyfinDiscovery.parse("""{"Address":"http://192.168.1.20:8096","Id":"bad id; drop","Name":"x"}"""))
        assertNull(JellyfinDiscovery.parse("x".repeat(5000)))
    }

    @Test
    fun `a long name is cut, control characters are dropped`() {
        val hint = JellyfinDiscovery.parse("""{"Address":"http://nas.local:8096","Id":"a1","Name":"N\u0007AS${"x".repeat(100)}"}""")
        assertEquals(64, hint?.name?.length)
        assertEquals("NAS", hint?.name?.take(3))
        assertEquals("http://nas.local:8096", hint?.address)
    }

    @Test
    fun `the broadcast address of a home network`() {
        assertEquals("192.168.1.255", JellyfinDiscovery.broadcastOf("192.168.1.37", 24))
        assertEquals("10.0.255.255", JellyfinDiscovery.broadcastOf("10.0.3.4", 16))
        assertEquals("192.168.0.127", JellyfinDiscovery.broadcastOf("192.168.0.100", 25))
        assertNull(JellyfinDiscovery.broadcastOf("8.8.8.8", 24))
        assertNull(JellyfinDiscovery.broadcastOf("192.168.1.37", 32))
    }

    @Test
    fun `a search finds the server once`() = runBlocking {
        val fake = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val questions = mutableListOf<String>()
        val t = thread {
            val buf = ByteArray(256)
            val p = DatagramPacket(buf, buf.size)
            fake.receive(p)
            questions += String(p.data, 0, p.length)
            val answer = """{"Address":"http://192.168.1.20:8096","Id":"a1","Name":"DS918+"}""".toByteArray()
            // the same server answers twice (two networks), and something else answers too
            fake.send(DatagramPacket(answer, answer.size, p.socketAddress))
            fake.send(DatagramPacket(answer, answer.size, p.socketAddress))
            val junk = "hello".toByteArray()
            fake.send(DatagramPacket(junk, junk.size, p.socketAddress))
        }
        val found = JellyfinDiscovery.discover(listOf("127.0.0.1", "127.0.0.1"), port = fake.localPort, timeoutMs = 1_500)
        t.join(2_000)
        fake.close()
        assertEquals(listOf("who is JellyfinServer?"), questions)
        assertEquals(listOf("DS918+"), found.map { it.name })
    }
}
