package com.gatecontrol.android.infocard

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contents of the "Info" card on the start tab: the plugins' start tiles
 * (`GET /api/v1/client/tiles`, kept in memory only) and what the person
 * chose to see, stored per login (server + token) in the DataStore.
 */
@Singleton
class InfoCardRepository @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()

    private val _tiles = MutableStateFlow(InfoTilesState())
    val tiles: StateFlow<InfoTilesState> = _tiles.asStateFlow()

    init {
        // Another server or token: forget the old one's tiles.
        scope.launch {
            setupRepository.revision.drop(1).collect { _tiles.value = InfoTilesState() }
        }
    }

    /** The current login's choice; follows a change of server or token. */
    val selection: Flow<InfoSelection> =
        combine(settingsRepository.getInfoCard(), setupRepository.revision) { raw, _ -> InfoCardStore.read(raw, owner()) }

    private fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    /** Asks the server; a failure keeps the tiles known so far. */
    suspend fun refresh(): InfoTilesState = mutex.withLock {
        val serverUrl = setupRepository.getServerUrl()
        if (serverUrl.isBlank()) return InfoTilesState().also { _tiles.value = it }
        val next = try {
            val response = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.getClient(serverUrl).getClientTiles(lang()) }
            when {
                response == null -> _tiles.value.copy(status = failedOrKept())
                response.code() == 404 -> InfoTilesState(InfoTilesState.Status.UNSUPPORTED)
                !response.isSuccessful -> _tiles.value.copy(status = failedOrKept())
                else -> InfoTilesState(InfoTilesState.Status.LOADED, InfoTile.sanitize(response.body()?.tiles.orEmpty()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("Start tiles unavailable (%s)", e.javaClass.simpleName)
            _tiles.value.copy(status = failedOrKept())
        }
        _tiles.value = next
        next
    }

    /** A failed refresh after an older server's 404 stays "unsupported". */
    private fun failedOrKept(): InfoTilesState.Status =
        if (_tiles.value.status == InfoTilesState.Status.UNSUPPORTED) InfoTilesState.Status.UNSUPPORTED else InfoTilesState.Status.FAILED

    /** Atomic change of the current login's choice. */
    suspend fun update(transform: (InfoSelection) -> InfoSelection) {
        val owner = owner()
        if (owner.isEmpty()) return
        settingsRepository.updateInfoCard { raw -> InfoCardStore.write(raw, owner, transform(InfoCardStore.read(raw, owner))) }
    }

    /** The app's own UI language, so titles match it. */
    private suspend fun lang(): String =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}
