package com.gatecontrol.android.diagnosis

import android.content.res.Resources
import androidx.annotation.StringRes
import com.gatecontrol.android.R
import java.util.Locale

/** The checks of the diagnosis, in the order they run. [key] = id in the support bundle. */
enum class CheckId(val key: String, @StringRes val title: Int) {
    INTERNET("internet", R.string.diag_check_internet),
    SERVER("server", R.string.diag_check_server),
    TUNNEL("tunnel", R.string.diag_check_tunnel),
    HANDSHAKE("handshake", R.string.diag_check_handshake),
    DNS("dns", R.string.diag_check_dns),
    MTU("mtu", R.string.diag_check_mtu),
    SPEED("speed", R.string.diag_check_speed),
}

enum class CheckStatus(val key: String) {
    PENDING("pending"),
    RUNNING("running"),
    OK("ok"),
    WARN("warn"),
    FAIL("fail"),
    SKIPPED("skipped");

    val finished: Boolean get() = this != PENDING && this != RUNNING
}

/**
 * A text of the diagnosis, resolved late: on screen with the Activity's
 * resources, for the support bundle with a context in the app language
 * (the app switches its language on the Activity only, see UiText).
 * Args may be [DiagText] (nested) or [Mbps] (locale-aware number).
 */
data class DiagText(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())

    fun resolve(res: Resources): String {
        val locale = runCatching { res.configuration.locales.get(0) }.getOrNull() ?: Locale.getDefault()
        val resolved = args.map { arg ->
            when (arg) {
                is DiagText -> arg.resolve(res)
                is Mbps -> arg.format(locale)
                else -> arg
            }
        }
        return if (resolved.isEmpty()) res.getString(id) else res.getString(id, *resolved.toTypedArray())
    }
}

/** A rate in Mbit/s, formatted in the reader's locale (no decimals from 10 on). */
data class Mbps(val value: Double) {
    fun format(locale: Locale): String =
        if (value >= 10) String.format(locale, "%.0f", value) else String.format(locale, "%.1f", value)

    /** Technical, locale-independent form for the bundle's `value`. */
    fun plain(): String = String.format(Locale.ROOT, "%.1f", value)
}

/**
 * Result of one check. [detail] is the one-line result shown on screen,
 * [value] the technical value (unlocalised, also in the bundle), [hint]
 * what to do (warn / fail / skipped).
 */
data class CheckResult(
    val id: CheckId,
    val status: CheckStatus,
    val detail: DiagText? = null,
    val value: String? = null,
    val hint: DiagText? = null,
) {
    companion object {
        fun pending(id: CheckId) = CheckResult(id, CheckStatus.PENDING)
    }
}

enum class DiagnosisSummary(val key: String) { OK("ok"), WARN("warn"), FAIL("fail") }

/** A finished diagnosis run (epoch millis). */
data class DiagnosisReport(
    val startedAt: Long,
    val finishedAt: Long,
    val checks: List<CheckResult>,
) {
    val summary: DiagnosisSummary get() = summarize(checks)
    val skippedCount: Int get() = checks.count { it.status == CheckStatus.SKIPPED }
    val issueCount: Int get() = checks.count { it.status == CheckStatus.WARN || it.status == CheckStatus.FAIL }

    /** The hint that matters most: first failure, else first warning. */
    val topIssue: CheckResult? get() = mostImportant(checks)

    companion object {
        /** fail if any failed, warn if any warned, else ok — skipped never degrades. */
        fun summarize(checks: List<CheckResult>): DiagnosisSummary = when {
            checks.any { it.status == CheckStatus.FAIL } -> DiagnosisSummary.FAIL
            checks.any { it.status == CheckStatus.WARN } -> DiagnosisSummary.WARN
            else -> DiagnosisSummary.OK
        }

        fun mostImportant(checks: List<CheckResult>): CheckResult? =
            checks.firstOrNull { it.status == CheckStatus.FAIL } ?: checks.firstOrNull { it.status == CheckStatus.WARN }
    }
}
