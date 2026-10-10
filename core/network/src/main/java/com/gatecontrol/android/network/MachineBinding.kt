package com.gatecontrol.android.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.HttpException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Rejections of the server's machine binding (Gerätebindung). */
enum class MachineBindingError {
    /** The token is bound to another device (403). */
    MISMATCH,

    /** Binding is active but the request carried no fingerprint (403, enroll 400). */
    REQUIRED,

    /** The fingerprint was malformed (400). */
    INVALID;

    companion object {
        // The server answers with a translated message (en/de) on the client
        // API and with an error code on enroll — match both.
        private val MISMATCH_RE = Regex(
            "binding_mismatch|bound to a different machine|an eine andere Maschine gebunden",
            RegexOption.IGNORE_CASE,
        )
        private val REQUIRED_RE = Regex(
            "fingerprint_required|machine fingerprint required|Fingerprint erforderlich",
            RegexOption.IGNORE_CASE,
        )
        private val INVALID_RE = Regex(
            "fingerprint_invalid|invalid machine fingerprint|valid machine fingerprint required|Fingerprint-Format",
            RegexOption.IGNORE_CASE,
        )

        /** Classify an HTTP error response; null when it is not a binding rejection. */
        fun fromResponse(code: Int, body: String?): MachineBindingError? {
            if (code != 400 && code != 403 || body.isNullOrEmpty()) return null
            return when {
                MISMATCH_RE.containsMatchIn(body) -> MISMATCH
                REQUIRED_RE.containsMatchIn(body) -> REQUIRED
                INVALID_RE.containsMatchIn(body) -> INVALID
                else -> null
            }
        }

        /**
         * Classify a Retrofit [HttpException]. Reads (and so consumes) the
         * error body — callers that also need the body use [fromResponse].
         */
        fun from(e: Throwable): MachineBindingError? {
            if (e !is HttpException) return null
            val body = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            return fromResponse(e.code(), body)
        }
    }
}

/**
 * Watches client API responses for machine-binding rejections so the UI can
 * explain them, whichever request hit them first (most run in the
 * background). Cleared again by the next successful request to an endpoint
 * that checks the binding — e.g. after the admin reset it.
 */
@Singleton
class MachineBindingMonitor @Inject constructor() : Interceptor {

    private val _error = MutableStateFlow<MachineBindingError?>(null)
    val error: StateFlow<MachineBindingError?> = _error.asStateFlow()

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val path = response.request.url.encodedPath
        if (!path.contains(CLIENT_API)) return response
        val code = response.code
        if (code == 400 || code == 403) {
            val body = try {
                response.peekBody(MAX_PEEK).string()
            } catch (_: Exception) {
                null
            }
            MachineBindingError.fromResponse(code, body)?.let { err ->
                if (_error.value != err) Timber.w("Machine binding rejected the request: %s", err)
                _error.value = err
            }
        } else if (code in 200..399 && CHECKED_ENDPOINTS.any { path.endsWith(it) }) {
            _error.value = null
        }
        return response
    }

    fun clear() {
        _error.value = null
    }

    private companion object {
        const val CLIENT_API = "/api/v1/client/"
        const val MAX_PEEK = 4096L

        /** Endpoints that pass the server's binding check when they succeed. */
        val CHECKED_ENDPOINTS = listOf(
            "/client/heartbeat", "/client/status", "/client/peer-info", "/client/config",
            "/client/config/check", "/client/traffic", "/client/policy", "/client/support-bundle",
            "/client/register", "/client/enroll",
        )
    }
}
