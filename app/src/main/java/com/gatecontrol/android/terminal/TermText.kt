package com.gatecontrol.android.terminal

import androidx.annotation.StringRes
import com.gatecontrol.android.R

/** The text of an error code of the terminal (design „Fehlertexte“); unknown codes get the generic one. */
object TermText {
    @StringRes
    fun error(code: String): Int = when (code) {
        "host_key_mismatch" -> R.string.term_err_mismatch
        "host_key_rejected" -> R.string.term_err_rejected
        "host_key_unpinned" -> R.string.term_err_unpinned
        "auth_failed", "key_unreadable", "credential_required", "credential_unreadable", "credential_missing" -> R.string.term_err_auth
        "recording_unavailable" -> R.string.term_err_recording
        "plugin_unavailable", "http_503", "offline" -> R.string.term_err_plugin
        "shell_refused" -> R.string.term_err_shell
        "unreachable", "timeout", "listener_required", "hello_timeout", "connection_lost" -> R.string.term_err_unreachable
        "limit", "limit_reached", "rate_limited", "http_429" -> R.string.term_err_limit
        "not_found", "http_404" -> R.string.term_err_expired
        "no_right", "forbidden" -> R.string.term_err_no_right
        "maintenance" -> R.string.term_err_maintenance
        "disabled" -> R.string.term_err_disabled
        "network" -> R.string.term_err_offline
        else -> R.string.term_err_other
    }
}
