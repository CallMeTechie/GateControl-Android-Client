package com.gatecontrol.android.familie

/** Outcome of a call to a plugin's app API. */
sealed interface FamilieResult<out T> {
    data class Ok<T>(val value: T) : FamilieResult<T>

    /**
     * The server answered with an error: [code] from `{ok:false, code}` or
     * `http_<status>`. [retryAfter] (s) with too_soon / daily_limit;
     * [requestId] when a locate request was refused because one is open;
     * [field] names the refused input of an `invalid` answer (e.g.
     * `contacts.0.phone`); [detail] the driver of an `already_taken` ride.
     */
    data class Failed(
        val code: String,
        val http: Int,
        val retryAfter: Int? = null,
        val requestId: Int? = null,
        val field: String? = null,
        val detail: String? = null,
    ) : FamilieResult<Nothing>

    /** No answer (network, VPN, timeout). */
    data object Offline : FamilieResult<Nothing>

    data object NotConfigured : FamilieResult<Nothing>
}

/** What a create call returned, when the answer carries it. */
data class Created<T>(val value: T?)

fun <T> FamilieResult<T>.valueOrNull(): T? = (this as? FamilieResult.Ok)?.value

fun FamilieResult<*>.errorCode(): String? = when (this) {
    is FamilieResult.Failed -> code
    FamilieResult.Offline -> FamiliePluginCalls.CODE_OFFLINE
    FamilieResult.NotConfigured -> FamiliePluginCalls.CODE_OFFLINE
    is FamilieResult.Ok -> null
}
