package com.gatecontrol.android.common

/**
 * Normalize a raw device name (Settings.Global.DEVICE_NAME or Build.MODEL)
 * into a DNS-label-safe form that matches the GateControl server's strict
 * hostname validator (RFC-1123: a-z 0-9 hyphen, max 63, no leading/trailing
 * hyphen).
 *
 * Android device names routinely contain spaces ("Marc's Pixel 7"),
 * apostrophes, and mixed case — all of which would be rejected by the
 * server's validator. Cleaning client-side saves a 400 round-trip.
 *
 * Returns null if nothing usable remains (all-invalid input, empty, etc.).
 * Mirrors ApiClient.sanitizeHostnameForDns in the Windows client-core.
 */
object HostnameSanitizer {
    private val INVALID = Regex("[^a-z0-9-]")
    private val MULTI_HYPHEN = Regex("-+")
    private val EDGE_HYPHEN = Regex("^-+|-+$")

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().lowercase()
        // Strip dotted suffix (e.g. ".local", ".lan")
        val dot = s.indexOf('.')
        if (dot >= 0) s = s.substring(0, dot)
        s = INVALID.replace(s, "-")
        s = MULTI_HYPHEN.replace(s, "-")
        s = EDGE_HYPHEN.replace(s, "")
        if (s.length > 63) s = s.substring(0, 63).replace(EDGE_HYPHEN, "")
        return if (s.isEmpty()) null else s
    }
}
