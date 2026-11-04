package com.gatecontrol.android.network

import com.gatecontrol.android.common.VpnSubnet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import java.net.UnknownHostException

class VpnSafeDnsTest {

    private val host = "gc.example.com"
    private val gateway = "10.8.0.1"
    private val public = "89.163.231.212"
    private val nat64 = "64:ff9b:0:0:0:0:59a3:e7d4" // as InetAddress.hostAddress writes it

    private fun addr(ip: String): InetAddress = InetAddress.getByAddress(host, InetAddress.getByName(ip).address)

    private class MemoryLastKnown : DnsLastKnown {
        val map = mutableMapOf<String, List<InetAddress>>()
        var puts = 0
        override fun get(hostname: String) = map[hostname]
        override fun put(hostname: String, addresses: List<InetAddress>) {
            puts++
            map[hostname] = addresses
        }
    }

    private class Fake {
        var system: List<String>? = emptyList()
        var outside: List<String> = emptyList()
        var viaVpn = false
        var outsideCalls = 0
        var routeChecks = 0
    }

    private fun dns(fake: Fake, lastKnown: DnsLastKnown = MemoryLastKnown()) = VpnSafeDns(
        system = { fake.system?.map(::addr) ?: throw UnknownHostException("no answer") },
        isVpnInternal = { VpnSubnet.contains("10.8.0.0/24", it.hostAddress ?: "") },
        routedThroughVpn = { fake.routeChecks++; fake.viaVpn },
        outsideVpn = { fake.outsideCalls++; fake.outside.map(::addr) },
        lastKnown = lastKnown,
    )

    private fun List<InetAddress>.ips() = map { it.hostAddress }

    @Test
    fun `a public answer is used as it is, without asking the network state`() {
        val fake = Fake().apply { system = listOf(public) }
        val last = MemoryLastKnown()
        assertEquals(listOf(public), dns(fake, last).lookup(host).ips())
        assertEquals(0, fake.routeChecks)
        assertEquals(listOf(public), last.map[host]?.ips())
    }

    @Test
    fun `app routed through the tunnel - the split-horizon VPN address is used first, known public ones follow`() {
        val fake = Fake().apply { system = listOf(public) }
        val dns = dns(fake)
        dns.lookup(host) // learned before the tunnel came up
        fake.system = listOf(gateway)
        fake.viaVpn = true
        assertEquals(listOf(gateway, public), dns.lookup(host).ips())
        assertEquals(0, fake.outsideCalls)
    }

    @Test
    fun `app routed through the tunnel - no cache after a restart is no failure`() {
        // Process restarted with the tunnel up: nothing learned, nothing persisted.
        val fake = Fake().apply { system = listOf(gateway); viaVpn = true }
        assertEquals(listOf(gateway), dns(fake).lookup(host).ips())
    }

    @Test
    fun `app excluded from the tunnel - a VPN address is dropped and the name is resolved outside the VPN`() {
        val fake = Fake().apply { system = listOf(gateway); viaVpn = false; outside = listOf(public) }
        val last = MemoryLastKnown()
        val dns = dns(fake, last)
        assertEquals(listOf(public), dns.lookup(host).ips())
        assertEquals(1, fake.outsideCalls)
        assertEquals(listOf(public), last.map[host]?.ips())
        // learned: the next lookup does not ask the other network again
        assertEquals(listOf(public), dns.lookup(host).ips())
        assertEquals(1, fake.outsideCalls)
    }

    @Test
    fun `app excluded - outside resolution fails, the persisted address of an earlier run helps`() {
        val last = MemoryLastKnown().apply { map[host] = listOf(addr(public)) }
        val fake = Fake().apply { system = listOf(gateway); outside = emptyList() }
        assertEquals(listOf(public), dns(fake, last).lookup(host).ips())
    }

    @Test
    fun `app excluded - outside answers only VPN addresses, they are not taken`() {
        val fake = Fake().apply { system = listOf(gateway); outside = listOf(gateway) }
        val e = assertThrows<UnknownHostException> { dns(fake).lookup(host) }
        assertTrue(e.message!!.contains("only VPN-internal"))
    }

    @Test
    fun `no answer at all - outside the VPN, then the last known address, else a clear error`() {
        val fake = Fake().apply { system = null; outside = listOf(nat64) }
        assertEquals(listOf(nat64), dns(fake).lookup(host).ips())

        val last = MemoryLastKnown().apply { map[host] = listOf(addr(public)) }
        val offline = Fake().apply { system = null }
        assertEquals(listOf(public), dns(offline, last).lookup(host).ips())

        val e = assertThrows<UnknownHostException> { dns(Fake().apply { system = null }).lookup(host) }
        assertTrue(e.message!!.contains("no answer"))
    }

    @Test
    fun `mixed answer outside the tunnel keeps only the public part`() {
        val fake = Fake().apply { system = listOf(gateway, public); viaVpn = false }
        assertEquals(listOf(public), dns(fake).lookup(host).ips())
    }

    @Test
    fun `a failing network check counts as outside the tunnel`() {
        val dns = VpnSafeDns(
            system = { listOf(addr(gateway)) },
            isVpnInternal = { VpnSubnet.contains("10.8.0.0/24", it.hostAddress ?: "") },
            routedThroughVpn = { throw SecurityException("no access") },
            outsideVpn = { listOf(addr(public)) },
            lastKnown = MemoryLastKnown(),
        )
        assertEquals(listOf(public), dns.lookup(host).ips())
    }

    @Test
    fun `pre-resolve never keeps a VPN address and asks outside the VPN instead`() {
        val fake = Fake().apply { system = listOf(gateway); outside = listOf(public) }
        val last = MemoryLastKnown()
        dns(fake, last).preResolve(host)
        assertEquals(listOf(public), last.map[host]?.ips())

        val nothing = Fake().apply { system = listOf(gateway) }
        val keep = MemoryLastKnown().apply { map[host] = listOf(addr(public)) }
        dns(nothing, keep).preResolve(host)
        assertEquals(listOf(public), keep.map[host]?.ips())
    }

    @Test
    fun `clear forgets this run but keeps the last known address`() {
        val fake = Fake().apply { system = listOf(public) }
        val last = MemoryLastKnown()
        val dns = dns(fake, last)
        dns.preResolve(host)
        dns.clear()
        fake.system = listOf(gateway)
        assertEquals(listOf(public), dns.lookup(host).ips())
        assertEquals(1, fake.outsideCalls)
    }

    @Test
    fun `stored addresses round-trip as literals and never trigger a lookup`() {
        val encoded = PrefsDnsLastKnown.encode(listOf(addr(public), addr(nat64), addr(public)))
        assertEquals("$public,$nat64", encoded)
        val decoded = PrefsDnsLastKnown.decode(host, encoded)!!
        assertEquals(listOf(public, nat64), decoded.ips())
        assertEquals(host, decoded.first().hostName)
        assertEquals(listOf(public), PrefsDnsLastKnown.decode(host, "evil.example.com,$public")?.ips())
        assertNull(PrefsDnsLastKnown.decode(host, ""))
        assertNull(PrefsDnsLastKnown.decode(host, "not-an-ip"))
    }

    @Test
    fun `persisted store without preferences still remembers in memory`() {
        val store = PrefsDnsLastKnown { throw IllegalStateException("no context") }
        store.put(host, listOf(addr(public)))
        assertEquals(listOf(public), store.get(host)?.ips())
    }
}
