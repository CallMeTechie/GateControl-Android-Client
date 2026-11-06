package com.gatecontrol.android.print

/** The print plugin ("Drucken & Scannen", plugin `gatecontrol-print` 1.0, GateControl 1.236+). */
const val PRINT_PLUGIN = "gatecontrol-print"

/**
 * A printer the person may use (`overview.printers[]`, never an address). [state] is the printer's own state
 * (`idle`, `processing`, `stopped`, `offline`); [online] false means its gateway or the printer is off: a job then
 * waits up to 24 hours. [problem] `cert_changed`: an administrator must confirm the device first.
 */
data class PPrinter(
    val id: String,
    val name: String,
    val location: String?,
    val online: Boolean,
    val problem: String?,
    val state: String,
    val color: Boolean,
    val duplex: Boolean,
    val copiesMax: Int,
)

/** The person's rights (`overview.rights`): [pagesPerDay] 0 = no limit. */
data class PRights(val print: Boolean, val pagesPerDay: Int, val bwOnly: Boolean)

/** A print job of the person (`overview.jobs[]`, `print.job`). States: README of the plugin / docs/plugins.md `gc.print`. */
data class PJob(
    val id: String,
    val name: String,
    val device: String?,
    val state: String,
    val error: String?,
    val message: String?,
    val pages: Int,
    val copies: Int,
)

/** `GET overview` as far as printing needs it. [pagesLeft] null = no limit today. */
data class POverview(
    val rights: PRights,
    val pagesLeft: Int?,
    val printers: List<PPrinter>,
    val jobs: List<PJob>,
)

/** `POST print` → the new job and what is left today (null = no limit). */
data class PPrinted(val job: PJob, val pagesLeft: Int?)

/** What a print call gave: never an exception (except cancellation). */
sealed interface PResult<out T> {
    data class Ok<T>(val value: T) : PResult<T>

    /** [code] the plugin's or the core's code (`no_right`, `limit`, `too_large`, `not_found`, …); [text] its own words. */
    data class Failed(val code: String, val status: Int, val text: String? = null, val left: Int? = null, val pages: Int? = null) : PResult<Nothing>

    data object Offline : PResult<Nothing>

    data object NotConfigured : PResult<Nothing>
}

fun <T> PResult<T>.valueOrNull(): T? = (this as? PResult.Ok)?.value
