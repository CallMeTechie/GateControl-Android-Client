package com.gatecontrol.android.push.routes

import com.gatecontrol.android.notify.PushEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Answer of a load. */
sealed interface RoutesLoad {
    data class Ok(val routes: NotifyRoutes) : RoutesLoad

    /** An older server (404): no such page, its entries stay hidden. */
    data object Unsupported : RoutesLoad

    /** Not reached, or a broken answer: try again later. */
    data object Failed : RoutesLoad
}

/** Answer of a change. */
sealed interface RoutesSave {
    /**
     * Saved; [routes] the server's new state when it sent one; [chat] the own
     * chat of the source a change to "own" made sure of (core 1.192+, else null).
     */
    data class Ok(val routes: NotifyRoutes?, val chat: NotifyRouteChat? = null) : RoutesSave

    /** Refused: [code] `family_not_allowed`, `locked`, `no_sink`, `forbidden`, `unsupported`, `network`, … */
    data class Refused(val code: String) : RoutesSave
}

/**
 * "Wo Mitteilungen erscheinen" (core 1.191+): reads and changes where each
 * kind of notification appears, with the device token like the other push
 * calls. [supported] says whether the server has the page (null: not asked
 * yet) — older servers answer 404 and the entries to it stay hidden.
 */
@Singleton
class NotifyRoutesRepository @Inject constructor(
    private val endpoint: PushEndpoint,
) {
    companion object {
        private const val TIMEOUT_MS = 15_000L
        const val ERR_FAMILY = "family_not_allowed"
        const val ERR_LOCKED = "locked"
        const val ERR_NO_SINK = "no_sink"
        const val ERR_FORBIDDEN = "forbidden"
        const val ERR_UNSUPPORTED = "unsupported"
        const val ERR_NETWORK = "network"
        const val ERR_FAILED = "failed"

        /** The refusal of a PUT: the server's code, else one from the HTTP status. */
        fun refusal(status: Int, body: String?): String {
            val code = NotifyRoutesJson.errorCode(body)
            return when (status) {
                403 -> code?.takeIf { it == ERR_FAMILY } ?: if (code == null || code == "forbidden") ERR_FORBIDDEN else code
                409 -> code?.takeIf { it == ERR_LOCKED || it == ERR_NO_SINK } ?: code ?: ERR_LOCKED
                404 -> code ?: ERR_UNSUPPORTED
                else -> code ?: ERR_FAILED
            }
        }
    }

    private val _supported = MutableStateFlow<Boolean?>(null)

    /** The server has the page (true), not (false, 404), or was not asked yet (null). */
    val supported: StateFlow<Boolean?> = _supported.asStateFlow()

    /** Asks once whether the server has the page (for the entries to it); known answers are kept. */
    suspend fun probe() {
        if (_supported.value == null) load()
    }

    suspend fun load(): RoutesLoad {
        val url = endpoint.serverUrl()
        if (url.isEmpty()) return RoutesLoad.Failed
        return try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { endpoint.api(url).getPushRoutes() } ?: return RoutesLoad.Failed
            when {
                response.code() == 404 -> {
                    response.errorBody()?.close()
                    _supported.value = false
                    RoutesLoad.Unsupported
                }
                !response.isSuccessful -> {
                    response.errorBody()?.close()
                    Timber.d("Routes: HTTP %d", response.code())
                    RoutesLoad.Failed
                }
                else -> {
                    val routes = NotifyRoutesJson.parse(response.body()) ?: return RoutesLoad.Failed
                    _supported.value = true
                    RoutesLoad.Ok(routes)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Routes not read (%s)", e.javaClass.simpleName)
            RoutesLoad.Failed
        }
    }

    suspend fun set(topic: String, dest: NotifyDest): RoutesSave {
        val url = endpoint.serverUrl()
        if (url.isEmpty()) return RoutesSave.Refused(ERR_NETWORK)
        return try {
            val response: Response<com.google.gson.JsonObject> =
                withTimeoutOrNull(TIMEOUT_MS) { endpoint.api(url).putPushRoutes(NotifyRoutesJson.body(topic, dest)) }
                    ?: return RoutesSave.Refused(ERR_NETWORK)
            if (response.isSuccessful) {
                // The answer has the GET's form; an answer without groups just says "saved".
                val body = response.body()
                RoutesSave.Ok(
                    routes = NotifyRoutesJson.parse(body)?.takeIf { it.groups.isNotEmpty() },
                    chat = if (dest == NotifyDest.OWN) NotifyRoutesJson.chat(body) else null,
                )
            } else {
                val body = response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                RoutesSave.Refused(refusal(response.code(), body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Route not saved (%s)", e.javaClass.simpleName)
            RoutesSave.Refused(ERR_NETWORK)
        }
    }
}
