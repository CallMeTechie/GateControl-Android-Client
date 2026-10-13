package com.gatecontrol.android.diagnosis

import java.time.Instant

/**
 * The `diagnosis` key of the support bundle:
 * `{ startedAt, finishedAt, summary, checks: [ { id, status, title, detail, value?, hint? } ] }`.
 * Titles, details and hints are the texts shown on screen, resolved by
 * [resolve] in the app language.
 */
object DiagnosisPayload {

    fun build(report: DiagnosisReport, resolve: (DiagText) -> String): Map<String, Any?> = linkedMapOf(
        "startedAt" to Instant.ofEpochMilli(report.startedAt).toString(),
        "finishedAt" to Instant.ofEpochMilli(report.finishedAt).toString(),
        "summary" to report.summary.key,
        "checks" to report.checks.map { check ->
            linkedMapOf<String, Any?>(
                "id" to check.id.key,
                // An unfinished check (should not happen) is reported as skipped.
                "status" to (if (check.status.finished) check.status.key else CheckStatus.SKIPPED.key),
                "title" to resolve(DiagText(check.id.title)),
                "detail" to (check.detail?.let(resolve) ?: ""),
            ).apply {
                check.value?.let { put("value", it) }
                check.hint?.let { put("hint", resolve(it)) }
            }
        },
    )

    /** Problem description as sent: trimmed, at most [MAX_DESCRIPTION] chars, null when empty. */
    fun description(text: String?): String? = text?.trim()?.take(MAX_DESCRIPTION)?.takeIf { it.isNotEmpty() }

    const val MAX_DESCRIPTION = 2000
}
