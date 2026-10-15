package com.gatecontrol.android.profile

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Does the server know profiles? */
enum class ProfileSupport {
    /** Not asked yet and nothing cached. */
    UNKNOWN,
    SUPPORTED,
    /** Server before 1.168 (404): the feature is hidden. */
    UNSUPPORTED,
    /** A token without a user (machine / service token, 403 NO_USER): hidden. */
    NO_USER,
}

data class ProfileState(
    val profile: MyProfile? = null,
    val support: ProfileSupport = ProfileSupport.UNKNOWN,
) {
    /** Profile entry, own avatar on the start tab: only when the server has profiles. */
    val available: Boolean get() = support == ProfileSupport.SUPPORTED && profile != null
}

/** Result of a change; [code] names the reason of a failure (see [ProfileRepository.codeOf]). */
sealed interface ProfileResult {
    data object Ok : ProfileResult
    data class Failed(val code: String) : ProfileResult
}

/**
 * The own GateControl profile (core 1.168+): picture and favourite colour.
 * The last answer is cached in DataStore (bound to the login) for the
 * offline / fast start and refreshed on start, in the foreground (at most
 * every few minutes) and after each change.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val setupRepository: SetupRepository,
    private val settingsRepository: SettingsRepository,
    private val apiClientProvider: ApiClientProvider,
    private val avatarLoader: AvatarLoader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    @Volatile private var lastRefreshMs = 0L

    init {
        // The cached copy first, then whenever server or token change.
        scope.launch {
            setupRepository.revision.collect {
                lastRefreshMs = 0L
                mutex.withLock { loadCache() }
            }
        }
    }

    private fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    private suspend fun loadCache() {
        val cached = ProfileJson.decode(runCatching { settingsRepository.getProfileCache().first() }.getOrNull(), owner())
        _state.value = if (cached != null) ProfileState(cached, ProfileSupport.SUPPORTED) else ProfileState()
    }

    /** On start / foreground: refresh unless it was done a moment ago. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshMs < MIN_INTERVAL_MS) return
        lastRefreshMs = System.currentTimeMillis()
        scope.launch { refresh() }
    }

    /** Asks the server; keeps the cached profile when it cannot be reached. */
    suspend fun refresh(): ProfileState = mutex.withLock {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) {
            _state.value = ProfileState()
            return@withLock _state.value
        }
        try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(url).getMyProfile() }
                ?: return@withLock _state.value
            val body = response.body()
            when {
                response.isSuccessful && body != null -> ProfileJson.parse(body)?.let { store(it) }
                response.code() == 404 -> forget(ProfileSupport.UNSUPPORTED)
                response.code() == 403 -> forget(ProfileSupport.NO_USER)
                else -> Timber.d("Profile: HTTP %d", response.code())
            }
            if (!response.isSuccessful) response.errorBody()?.close()
            lastRefreshMs = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Profile unavailable (%s)", e.javaClass.simpleName)
        }
        _state.value
    }

    // Cache first, then the state: a concurrent loadCache() never reads an older copy.
    private suspend fun store(profile: MyProfile) {
        runCatching { settingsRepository.setProfileCache(ProfileJson.encode(profile, owner())) }
        _state.value = ProfileState(profile, ProfileSupport.SUPPORTED)
    }

    private suspend fun forget(support: ProfileSupport) {
        runCatching { settingsRepository.setProfileCache(null) }
        _state.value = ProfileState(null, support)
    }

    /** Uploads [image] ([mime]: image/webp | image/jpeg | image/png), ≤ 96 KB, square. */
    suspend fun uploadAvatar(image: ByteArray, mime: String): ProfileResult = change { url ->
        val response = apiClientProvider.getClient(url).putMyAvatar(image.toRequestBody(mime.toMediaType()))
        response.also { r ->
            // The picture is already here: the new (immutable) address shows it without a download.
            val newUrl = r.body()?.takeIf { r.isSuccessful }?.get("avatarUrl")
                ?.takeIf { it.isJsonPrimitive }?.asString?.let(ProfileJson::avatarUrl)
            if (newUrl != null) {
                AvatarUrls.resolve(url, newUrl)?.let { avatarLoader.prime(it, image) }
                _state.value.profile?.let { store(it.copy(avatarUrl = newUrl)) }
            }
        }
    }

    suspend fun removeAvatar(): ProfileResult = change { url ->
        apiClientProvider.getClient(url).deleteMyAvatar().also { r ->
            if (r.isSuccessful) _state.value.profile?.let { store(it.copy(avatarUrl = null)) }
        }
    }

    /** [key] from the palette, null = back to the server's default. */
    suspend fun setColor(key: String?): ProfileResult = change { url ->
        val json = if (key == null) """{"color":null}""" else """{"color":"${AvatarPalette.validKey(key) ?: return@change null}"}"""
        apiClientProvider.getClient(url).putMyColor(json.toRequestBody(JSON)).also { r ->
            val body = r.body()
            if (r.isSuccessful && body != null) {
                val newKey = body.get("color")?.takeIf { it.isJsonPrimitive }?.asString?.let(AvatarPalette::validKey)
                val hex = body.get("colorHex")?.takeIf { it.isJsonPrimitive }?.asString?.let(AvatarPalette::validHex)
                _state.value.profile?.let { p ->
                    store(p.copy(color = newKey ?: key, colorHex = hex ?: AvatarPalette.hexOf(newKey ?: key, p.palette) ?: p.colorHex))
                }
            }
        }
    }

    /** Runs a change, maps the answer, then refreshes the profile from the server. */
    private suspend fun change(call: suspend (serverUrl: String) -> Response<JsonObject>?): ProfileResult {
        val url = setupRepository.getServerUrl()
        if (url.isBlank()) return ProfileResult.Failed(CODE_OFFLINE)
        val result = try {
            val response = withTimeoutOrNull(WRITE_TIMEOUT_MS) { call(url) }
            when {
                response == null -> ProfileResult.Failed(CODE_OFFLINE)
                response.isSuccessful &&
                    response.body()?.get("ok")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean != false ->
                    ProfileResult.Ok
                else -> ProfileResult.Failed(codeOf(response.code(), errorCode(response))).also { response.errorBody()?.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Profile change failed (%s)", e.javaClass.simpleName)
            ProfileResult.Failed(CODE_OFFLINE)
        }
        if (result == ProfileResult.Ok) {
            lastRefreshMs = 0L
            scope.launch { refresh() }
        }
        return result
    }

    private fun errorCode(response: Response<JsonObject>): String? = runCatching {
        val raw = response.errorBody()?.string()?.take(4_000) ?: return null
        com.google.gson.JsonParser.parseString(raw).asJsonObject.get("code")?.asString
    }.getOrNull()

    companion object {
        const val CODE_OFFLINE = "offline"
        const val CODE_TOO_LARGE = "too_large"
        const val CODE_INVALID = "invalid"
        const val CODE_UNSUPPORTED_TYPE = "unsupported_type"
        const val CODE_INVALID_COLOR = "invalid_color"
        const val CODE_RATE_LIMITED = "rate_limited"
        const val CODE_UNSUPPORTED = "unsupported"
        const val CODE_NO_USER = "no_user"
        const val CODE_FAILED = "failed"

        private const val TIMEOUT_MS = 8_000L
        private const val WRITE_TIMEOUT_MS = 20_000L
        private const val MIN_INTERVAL_MS = 3 * 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Pure: HTTP status and the server's `code` → the reason the app shows.
         * Core 1.168: 403 NO_USER, 429 RATE_LIMITED, 415 UNSUPPORTED_TYPE,
         * 413 TOO_LARGE, 400 INVALID_IMAGE (`reason`), 400 INVALID_COLOR, 404 = older server.
         */
        fun codeOf(status: Int, serverCode: String?): String {
            val c = serverCode?.uppercase()
            return when {
                c == "NO_USER" -> CODE_NO_USER
                c == "TOO_LARGE" || c == "PAYLOAD_TOO_LARGE" || status == 413 -> CODE_TOO_LARGE
                c == "RATE_LIMITED" || c == "TOO_MANY_REQUESTS" || status == 429 -> CODE_RATE_LIMITED
                c == "UNSUPPORTED_TYPE" || status == 415 -> CODE_UNSUPPORTED_TYPE
                c == "INVALID_COLOR" -> CODE_INVALID_COLOR
                status == 404 -> CODE_UNSUPPORTED
                status == 403 -> CODE_NO_USER
                status == 400 || status == 422 || c?.startsWith("INVALID") == true -> CODE_INVALID
                else -> CODE_FAILED
            }
        }
    }
}
