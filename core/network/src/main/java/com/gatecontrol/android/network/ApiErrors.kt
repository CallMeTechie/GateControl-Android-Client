package com.gatecontrol.android.network

import retrofit2.Response

/** The `error` code (and a few numbers) of a JSON error answer `{ ok: false, error: "…" }`. Pure, unit-tested. */
object ApiErrors {
    private val ERROR_RE = Regex("\"error\"\\s*:\\s*\"([A-Za-z0-9_]{1,64})\"")

    /** `invalid_code` of `{"ok":false,"error":"invalid_code"}`; null when there is none. */
    fun code(body: String?): String? = body?.let { ERROR_RE.find(it)?.groupValues?.get(1) }

    /** A whole-number field such as `interval` or `retryAfter` of an error answer; null when missing. */
    fun number(body: String?, field: String): Long? =
        body?.let { Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*(\\d{1,9})").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** The error body of a failed [response] (read once, consumed). */
    fun body(response: Response<*>): String? = try {
        response.errorBody()?.use { it.string() }
    } catch (_: Exception) {
        null
    }
}
