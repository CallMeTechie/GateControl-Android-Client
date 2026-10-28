package com.gatecontrol.android.drive

import java.time.Instant

/**
 * "Auf früheren Stand zurücksetzen…" of a folder (Drive 1.7, feature `timeTravel`) step by step: pick a time, look at
 * what it would do (numbers, examples, a note when Drive cannot turn back older renames), run it, then "Rückgängig" —
 * another time travel to the moment just before. Pure (unit-tested); the view model does the calls.
 */
data class DriveTimeTravel(
    val folder: DNode,
    val step: Step = Step.PICK,
    val atMs: Long? = null,
    val preview: DTimeTravelPreview? = null,
    val result: DTimeTravelResult? = null,
    /** The result is the one of "Rückgängig" (no second undo). */
    val undone: Boolean = false,
    /** A Drive code, or `tt_future` for a time not in the past. */
    val error: String? = null,
) {
    enum class Step { PICK, PREVIEWING, PREVIEW, RUNNING, DONE, UNDOING }

    /** The time travel goes to [ms]; it must lie in the past. */
    fun pick(ms: Long, nowMs: Long): DriveTimeTravel =
        if (ms >= nowMs) copy(step = Step.PICK, error = FUTURE) else copy(step = Step.PREVIEWING, atMs = ms, preview = null, error = null)

    fun previewed(r: DResult<DTimeTravelPreview>): DriveTimeTravel {
        if (step != Step.PREVIEWING) return this
        return when (r) {
            is DResult.Ok -> copy(step = Step.PREVIEW, preview = r.value)
            else -> copy(step = Step.PICK, error = code(r))
        }
    }

    /** Something to do, and nothing running. */
    val canRun: Boolean get() = step == Step.PREVIEW && (preview?.total ?: 0) > 0 && atMs != null

    fun running(): DriveTimeTravel = if (canRun) copy(step = Step.RUNNING, error = null) else this

    fun ran(r: DResult<DTimeTravelResult>): DriveTimeTravel {
        if (step != Step.RUNNING) return this
        return when (r) {
            is DResult.Ok -> copy(step = Step.DONE, result = r.value)
            else -> copy(step = Step.PREVIEW, error = code(r))
        }
    }

    /** "Rückgängig": once, when Drive said where "before" was. */
    val canUndo: Boolean get() = step == Step.DONE && !undone && result?.before != null

    fun undoing(): DriveTimeTravel = if (canUndo) copy(step = Step.UNDOING, error = null) else this

    fun undid(r: DResult<DTimeTravelResult>): DriveTimeTravel {
        if (step != Step.UNDOING) return this
        return when (r) {
            is DResult.Ok -> copy(step = Step.DONE, result = r.value, undone = true)
            else -> copy(step = Step.DONE, error = code(r))
        }
    }

    /** The time of [atMs] as the server takes it. */
    val atIso: String? get() = atMs?.let { iso(it) }

    companion object {
        const val FUTURE = "tt_future"
        /** More steps than Drive does in one go (`400 invalid { field: 'at', ops, max }`): `tt_too_many:<max>`. */
        const val TOO_MANY = "tt_too_many"

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

        private fun code(r: DResult<*>): String = when (r) {
            is DResult.Failed -> {
                val body = r.body
                val field = body?.get("field")?.takeIf { it.isJsonPrimitive }?.asString
                val max = body?.get("max")?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }
                when {
                    field == "at" && max != null -> "$TOO_MANY:$max"
                    field == "at" -> FUTURE
                    else -> r.code
                }
            }
            DResult.Offline -> "offline"
            DResult.NotConfigured -> "not_configured"
            is DResult.Ok -> "invalid"
        }
    }
}
