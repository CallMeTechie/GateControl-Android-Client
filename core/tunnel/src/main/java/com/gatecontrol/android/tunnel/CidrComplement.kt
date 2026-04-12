package com.gatecontrol.android.tunnel

/**
 * Computes the complement of a set of IPv4 CIDRs within 0.0.0.0/0.
 * Used for split-tunnel "exclude" mode: given a list of CIDRs to exclude,
 * returns the minimal set of CIDRs that covers everything EXCEPT the excluded ranges.
 *
 * IPv6 CIDRs (containing ':') are passed through unchanged.
 * Invalid CIDRs are silently skipped.
 */
object CidrComplement {

    /**
     * Compute AllowedIPs for exclude mode.
     * @param excludedCidrs CIDRs to exclude from the tunnel
     * @return Minimal set of CIDRs covering 0.0.0.0/0 minus the excluded ranges
     */
    fun computeAllowedIps(excludedCidrs: List<String>): List<String> {
        val ipv6 = mutableListOf<String>()
        val ipv4Excludes = mutableListOf<Pair<Long, Long>>() // (start, end) ranges

        for (cidr in excludedCidrs) {
            if (cidr.contains(':')) {
                ipv6.add(cidr) // pass through IPv6
                continue
            }
            val range = parseCidr(cidr) ?: continue
            ipv4Excludes.add(range)
        }

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
}
