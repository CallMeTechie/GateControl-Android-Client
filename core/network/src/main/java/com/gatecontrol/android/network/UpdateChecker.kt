package com.gatecontrol.android.network

object UpdateChecker {

    /**
     * Returns true if [remote] is a newer semantic version than [local].
     * Compares major, minor, and patch components numerically.
     * Returns false for malformed input.
     */
    fun isNewer(remote: String, local: String): Boolean {
        return try {
            val remoteParts = remote.trim().split(".").map { it.toInt() }
            val localParts = local.trim().split(".").map { it.toInt() }

            if (remoteParts.size < 1 || localParts.size < 1) return false

            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            false
        } catch (e: NumberFormatException) {
            false
        }
    }
}
