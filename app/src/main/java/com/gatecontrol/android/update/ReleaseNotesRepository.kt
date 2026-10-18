package com.gatecontrol.android.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Release notes of one update step, for the update screens. */
sealed interface ReleaseNotesState {
    data object Loading : ReleaseNotesState
    data class Loaded(val notes: List<ReleaseNote>) : ReleaseNotesState
    /** Offline, failed or nothing in the changelog: only the link to all changes is shown. */
    data object Unavailable : ReleaseNotesState
}

/**
 * "Was ist neu": the public CHANGELOG.md of the repository, fetched only
 * when an update card or sheet is shown and cached per update step. The
 * request is a plain GET of a public file; it carries no user data.
 */
@Singleton
class ReleaseNotesRepository internal constructor(
    private val prefs: UpdatePrefs,
    private val fetch: suspend () -> String,
) {
    @Inject constructor(prefs: UpdatePrefs) : this(prefs, { download() })

    private val _state = MutableStateFlow<Pair<Pair<Int, Int>, ReleaseNotesState>?>(null)
    /** (from, to) → notes of the step requested last. */
    val state: StateFlow<Pair<Pair<Int, Int>, ReleaseNotesState>?> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastFailure = 0L

    /** Loads the notes for [fromCode] → [toCode] unless already there. */
    suspend fun load(fromCode: Int, toCode: Int) = mutex.withLock {
        val key = fromCode to toCode
        val current = _state.value
        if (current?.first == key && current.second is ReleaseNotesState.Loaded) return@withLock
        prefs.notes(fromCode, toCode)?.takeIf { it.isNotEmpty() }?.let {
            _state.value = key to ReleaseNotesState.Loaded(it)
            return@withLock
        }
        // A failed fetch is tried again after a while, not on every recomposition.
        if (current?.first == key && current.second is ReleaseNotesState.Unavailable &&
            System.currentTimeMillis() - lastFailure < RETRY_MS
        ) return@withLock
        _state.value = key to ReleaseNotesState.Loading
        val notes = try {
            ChangelogParser.parse(fetch(), fromCode, toCode)
        } catch (e: CancellationException) {
            _state.value = null
            throw e
        } catch (e: Exception) {
            Timber.d("Changelog not available (%s)", e.javaClass.simpleName)
            null
        }
        if (notes.isNullOrEmpty()) {
            lastFailure = System.currentTimeMillis()
            _state.value = key to ReleaseNotesState.Unavailable
        } else {
            prefs.setNotes(fromCode, toCode, notes)
            _state.value = key to ReleaseNotesState.Loaded(notes)
        }
    }

    /** The state for [fromCode] → [toCode] (Loading until [load] ran). */
    fun stateFor(fromCode: Int, toCode: Int): ReleaseNotesState =
        _state.value?.takeIf { it.first == (fromCode to toCode) }?.second ?: ReleaseNotesState.Loading

    companion object {
        const val CHANGELOG_URL = "https://raw.githubusercontent.com/CallMeTechie/GateControl-Android-Client/main/CHANGELOG.md"
        const val RELEASES_URL = "https://github.com/CallMeTechie/GateControl-Android-Client/releases"
        private const val RETRY_MS = 5 * 60 * 1000L
        private const val MAX_BYTES = 512 * 1024L

        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        private suspend fun download(): String = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(CHANGELOG_URL).get().build()).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                r.body.source().let { src ->
                    src.request(MAX_BYTES)
                    src.buffer.readUtf8(minOf(src.buffer.size, MAX_BYTES))
                }
            }
        }
    }
}
