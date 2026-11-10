package com.gatecontrol.android.drive.vault

import android.content.IntentSender
import android.net.Uri
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.vault.DriveVaultMove.Origin
import com.gatecontrol.android.drive.vault.DriveVaultMove.Outcome
import com.gatecontrol.android.drive.vault.DriveVaultMove.Removal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "In Tresor verschieben": what becomes of the original of a file whose encrypted upload went through — per place
 * (Drive, the gallery, a document of another app, a file of the phone), and how a batch adds up.
 */
class DriveVaultMoveTest {

    /** The phone's side: what each uri is and what deleting it does. */
    private class FakeOriginals(
        val origins: Map<Uri, Origin>,
        var allFiles: Boolean = false,
        val documentsGo: Boolean = true,
        val filesGo: Boolean = true,
        val sender: IntentSender? = null,
        val stillThere: Set<Uri> = emptySet(),
    ) : DriveVaultMove.Originals {
        val deletedDocs = mutableListOf<Uri>()
        val deletedFiles = mutableListOf<String>()
        val deletedMedia = mutableListOf<Uri>()
        val requested = mutableListOf<List<Uri>>()
        override fun origin(uri: Uri) = origins[uri] ?: Origin.Unsupported
        override fun deleteDocument(uri: Uri): Boolean { deletedDocs += uri; return documentsGo }
        override fun deleteFile(path: String): Boolean { deletedFiles += path; return filesGo }
        override fun allFilesAccess() = allFiles
        override fun deleteMedia(uri: Uri): Boolean { deletedMedia += uri; return true }
        override fun deleteRequest(uris: List<Uri>): IntentSender? { requested += uris; return sender }
        override fun exists(uri: Uri) = uri in stillThere
    }

    private val api = mockk<DriveApi>()
    private fun uri() = mockk<Uri>()

    private fun node(size: Long, sha: String?) = DNode(7, "a1", 2, "abcd".repeat(8), false, size, null, "m", 1, null, null, null, sha256 = sha)

    // ─── a file of Drive ────────────────────────────────────────────

    @Test
    fun `a file of Drive goes into the trash everywhere and then out of it for good`() = runTest {
        val u = uri()
        coEvery { api.delete(42L, everywhere = true) } returns DResult.Ok(Unit)
        coEvery { api.purge(42L) } returns DResult.Ok(Unit)
        val move = DriveVaultMove(api, FakeOriginals(mapOf(u to Origin.Drive(42L))))
        assertEquals(Removal.Deleted, move.remove(u))
        coVerifyOrder {
            api.delete(42L, everywhere = true)
            api.purge(42L)
        }
    }

    @Test
    fun `a file of Drive whose purge fails is still in Drive's trash`() = runTest {
        val u = uri()
        coEvery { api.delete(42L, everywhere = true) } returns DResult.Ok(Unit)
        coEvery { api.purge(42L) } returns DResult.Failed("forbidden", 403)
        val move = DriveVaultMove(api, FakeOriginals(mapOf(u to Origin.Drive(42L))))
        assertEquals(Removal.InDriveTrash, move.remove(u))
    }

    @Test
    fun `a file of Drive that may not be deleted stays and is never purged`() = runTest {
        val u = uri()
        coEvery { api.delete(42L, everywhere = true) } returns DResult.Failed("forbidden", 403)
        val move = DriveVaultMove(api, FakeOriginals(mapOf(u to Origin.Drive(42L))))
        assertEquals(Removal.Kept, move.remove(u))
        coVerify(exactly = 0) { api.purge(any()) }
    }

    // ─── the gallery, documents, files ──────────────────────────────

    @Test
    fun `a gallery entry waits for Android's dialog, with all files access it goes at once`() = runTest {
        val u = uri()
        val fake = FakeOriginals(mapOf(u to Origin.Media(u)))
        val move = DriveVaultMove(api, fake)
        assertEquals(Removal.Ask(u), move.remove(u))
        assertTrue(fake.deletedMedia.isEmpty())
        fake.allFiles = true
        assertEquals(Removal.Deleted, move.remove(u))
        assertEquals(listOf(u), fake.deletedMedia)
    }

    @Test
    fun `a document that allows deleting is deleted through its app`() = runTest {
        val u = uri()
        val fake = FakeOriginals(mapOf(u to Origin.Document(u, deletable = true, media = null)))
        assertEquals(Removal.Deleted, DriveVaultMove(api, fake).remove(u))
        assertEquals(listOf(u), fake.deletedDocs)
    }

    @Test
    fun `a gallery document without delete (or whose delete fails) goes through the media store`() = runTest {
        val u = uri()
        val media = uri()
        val noDelete = FakeOriginals(mapOf(u to Origin.Document(u, deletable = false, media = media)))
        assertEquals(Removal.Ask(media), DriveVaultMove(api, noDelete).remove(u))
        assertTrue(noDelete.deletedDocs.isEmpty())
        val fails = FakeOriginals(mapOf(u to Origin.Document(u, deletable = true, media = media)), documentsGo = false)
        assertEquals(Removal.Ask(media), DriveVaultMove(api, fails).remove(u))
    }

    @Test
    fun `a document of an app that cannot delete, and an unknown uri, stay`() = runTest {
        val u = uri()
        val other = uri()
        val move = DriveVaultMove(api, FakeOriginals(mapOf(u to Origin.Document(u, deletable = false, media = null))))
        assertEquals(Removal.Kept, move.remove(u))
        assertEquals(Removal.Kept, move.remove(other))
    }

    @Test
    fun `a file of the phone is deleted, a failure keeps it`() = runTest {
        val u = uri()
        val ok = FakeOriginals(mapOf(u to Origin.LocalFile("/storage/emulated/0/Download/a.pdf")))
        assertEquals(Removal.Deleted, DriveVaultMove(api, ok).remove(u))
        assertEquals(listOf("/storage/emulated/0/Download/a.pdf"), ok.deletedFiles)
        val no = FakeOriginals(mapOf(u to Origin.LocalFile("/storage/emulated/0/a.pdf")), filesGo = false)
        assertEquals(Removal.Kept, DriveVaultMove(api, no).remove(u))
    }

    // ─── Android's dialog ───────────────────────────────────────────

    @Test
    fun `the gallery originals of a batch go in one dialog, a yes deletes those no longer found`() = runTest {
        val a = uri()
        val b = uri()
        val sender = mockk<IntentSender>()
        val fake = FakeOriginals(emptyMap(), sender = sender, stillThere = setOf(b))
        val move = DriveVaultMove(api, fake)
        assertNull(move.deleteRequest(emptyList()))
        assertSame(sender, move.deleteRequest(listOf(a, b)))
        assertEquals(listOf(listOf(a, b)), fake.requested)
        assertEquals(1, move.confirmAsked(listOf(a, b), ok = true))
        assertEquals(0, move.confirmAsked(listOf(a, b), ok = false))
    }

    // ─── a batch ────────────────────────────────────────────────────

    @Test
    fun `a batch adds up - deleted, in the trash, kept, asked and then answered`() {
        val a = uri()
        val b = uri()
        var o = Outcome()
        o += Removal.Deleted
        o += Removal.InDriveTrash
        o += Removal.Kept
        o += Removal.Ask(a)
        o += Removal.Ask(b)
        assertEquals(5, o.moved)
        assertEquals(listOf(a, b), o.ask)
        assertFalse(o.clean)
        val answered = o.asked(1)
        assertEquals(Outcome(moved = 5, deleted = 2, inTrash = 1, kept = 1, declined = 1), answered)
        // no dialog to ask with: they stay (kept, not declined)
        assertEquals(Outcome(moved = 5, deleted = 1, inTrash = 1, kept = 3), o.asked(0, answered = false))
    }

    @Test
    fun `clean only when every original went`() {
        assertFalse(Outcome().clean)
        assertTrue((Outcome() + Removal.Deleted + Removal.Deleted).clean)
        assertTrue((Outcome() + Removal.Ask(uri())).asked(1).clean)
        assertFalse((Outcome() + Removal.Ask(uri())).asked(0).clean)
    }

    // ─── verified ───────────────────────────────────────────────────

    @Test
    fun `the upload counts only when the vault's file is the ciphertext sent`() {
        val sha = "ab".repeat(32)
        assertTrue(DriveVaultMove.verified(node(1000, sha), 1000, sha))
        assertTrue(DriveVaultMove.verified(node(1000, sha.uppercase()), 1000, sha))
        // an older server without a hash: the size
        assertTrue(DriveVaultMove.verified(node(1000, null), 1000, sha))
        assertFalse(DriveVaultMove.verified(node(999, sha), 1000, sha))
        assertFalse(DriveVaultMove.verified(node(1000, "cd".repeat(32)), 1000, sha))
        assertFalse(DriveVaultMove.verified(node(1000, sha).copy(isDir = true), 1000, sha))
    }
}
