package com.gatecontrol.android.update

import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayUpdaterTest {

    private val listener = slot<InstallStateUpdatedListener>()
    private val manager = mockk<AppUpdateManager>(relaxed = true) {
        every { registerListener(capture(listener)) } just runs
    }
    private val updater = PlayUpdater(manager)

    private fun info(availability: Int, status: Int = InstallStatus.UNKNOWN, code: Int = 12500) = mockk<AppUpdateInfo> {
        every { updateAvailability() } returns availability
        every { installStatus() } returns status
        every { availableVersionCode() } returns code
        every { isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) } returns true
        every { totalBytesToDownload() } returns 18_000_000
        every { bytesDownloaded() } returns 0
    }

    private fun installState(status: Int, done: Long = 0, total: Long = 0) = mockk<InstallState> {
        every { installStatus() } returns status
        every { bytesDownloaded() } returns done
        every { totalBytesToDownload() } returns total
    }

    @Test
    fun `no newer version is up to date`() = runTest {
        every { manager.appUpdateInfo } returns Tasks.forResult(info(UpdateAvailability.UPDATE_NOT_AVAILABLE))
        assertEquals(PlayUpdateState.UpToDate, updater.check())
    }

    @Test
    fun `a newer version on Play is offered`() = runTest {
        every { manager.appUpdateInfo } returns Tasks.forResult(info(UpdateAvailability.UPDATE_AVAILABLE))
        assertEquals(PlayUpdateState.Available(12500), updater.check())
    }

    @Test
    fun `an app not installed from Play is reported as such`() = runTest {
        every { manager.appUpdateInfo } returns Tasks.forException(InstallException(InstallErrorCode.ERROR_APP_NOT_OWNED))
        assertEquals(PlayUpdateState.NotFromPlay, updater.check())
    }

    @Test
    fun `download progress, then a restart installs it`() = runTest {
        every { manager.appUpdateInfo } returns Tasks.forResult(info(UpdateAvailability.UPDATE_AVAILABLE))
        updater.check()

        listener.captured.onStateUpdate(installState(InstallStatus.DOWNLOADING, done = 50, total = 200))
        assertEquals(PlayUpdateState.Downloading(25, 50, 200), updater.state.value)

        listener.captured.onStateUpdate(installState(InstallStatus.DOWNLOADED))
        assertEquals(PlayUpdateState.Downloaded, updater.state.value)

        updater.complete()
        verify { manager.completeUpdate() }
    }

    @Test
    fun `an update already downloaded is ready to install`() = runTest {
        every { manager.appUpdateInfo } returns
            Tasks.forResult(info(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, InstallStatus.DOWNLOADED))
        assertEquals(PlayUpdateState.Downloaded, updater.check())
    }

    @Test
    fun `a declined dialog keeps the update offered`() = runTest {
        every { manager.appUpdateInfo } returns Tasks.forResult(info(UpdateAvailability.UPDATE_AVAILABLE))
        updater.check()
        updater.declined()
        assertEquals(PlayUpdateState.Available(12500), updater.state.value)
    }

    @Test
    fun `a stopped download remembers how far it got`() = runTest {
        every { manager.appUpdateInfo } returns Tasks.forResult(info(UpdateAvailability.UPDATE_AVAILABLE, code = 99_00_00))
        updater.check()
        assertEquals(PlayUpdateTarget(99_00_00, 18_000_000), updater.target.value)

        listener.captured.onStateUpdate(installState(InstallStatus.DOWNLOADING, done = 122, total = 200))
        listener.captured.onStateUpdate(installState(InstallStatus.FAILED))
        assertEquals(PlayUpdateState.DownloadFailed(61), updater.state.value)
    }

    @Test
    fun `no update clears the target`() = runTest {
        every { manager.appUpdateInfo } returns Tasks.forResult(info(UpdateAvailability.UPDATE_NOT_AVAILABLE, code = 0))
        updater.check()
        assertEquals(null, updater.target.value)
    }
}
