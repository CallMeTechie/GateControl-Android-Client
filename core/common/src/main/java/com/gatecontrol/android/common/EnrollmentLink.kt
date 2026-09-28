package com.gatecontrol.android.common

import java.net.URI
import java.net.URLDecoder

/**
 * One-scan setup link issued by the GateControl server ("Android-App
 * einrichten"): `gatecontrol://enroll?url=https://gate.example.com&code=XXXX-XXXX-XXXX-XXXX`.
 * The code is redeemed at POST /api/v1/client/enroll for a peer-bound API
 * token plus the WireGuard config.
 *
 * Pure parsing (java.net only) so it is unit-testable without Android.
 */
data class EnrollmentLink(val serverUrl: String, val code: String) {

    companion object {
        private val SEPARATORS = Regex("[\\s-]")
        private val HEX16 = Regex("^[A-F0-9]{16}$")

        /** Parses a scanned/opened link; null if it is not a valid enrollment link. */
        fun parse(raw: String?): EnrollmentLink? {
            if (raw.isNullOrBlank()) return null
            val uri = try {
                URI(raw.trim())
            } catch (_: Exception) {
                return null
            }
            if (!uri.scheme.equals("gatecontrol", ignoreCase = true)) return null
            if (!uri.host.equals("enroll", ignoreCase = true)) return null

            val params = parseQuery(uri.rawQuery ?: return null)
            val url = normalizeServerUrl(params["url"]) ?: return null
            val code = normalizeCode(params["code"]) ?: return null
            return EnrollmentLink(url, code)
        }

        /**
         * Canonical XXXX-XXXX-XXXX-XXXX form of a hand-typed code (any case,
         * spaces, dashes or none), or null if it is not 16 hex digits.
         * API tokens (`gc_…`) are never mistaken for a code.
         */
        fun normalizeCode(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (trimmed.startsWith("gc_", ignoreCase = true)) return null
            val hex = trimmed.uppercase().replace(SEPARATORS, "")
            if (!HEX16.matches(hex)) return null
            return "${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}"
        }

        /** Only https server URLs are accepted; returns scheme://host[:port] without trailing slash. */
        fun normalizeServerUrl(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val uri = try {
                URI(raw.trim())
            } catch (_: Exception) {
                return null
            }
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""
            return "https://$host$port"
        }

        private fun parseQuery(rawQuery: String): Map<String, String> =
            rawQuery.split("&")
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                    val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                    key to value
                }
                .toMap()
    }
}
