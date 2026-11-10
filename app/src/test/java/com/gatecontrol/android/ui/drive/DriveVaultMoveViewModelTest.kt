package com.gatecontrol.android.ui.drive

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveOffline
import com.gatecontrol.android.drive.DriveOfflineStore
import com.gatecontrol.android.drive.DriveOps
import com.gatecontrol.android.drive.DriveSettings
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.DriveUploadNetwork
import com.gatecontrol.android.drive.vault.DriveVaultLock
import com.gatecontrol.android.drive.vault.DriveVaultMove
import com.gatecontrol.android.drive.vault.DriveVaultMove.Origin
import com.gatecontrol.android.drive.vault.DriveVaultSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The vault page puts picked files into the vault as a move: an original goes only after its own upload went through
 * (verified by the session), never for a file that failed or was skipped; a batch stops at the first failure and only
 * the files before it lose their originals; gallery originals wait for Android's one dialog; a file picked from Drive
 * itself is deleted on the server (trash everywhere, then purged).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveVaultMoveViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() = Dispatchers.resetMain()

    private val vault = DVault("v".repeat(22), 5L, "a1", 1L, "nc", 1, null, false, emptyList(), 0)
    private val vaultNode = DNode(5, "a1", 1, "abcd".repeat(8), true, 0, null, null, 1, null, null, null, vaultId = vault.id, nameCipher = "nc")
    private val folder = DFolder(vaultNode, emptyList(), "write", emptyList(), 0, null)

    private val api = mockk<DriveApi>(relaxed = true).also {
        coEvery { it.vault(vault.id) } returns DResult.Ok(vault)
        coEvery { it.folder(any(), any(), any(), any(), any()) } returns DResult.Ok(folder)
    }
    private val session = mockk<DriveVaultSession>(relaxed = true).also {
        every { it.unlocked(any()) } returns true
        every { it.locks } returns MutableSharedFlow()
        coEvery { it.open(any()) } returns DResult.Ok(DriveVaultSession.Access.Open)
        every { it.nameOf(any<DNode>()) } returns "Ordner"
        every { it.nameOf(any<DVault>()) } returns "Tresor"
    }
    private val lock = mockk<DriveVaultLock>(relaxed = true).also {
        coEvery { it.busy<Any?>(any()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
    }
    private val live = mockk<DriveLive>(relaxed = true).also {
        every { it.vault } returns MutableSharedFlow()
        every { it.changed } returns MutableSharedFlow()
        every { it.me } returns MutableStateFlow(null)
        every { it.ops } returns MutableSharedFlow()
    }
    private val marks = mockk<DriveOffline>(relaxed = true).also { every { it.ids } returns MutableStateFlow(emptySet()) }
    private val store = mockk<DriveOfflineStore>(relaxed = true).also { every { it.copies } returns MutableStateFlow(emptyMap()) }
    private val network = mockk<DriveUploadNetwork>().also { every { it.waitsForWifi() } returns false }

    /** The phone's side of the originals. */
    private class Phone(val origins: Map<Uri, Origin>, val sender: IntentSender? = null) : DriveVaultMove.Originals {
        val deleted = mutableListOf<Uri>()
        val requested = mutableListOf<List<Uri>>()
        override fun origin(uri: Uri) = origins[uri] ?: Origin.Unsupported
        override fun deleteDocument(uri: Uri): Boolean { deleted += uri; return true }
        override fun deleteFile(path: String) = true
        override fun allFilesAccess() = false
        override fun deleteMedia(uri: Uri) = true
        override fun deleteRequest(uris: List<Uri>): IntentSender? { requested += uris; return sender }
        override fun exists(uri: Uri) = false
    }

    private fun TestScope.model(phone: Phone): DriveVaultViewModel {
        val vm = DriveVaultViewModel(
            context = mockk<Context>(relaxed = true),
            savedStateHandle = SavedStateHandle(mapOf("vault" to vault.id)),
            api = api,
            session = session,
            live = live,
            lock = lock,
            settings = mockk<DriveSettings>(relaxed = true),
            network = network,
            ops = mockk<DriveOps>(relaxed = true),
            transfers = mockk<DriveTransfers>(relaxed = true),
            marks = marks,
            offlineStore = store,
            vaultMove = DriveVaultMove(api, phone, dispatcher),
        )
        // the page loads with a few steps on Dispatchers.IO: wait until it shows the folder
        val until = System.currentTimeMillis() + 5_000
        while (vm.ui.value !is DriveVaultViewModel.Ui.Ready && System.currentTimeMillis() < until) {
            advanceUntilIdle()
            Thread.sleep(5)
        }
        advanceUntilIdle()
        return vm
    }

    private fun up(uri: Uri, result: DResult<DNode>) {
        coEvery { session.upload(vault.id, 5L, uri, any(), any(), any()) } returns result
    }

    private val done = DResult.Ok(vaultNode.copy(id = 99, isDir = false, size = 120))

    @Test
    fun `the originals go only after their own upload went through, a batch stops at the first failure`() = runTest(dispatcher) {
        val a = mockk<Uri>()
        val b = mockk<Uri>()
        val c = mockk<Uri>()
        val phone = Phone(mapOf(a to Origin.Document(a, true, null), b to Origin.Document(b, true, null), c to Origin.Document(c, true, null)))
        up(a, done)
        up(b, DResult.Failed("failed", 500))
        up(c, done)
        val vm = model(phone)
        assertTrue(vm.ui.value is DriveVaultViewModel.Ui.Ready)
        vm.upload(listOf(a, b, c)) { "x.pdf" }
        advanceUntilIdle()
        // a went up: its original is gone; b failed: kept; c was never tried: kept
        assertEquals(listOf(a), phone.deleted)
        coVerify(exactly = 0) { session.upload(vault.id, 5L, c, any(), any(), any()) }
        assertEquals(DriveVaultMove.Outcome(moved = 1, deleted = 1), vm.moved.value)
        assertEquals("failed", vm.message.value)
    }

    @Test
    fun `a failed upload deletes nothing and says nothing about originals`() = runTest(dispatcher) {
        val a = mockk<Uri>()
        val phone = Phone(mapOf(a to Origin.Document(a, true, null)))
        up(a, DResult.Failed(DriveVaultSession.CODE_VERIFY, 0))
        val vm = model(phone)
        vm.upload(listOf(a)) { "x.pdf" }
        advanceUntilIdle()
        assertTrue(phone.deleted.isEmpty())
        assertNull(vm.moved.value)
        assertEquals(DriveVaultSession.CODE_VERIFY, vm.message.value)
    }

    @Test
    fun `gallery originals wait for Android's one dialog, its answer decides`() = runTest(dispatcher) {
        val a = mockk<Uri>()
        val b = mockk<Uri>()
        val sender = mockk<IntentSender>()
        val phone = Phone(mapOf(a to Origin.Media(a), b to Origin.Media(b)), sender = sender)
        up(a, done)
        up(b, done)
        val vm = model(phone)
        vm.upload(listOf(a, b)) { "foto.jpg" }
        advanceUntilIdle()
        assertEquals(listOf(listOf(a, b)), phone.requested)
        assertNotNull(vm.deleteAsk.value)
        val ask = vm.deleteAsk.value!!
        assertSame(sender, ask.sender)
        // the dialog opens once only
        assertTrue(ask.take())
        assertTrue(!ask.take())
        assertNull(vm.moved.value)
        vm.deleteAnswered(ok = true)
        advanceUntilIdle()
        assertNull(vm.deleteAsk.value)
        assertEquals(DriveVaultMove.Outcome(moved = 2, deleted = 2), vm.moved.value)
    }

    @Test
    fun `a no in Android's dialog keeps the gallery originals`() = runTest(dispatcher) {
        val a = mockk<Uri>()
        val phone = Phone(mapOf(a to Origin.Media(a)), sender = mockk())
        up(a, done)
        val vm = model(phone)
        vm.upload(listOf(a)) { "foto.jpg" }
        advanceUntilIdle()
        vm.deleteAnswered(ok = false)
        advanceUntilIdle()
        assertEquals(DriveVaultMove.Outcome(moved = 1, declined = 1), vm.moved.value)
    }

    @Test
    fun `a file picked from Drive itself is deleted on the server after its upload`() = runTest(dispatcher) {
        val a = mockk<Uri>()
        coEvery { api.delete(77L, everywhere = true) } returns DResult.Ok(Unit)
        coEvery { api.purge(77L) } returns DResult.Ok(Unit)
        up(a, done)
        val vm = model(Phone(mapOf(a to Origin.Drive(77L))))
        vm.upload(listOf(a)) { "vertrag.pdf" }
        advanceUntilIdle()
        coVerify { api.delete(77L, everywhere = true) }
        coVerify { api.purge(77L) }
        assertEquals(DriveVaultMove.Outcome(moved = 1, deleted = 1), vm.moved.value)
    }

    @Test
    fun `a new version moves too - its original goes after the upload`() = runTest(dispatcher) {
        val a = mockk<Uri>()
        val phone = Phone(mapOf(a to Origin.Document(a, true, null)))
        up(a, done)
        val vm = model(phone)
        vm.uploadVersion(vaultNode.copy(id = 99, isDir = false), "x.pdf", a)
        advanceUntilIdle()
        assertEquals(listOf(a), phone.deleted)
        assertEquals(DriveVaultMove.Outcome(moved = 1, deleted = 1), vm.moved.value)
        vm.movedShown()
        assertNull(vm.moved.value)
    }
}
