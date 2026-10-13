package com.gatecontrol.android.location

import android.content.Context
import android.location.Location
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.LocationDeleteResponse
import com.gatecontrol.android.network.LocationFix
import com.gatecontrol.android.network.LocationReportRequest
import com.gatecontrol.android.network.LocationReportResponse
import com.gatecontrol.android.network.LocationStatusResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

class LocationShareRepositoryTest {

    private val enabled = MutableStateFlow(true)
    private val lastSent = MutableStateFlow<String?>(null)
    private val settings = mockk<SettingsRepository> {
        every { getLocationSharing() } returns enabled
        every { getLocationLastSent() } returns lastSent
        coEvery { setLocationSharing(any()) } coAnswers { enabled.value = firstArg() }
        coEvery { setLocationLastSent(any()) } coAnswers { lastSent.value = firstArg() }
    }
    private val setup = mockk<SetupRepository> { every { getServerUrl() } returns "https://gc.example.com" }
    private val api = mockk<ApiClient>()
    private val provider = mockk<ApiClientProvider> { every { getClient(any()) } returns api }
    private val context = mockk<Context>(relaxed = true)

    private fun repo() = LocationShareRepository(context, setup, provider, settings)

    private fun fix(): Location = mockk {
        every { latitude } returns 51.3397
        every { longitude } returns 12.3731
        every { hasAccuracy() } returns true
        every { accuracy } returns 12f
        every { hasAltitude() } returns false
        every { provider } returns "fused"
        every { time } returns 1_700_000_000_000
    }

    @BeforeEach
    fun mockLocator() {
        mockkObject(DeviceLocator)
    }

    @AfterEach
    fun unmockLocator() {
        unmockkObject(DeviceLocator)
    }

    @Test
    fun `status maps allowed, not allowed and older servers`() = runTest {
        val r = repo()
        coEvery { api.getLocationStatus() } returns Response.success(
            LocationStatusResponse(ok = true, allowed = true, last = LocationFix(1.0, 2.0, 8.0, 1234), keep = 20, retentionDays = 30),
        )
        val allowed = r.refreshServer() as LocationServerStatus.Allowed
        assertEquals(SentLocation(1234, 8.0), allowed.last)
        assertEquals(30, allowed.retentionDays)

        coEvery { api.getLocationStatus() } returns Response.success(LocationStatusResponse(ok = true, allowed = false))
        assertEquals(LocationServerStatus.NotAllowed, r.refreshServer())

        coEvery { api.getLocationStatus() } returns Response.error(404, "".toResponseBody())
        assertEquals(LocationServerStatus.Unsupported, r.refreshServer())
    }

    @Test
    fun `a 403 of the status call (token problem) keeps the last known state`() = runTest {
        val r = repo()
        coEvery { api.getLocationStatus() } returns Response.error(403, """{"error":"no_peer"}""".toResponseBody())
        assertEquals(LocationServerStatus.Unknown, r.refreshServer())
    }

    @Test
    fun `share sends one position and remembers it`() = runTest {
        coEvery { DeviceLocator.locate(any()) } returns LocateResult.Fix(fix())
        val sent = slot<LocationReportRequest>()
        coEvery { api.reportLocation(capture(sent)) } returns Response.success(LocationReportResponse(ok = true, at = 1_700_000_000_500))
        val result = repo().share()
        assertEquals(LocationShareState.Sent(SentLocation(1_700_000_000_500, 12.0)), result)
        assertEquals(51.3397, sent.captured.lat)
        assertEquals(12.0, sent.captured.accuracy)
        assertNull(sent.captured.altitude)
        assertEquals("fused", sent.captured.provider)
        assertEquals(SentLocation(1_700_000_000_500, 12.0), LocationShareRepository.decode(lastSent.value))
    }

    @Test
    fun `nothing is sent without permission, with location off or when switched off`() = runTest {
        coEvery { DeviceLocator.locate(any()) } returns LocateResult.NoPermission
        assertEquals(LocationShareState.Failed(LocationFailure.NO_PERMISSION), repo().share())
        coEvery { DeviceLocator.locate(any()) } returns LocateResult.LocationOff
        assertEquals(LocationShareState.Failed(LocationFailure.LOCATION_OFF), repo().share())
        enabled.value = false
        coEvery { DeviceLocator.locate(any()) } returns LocateResult.Fix(fix())
        assertEquals(LocationShareState.Failed(LocationFailure.NOT_ALLOWED), repo().share())
        coVerify(exactly = 0) { api.reportLocation(any()) }
    }

    @Test
    fun `403 location_not_allowed vs other errors`() = runTest {
        coEvery { DeviceLocator.locate(any()) } returns LocateResult.Fix(fix())
        val r = repo()
        coEvery { api.reportLocation(any()) } returns Response.error(403, """{"ok":false,"error":"location_not_allowed"}""".toResponseBody())
        assertEquals(LocationShareState.Failed(LocationFailure.NOT_ALLOWED), r.share())
        assertEquals(LocationServerStatus.NotAllowed, r.server.value)

        val r2 = repo()
        coEvery { api.reportLocation(any()) } returns Response.error(403, """{"ok":false,"error":"binding_mismatch"}""".toResponseBody())
        assertEquals(LocationShareState.Failed(LocationFailure.NETWORK), r2.share())
        assertEquals(LocationServerStatus.Unknown, r2.server.value)

        coEvery { api.reportLocation(any()) } returns Response.error(404, "".toResponseBody())
        assertEquals(LocationShareState.Failed(LocationFailure.UNSUPPORTED), r2.share())
    }

    @Test
    fun `delete clears the remembered position`() = runTest {
        lastSent.value = LocationShareRepository.encode(SentLocation(5, 3.0))
        coEvery { api.deleteLocations() } returns Response.success(LocationDeleteResponse(ok = true, deleted = 3))
        assertTrue(repo().deleteOnServer())
        assertNull(lastSent.value)
    }

    @Test
    fun `stored value round trip and broken storage`() {
        val s = SentLocation(1_700_000_000_000, null)
        assertEquals(s, LocationShareRepository.decode(LocationShareRepository.encode(s)))
        assertNull(LocationShareRepository.decode("{broken"))
        assertNull(LocationShareRepository.decode("""{"at":0}"""))
        assertNull(LocationShareRepository.decode(null))
    }

    @Test
    fun `active only while working and after sending`() {
        assertTrue(LocationShareState.Locating.active)
        assertTrue(LocationShareState.Sent(SentLocation(1, null)).active)
        assertEquals(false, LocationShareState.Idle.active)
        assertEquals(false, LocationShareState.Failed(LocationFailure.NO_FIX).active)
    }
}
