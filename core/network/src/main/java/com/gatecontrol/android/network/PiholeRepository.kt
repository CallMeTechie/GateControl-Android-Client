package com.gatecontrol.android.network

import com.gatecontrol.android.data.SetupRepository
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class PiholeRepository @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider
) {
    private fun client() = setupRepository.getServerUrl().takeIf { it.isNotBlank() }
        ?.let { apiClientProvider.getClient(it) }

    suspend fun getSummary(): PiholeSummary? = try {
        client()?.getPiholeSummary()?.takeIf { it.ok }?.data
    } catch (e: Exception) { Timber.w(e, "pihole getSummary failed"); null }

    suspend fun getHistory(): List<PiholeHistoryPoint> = try {
        client()?.getPiholeHistory()?.takeIf { it.ok }?.data ?: emptyList()
    } catch (e: Exception) { Timber.w(e, "pihole getHistory failed"); emptyList() }

    suspend fun getTopDomains(): List<PiholeTopDomain> = try {
        client()?.getPiholeTopDomains()?.takeIf { it.ok }?.data ?: emptyList()
    } catch (e: Exception) { Timber.w(e, "pihole getTopDomains failed"); emptyList() }

    suspend fun getTopClients(): List<PiholeTopClient> = try {
        client()?.getPiholeTopClients()?.takeIf { it.ok }?.data ?: emptyList()
    } catch (e: Exception) { Timber.w(e, "pihole getTopClients failed"); emptyList() }

    suspend fun getQueryTypes(): Map<String, Long> = try {
        client()?.getPiholeQueryTypes()?.takeIf { it.ok }?.data ?: emptyMap()
    } catch (e: Exception) { Timber.w(e, "pihole getQueryTypes failed"); emptyMap() }

    suspend fun getHealth(): PiholeHealth? = try {
        client()?.getPiholeHealth()?.takeIf { it.ok }?.data
    } catch (e: Exception) { Timber.w(e, "pihole getHealth failed"); null }

    /** Returns true on success. enabled=false pauses; timerSec null = permanent. */
    suspend fun setBlocking(enabled: Boolean, timerSec: Int?): Boolean = try {
        client()?.setPiholeBlocking(PiholeBlockingRequest(enabled, timerSec))?.ok ?: false
    } catch (e: Exception) { Timber.w(e, "pihole setBlocking failed"); false }
}
