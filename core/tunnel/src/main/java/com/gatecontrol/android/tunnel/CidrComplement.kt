package com.gatecontrol.android.tunnel

import java.math.BigInteger
import java.net.InetAddress

/**
 * Computes the complement of a set of CIDRs within 0.0.0.0/0 and ::/0.
 * Used for split-tunnel "exclude" mode: given a list of CIDRs to exclude,
 * returns the minimal set of CIDRs that covers everything EXCEPT the excluded ranges,
 * for IPv4 and IPv6 alike (so IPv6 traffic is tunneled too, minus the exclusions).
 *
 * Invalid CIDRs are silently skipped.
 */
object CidrComplement {

    /**
     * Compute AllowedIPs for exclude mode.
     * @param excludedCidrs CIDRs to exclude from the tunnel
     * @return Minimal set of CIDRs covering 0.0.0.0/0 minus the excluded ranges
     */
    fun computeAllowedIps(excludedCidrs: List<String>): List<String> {
        val ipv6Excludes = mutableListOf<Pair<BigInteger, BigInteger>>()
        val ipv4Excludes = mutableListOf<Pair<Long, Long>>() // (start, end) ranges

        for (cidr in excludedCidrs) {
            if (cidr.contains(':')) {
                parseCidr6(cidr)?.let { ipv6Excludes.add(it) }
                continue
            }
            val range = parseCidr(cidr) ?: continue
            ipv4Excludes.add(range)
        }

        val ipv6 = complement6(ipv6Excludes)

        if (ipv4Excludes.isEmpty()) {
            return listOf("0.0.0.0/0") + ipv6
        }

        // Merge overlapping ranges
        val merged = mergeRanges(ipv4Excludes)

        // Compute complement within 0..2^32-1
        val complement = complementRanges(merged, 0L, 4294967295L)

        // Convert ranges back to minimal CIDRs
        val result = mutableListOf<String>()
        for ((start, end) in complement) {
            result.addAll(rangeToCidrs(start, end))
        }

        return result + ipv6
    }

    // Parse "10.0.0.0/8" to (start, end) inclusive range
    internal fun parseCidr(cidr: String): Pair<Long, Long>? {
        val parts = cidr.split('/')
        if (parts.size != 2) return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix < 0 || prefix > 32) return null
        val ip = ipToLong(parts[0]) ?: return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val start = ip and mask
        val end = start or (mask.inv() and 0xFFFFFFFFL)
        return start to end
    }

    internal fun ipToLong(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var result = 0L
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v < 0 || v > 255) return null
            result = (result shl 8) or v.toLong()
        }
        return result
    }

    internal fun longToIp(value: Long): String {
        return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
    }

    // Merge overlapping/adjacent ranges (sorted by start)
    internal fun mergeRanges(ranges: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val result = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = result.last()
            val cur = sorted[i]
            if (cur.first <= last.second + 1) {
                result[result.size - 1] = last.first to maxOf(last.second, cur.second)
            } else {
                result.add(cur)
            }
        }
        return result
    }

    // Complement of merged ranges within [fullStart, fullEnd]
    internal fun complementRanges(merged: List<Pair<Long, Long>>, fullStart: Long, fullEnd: Long): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        var cursor = fullStart
        for ((start, end) in merged) {
            if (cursor < start) {
                result.add(cursor to start - 1)
            }
            cursor = end + 1
            if (cursor > fullEnd) break
        }
        if (cursor <= fullEnd) {
            result.add(cursor to fullEnd)
        }
        return result
    }

    // Convert a contiguous range to minimal CIDR notation
    internal fun rangeToCidrs(start: Long, end: Long): List<String> {
        val result = mutableListOf<String>()
        var current = start
        while (current <= end) {
            // Find largest block starting at current that fits within [current, end]
            var maxBits = 32
            while (maxBits > 0) {
                val mask = (0xFFFFFFFFL shl maxBits) and 0xFFFFFFFFL
                val blockStart = current and mask
                val blockEnd = current or (mask.inv() and 0xFFFFFFFFL)
                if (blockStart == current && blockEnd <= end) {
                    break
                }
                maxBits--
            }
            val prefix = 32 - maxBits
            result.add("${longToIp(current)}/$prefix")
            val blockSize = 1L shl maxBits
            current += blockSize
            if (current < 0 || current > 4294967295L) break // overflow guard
        }
        return result
    }

    // --- IPv6 (128-bit, BigInteger) ---

    private val MAX6: BigInteger = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)

    internal fun parseCidr6(cidr: String): Pair<BigInteger, BigInteger>? {
        val parts = cidr.split('/')
        if (parts.size != 2) return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix < 0 || prefix > 128) return null
        val addr = parts[0].substringBefore('%')
        if (!addr.contains(':') || !addr.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
        val bytes = try {
            // Literal only (contains ':'), so this never performs a DNS lookup.
            InetAddress.getByName(addr).address
        } catch (_: Exception) {
            return null
        }
        if (bytes.size != 16) return null
        val ip = BigInteger(1, bytes)
        val hostBits = BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE)
        val start = ip.andNot(hostBits)
        return start to start.or(hostBits)
    }

    internal fun complement6(excludes: List<Pair<BigInteger, BigInteger>>): List<String> {
        if (excludes.isEmpty()) return listOf("::/0")
        val sorted = excludes.sortedBy { it.first }
        val merged = mutableListOf(sorted[0])
        for (cur in sorted.drop(1)) {
            val last = merged.last()
            if (cur.first <= last.second.add(BigInteger.ONE)) {
                merged[merged.size - 1] = last.first to last.second.max(cur.second)
            } else {
                merged.add(cur)
            }
        }
        val result = mutableListOf<String>()
        var cursor = BigInteger.ZERO
        for ((start, end) in merged) {
            if (cursor < start) result += rangeToCidrs6(cursor, start.subtract(BigInteger.ONE))
            cursor = end.add(BigInteger.ONE)
            if (cursor > MAX6) break
        }
        if (cursor <= MAX6) result += rangeToCidrs6(cursor, MAX6)
        return result
    }

    private fun rangeToCidrs6(start: BigInteger, end: BigInteger): List<String> {
        val result = mutableListOf<String>()
        var current = start
        while (current <= end) {
            var hostBits = 128
            while (hostBits > 0) {
                val size = BigInteger.ONE.shiftLeft(hostBits)
                if (current.mod(size) == BigInteger.ZERO && current.add(size).subtract(BigInteger.ONE) <= end) break
                hostBits--
            }
            result.add("${bigToIp6(current)}/${128 - hostBits}")
            current = current.add(BigInteger.ONE.shiftLeft(hostBits))
        }
        return result
    }

    /** Canonical RFC 5952 text form (longest zero run compressed to "::"). */
    internal fun bigToIp6(value: BigInteger): String {
        val groups = (0 until 8).map { i -> value.shiftRight(112 - 16 * i).toInt() and 0xFFFF }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen && j - i >= 2) { bestStart = i; bestLen = j - i }
                i = j
            } else {
                i++
            }
        }
        val hex = groups.map { Integer.toHexString(it) }
        if (bestStart < 0) return hex.joinToString(":")
        val head = hex.subList(0, bestStart).joinToString(":")
        val tail = hex.subList(bestStart + bestLen, 8).joinToString(":")
        return "$head::$tail"
    }
}
