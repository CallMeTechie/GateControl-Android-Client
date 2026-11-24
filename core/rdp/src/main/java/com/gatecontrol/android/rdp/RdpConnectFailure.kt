package com.gatecontrol.android.rdp

/**
 * Why GET /client/rdp/:id/connect refused a session, as far as the app can explain it to the person.
 *
 * For a PC that registered itself with Fernzugriff (shared "ohne Code") the plugin's `connect` asks the
 * PC to open its Windows firewall for this app's VPN address and waits for the PC's report. The answers
 * (plugin gatecontrol-remote, server/facade.js `connectHost`, server/support.js `open`):
 *
 * - 504 `firewall_timeout` — the PC did not report in time (off, or its GateControl app not connected)
 * - 504 `firewall_failed`  — the PC refused or could not open the rule
 * - 403 `code_required`    — the PC is shared only with a code
 * - 503 `Host is offline`  — the PC's app is not ready
 * - 400 `no_vpn`           — the server does not know this app's VPN address
 *
 * Everything else keeps the generic error message.
 */
enum class RdpConnectFailure {
    FIREWALL_TIMEOUT,
    FIREWALL_REFUSED,
    CODE_REQUIRED,
    HOST_OFFLINE,
    NO_VPN;

    companion object {
        private val ERROR_FIELD = Regex("\"error\"\\s*:\\s*\"([^\"]*)\"")

        /** The `error` field of a JSON error body like `{"ok":false,"error":"code_required"}`, or null. */
        fun errorOf(body: String?): String? =
            body?.let { ERROR_FIELD.find(it)?.groupValues?.get(1) }

        /** The failure for an HTTP status and its error body, or null when it is none of the known ones. */
        fun from(httpStatus: Int, body: String?): RdpConnectFailure? {
            val error = errorOf(body)?.trim()?.lowercase() ?: return null
            return when {
                error == "firewall_timeout" -> FIREWALL_TIMEOUT
                error == "firewall_failed" -> FIREWALL_REFUSED
                error == "code_required" -> CODE_REQUIRED
                error == "no_vpn" -> NO_VPN
                httpStatus == 503 && error == "host is offline" -> HOST_OFFLINE
                else -> null
            }
        }
    }
}
