package com.gatecontrol.android.common

import java.net.URI
import java.net.URLDecoder

/**
 * The QR code GateControl TV shows while it waits to be paired (server ≥ 1.318.0, `POST /api/v1/client/pair/start`):
 * `gatecontrol://tv-pair?url=https://gate.example.com&id=<pairing id>`. It carries only the server and the pairing
 * id, never the display code or the TV's poll secret: the phone still needs the code from the TV screen to approve
 * (`POST /api/v1/client/pair/approve`).
 *
 * Pure parsing (java.net only) so it is unit-testable without Android.
 */
data class TvPairLink(val serverUrl: String, val pairingId: String) {

    companion object {
        /** The server's pairing ids: 16 random bytes, base64url without padding. */
        private val ID_RE = Regex("^[A-Za-z0-9_-]{22}$")

        /** Parses a scanned/opened link; null if it is not a valid TV pairing link. */
        fun parse(raw: String?): TvPairLink? {
            if (raw.isNullOrBlank() || raw.length > 2048) return null
            val uri = try {
                URI(raw.trim())
            } catch (_: Exception) {
                return null
            }
            if (!uri.scheme.equals("gatecontrol", ignoreCase = true)) return null
            if (!uri.host.equals("tv-pair", ignoreCase = true)) return null
            val params = parseQuery(uri.rawQuery ?: return null) ?: return null
            val url = EnrollmentLink.normalizeServerUrl(params["url"]) ?: return null
            val id = params["id"]?.takeIf { ID_RE.matches(it) } ?: return null
            return TvPairLink(url, id)
        }

        /** The link for a pairing (what the server puts into `link`; the TV builds it itself if the field is missing). */
        fun build(serverUrl: String, pairingId: String): String =
            "gatecontrol://tv-pair?url=" + java.net.URLEncoder.encode(serverUrl, "UTF-8") + "&id=" + pairingId

        private fun parseQuery(rawQuery: String): Map<String, String>? = try {
            rawQuery.split("&")
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    URLDecoder.decode(part.substring(0, idx), "UTF-8") to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                }
                .toMap()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * The 8-character display code of a TV pairing (server `services/clientPairing.js`): letters and digits without
 * 0/O, 1/I/L, shown on the TV as `ABCD-EFGH` and typed on the phone in any case, with or without dash or spaces.
 */
object TvPairCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8
    private val SEPARATORS = Regex("[\\s-]")

    /** `abcd efgh` / `ABCD-EFGH` → `ABCDEFGH`, or null when it cannot be a display code. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length > 32) return null
        val s = raw.uppercase().replace(SEPARATORS, "")
        if (s.length != LENGTH || s.any { it !in ALPHABET }) return null
        return s
    }

    /** `ABCDEFGH` → `ABCD-EFGH` (how the TV shows it); anything else unchanged. */
    fun format(code: String): String {
        val n = normalize(code) ?: return code
        return n.substring(0, 4) + "-" + n.substring(4)
    }

    /**
     * What a person typed so far, cleaned for the input field: upper case, only characters of the alphabet (a typed
     * 0/O/1/I/L is dropped), at most [LENGTH].
     */
    fun cleanInput(raw: String): String =
        raw.uppercase().filter { it in ALPHABET }.take(LENGTH)
}
