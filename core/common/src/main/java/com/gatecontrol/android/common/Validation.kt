package com.gatecontrol.android.common

object Validation {

    private val IPV4_REGEX = Regex(
        """^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$"""
    )

    private val HEX64_REGEX = Regex("""^[0-9a-f]{64}$""")

    fun validateIp(value: String): Boolean {
        val match = IPV4_REGEX.matchEntire(value) ?: return false
        return match.groupValues.drop(1).all { it.toInt() in 0..255 }
    }

    fun validateCidr(value: String): Boolean {
        val parts = value.split("/")
        if (parts.size != 2) return false
        val (ip, prefix) = parts
        val prefixInt = prefix.toIntOrNull() ?: return false
        if (prefixInt !in 0..32) return false
        return validateIp(ip)
    }

    fun validatePort(port: Int): Boolean = port in 1..65535

    fun validateServerUrl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val rest = url.removePrefix("https://")
        return rest.isNotEmpty()
    }

    fun validateApiToken(token: String): Boolean {
        if (!token.startsWith("gc_")) return false
        return token.length > 3
    }

    fun validateFingerprint(fp: String): Boolean = HEX64_REGEX.matches(fp)

    fun parseSplitRoutes(input: String): List<String> {
        return input.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && validateCidr(it) }
    }
}
