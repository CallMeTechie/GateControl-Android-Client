package com.gatecontrol.android.common

/**
 * The VPN-internal IPv4 subnet of the WireGuard server (e.g. 10.8.0.0/24).
 *
 * GateControl servers hand out host addresses (`Address = 10.8.0.5/32`), so
 * the prefix in the client config says nothing about the server subnet. We
 * therefore use the prefix when it is shorter than /32, and otherwise the /24
 * around the client address, which matches the server default. Everything
 * that used to assume a fixed `10.8.0.0/24` derives it from here instead.
 */
object VpnSubnet {

    const val DEFAULT = "10.8.0.0/24"

    /** Subnet from a WireGuard `Address` value (may be a comma list); null if none is IPv4. */
    fun fromAddress(address: String): String? {
        val entry = address.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.contains(':') }
            ?: return null
        val ip = parseIpv4(entry.substringBefore('/')) ?: return null
        val prefix = entry.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 8..32 } ?: return null
        val effective = if (prefix == 32) 24 else prefix
        val mask = (0xFFFFFFFFL shl (32 - effective)) and 0xFFFFFFFFL
        return "${toIpv4(ip and mask)}/$effective"
    }

    /** True if [ip] (IPv4 literal) lies inside [cidr]. */
    fun contains(cidr: String, ip: String): Boolean {
        val net = parseIpv4(cidr.substringBefore('/')) ?: return false
        val prefix = cidr.substringAfter('/', "").toIntOrNull()?.takeIf { it in 0..32 } ?: return false
        val addr = parseIpv4(ip) ?: return false
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (addr and mask) == (net and mask)
    }

    private fun parseIpv4(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (p in parts) {
            val v = p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            result = (result shl 8) or v.toLong()
        }
        return result
    }

    private fun toIpv4(v: Long) = "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
}
