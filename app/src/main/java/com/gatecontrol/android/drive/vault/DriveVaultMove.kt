package com.gatecontrol.android.drive.vault

import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveDocs
import com.gatecontrol.android.drive.DriveDocumentsProvider
import com.gatecontrol.android.drive.device.DeviceFileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "In Tresor verschieben": a file put into a vault is moved, not copied — once its encrypted upload is through and
 * verified ([verified]), its unencrypted original goes, the way its place allows:
 *
 * - a file of Drive (picked through Drive's own provider in the files dialog): into the trash with "everywhere" (the
 *   other devices drop their copies) and then purged from the trash — the trash would keep the plaintext;
 * - a file of the gallery (MediaStore, also a picked "Bilder"/"Videos" document): with "Zugriff auf alle Dateien" at
 *   once, else Android asks first ([deleteRequest], `MediaStore.createDeleteRequest`, one dialog for the batch);
 * - a picked document whose app allows deleting (`FLAG_SUPPORTS_DELETE`): `DocumentsContract.deleteDocument`;
 * - a file of the shared storage ("Auf dem Gerät", a `file:` path): `File.delete`.
 *
 * Anything else stays ([Removal.Kept]) and the person is told to delete it.
 */
@Singleton
class DriveVaultMove(
    private val api: DriveApi,
    private val originals: Originals,
    /** Where the phone's side runs (tests: their own). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    @Inject constructor(api: DriveApi, android: AndroidOriginals) : this(api, android as Originals)

    /** Where a picked file lies. */
    sealed class Origin {
        /** A file of Drive (its node). */
        data class Drive(val nodeId: Long) : Origin()
        /** An entry of the media store (the gallery). */
        data class Media(val uri: Uri) : Origin()
        /** A document of another app; [deletable] it allows deleting, [media] its media store entry, if any. */
        data class Document(val uri: Uri, val deletable: Boolean, val media: Uri?) : Origin()
        /** A file of the phone's storage. */
        data class LocalFile(val path: String) : Origin()
        /** Nothing that could be deleted from here. */
        data object Unsupported : Origin()
    }

    /** What became of an original. */
    sealed class Removal {
        data object Deleted : Removal()
        /** A file of Drive in Drive's trash (purging failed): still there in plaintext. */
        data object InDriveTrash : Removal()
        /** Android asks first: [uri] goes into the batch's [deleteRequest]. */
        data class Ask(val uri: Uri) : Removal()
        data object Kept : Removal()
    }

    /** What the phone does (Android's side), replaced in tests. */
    interface Originals {
        fun origin(uri: Uri): Origin
        /** Deleted the document (true only when it is gone). */
        fun deleteDocument(uri: Uri): Boolean
        fun deleteFile(path: String): Boolean
        /** "Zugriff auf alle Dateien": media store entries go without asking. */
        fun allFilesAccess(): Boolean
        fun deleteMedia(uri: Uri): Boolean
        /** Android's dialog that deletes [uris] for good after the person's yes; null when there is none. */
        fun deleteRequest(uris: List<Uri>): IntentSender?
        /** The entry is still there (after the dialog). */
        fun exists(uri: Uri): Boolean
    }

    fun origin(uri: Uri): Origin = runCatching { originals.origin(uri) }.getOrDefault(Origin.Unsupported)

    /**
     * Removes the original [uri] of a file now in the vault. Call only after [verified] said yes for its upload — never
     * for a file that did not go up (or only partly).
     */
    suspend fun remove(uri: Uri): Removal = withContext(io) {
        when (val o = origin(uri)) {
            is Origin.Drive -> removeFromDrive(o.nodeId)
            is Origin.Media -> media(o.uri)
            is Origin.Document -> when {
                o.deletable && runCatching { originals.deleteDocument(o.uri) }.getOrDefault(false) -> Removal.Deleted
                o.media != null -> media(o.media)
                else -> Removal.Kept
            }
            is Origin.LocalFile -> if (runCatching { originals.deleteFile(o.path) }.getOrDefault(false)) Removal.Deleted else Removal.Kept
            Origin.Unsupported -> Removal.Kept
        }
    }

    private fun media(uri: Uri): Removal =
        if (originals.allFilesAccess() && runCatching { originals.deleteMedia(uri) }.getOrDefault(false)) Removal.Deleted else Removal.Ask(uri)

    /** Into Drive's trash ("everywhere": the other devices drop their copies too), then out of the trash for good. */
    private suspend fun removeFromDrive(nodeId: Long): Removal {
        val trashed = api.delete(nodeId, everywhere = true)
        if (trashed !is DResult.Ok) {
            Timber.i("Drive vault move: original not deleted (%s)", (trashed as? DResult.Failed)?.code ?: trashed.javaClass.simpleName)
            return Removal.Kept
        }
        val purged = api.purge(nodeId)
        if (purged !is DResult.Ok) Timber.i("Drive vault move: original left in the trash (%s)", (purged as? DResult.Failed)?.code ?: purged.javaClass.simpleName)
        return if (purged is DResult.Ok) Removal.Deleted else Removal.InDriveTrash
    }

    /** Android's dialog for the batch's [Removal.Ask] entries; null when there is none (they stay). */
    fun deleteRequest(uris: List<Uri>): IntentSender? =
        if (uris.isEmpty()) null else runCatching { originals.deleteRequest(uris) }.getOrNull()

    /** After the dialog: how many of [uris] are gone ([ok]: the person said yes; one still found counts as kept). */
    suspend fun confirmAsked(uris: List<Uri>, ok: Boolean): Int = withContext(io) {
        if (!ok) 0 else uris.count { !runCatching { originals.exists(it) }.getOrDefault(false) }
    }

    /** How a batch went: [moved] files in the vault, what became of their originals. */
    data class Outcome(
        val moved: Int = 0,
        val deleted: Int = 0,
        val inTrash: Int = 0,
        val kept: Int = 0,
        /** Android asked and the person said no (or the dialog did not delete them). */
        val declined: Int = 0,
        /** Waiting for Android's dialog. */
        val ask: List<Uri> = emptyList(),
    ) {
        operator fun plus(r: Removal): Outcome = when (r) {
            Removal.Deleted -> copy(moved = moved + 1, deleted = deleted + 1)
            Removal.InDriveTrash -> copy(moved = moved + 1, inTrash = inTrash + 1)
            Removal.Kept -> copy(moved = moved + 1, kept = kept + 1)
            is Removal.Ask -> copy(moved = moved + 1, ask = ask + r.uri)
        }

        /**
         * Android's dialog is over: [gone] of the [ask] entries were deleted, the others declined — or kept, when there
         * was no dialog to ask with (not [answered]).
         */
        fun asked(gone: Int, answered: Boolean = true): Outcome {
            val g = gone.coerceIn(0, ask.size)
            val left = ask.size - g
            return if (answered) copy(deleted = deleted + g, declined = declined + left, ask = emptyList())
            else copy(deleted = deleted + g, kept = kept + left, ask = emptyList())
        }

        /** Every original is gone. */
        val clean: Boolean get() = moved > 0 && inTrash == 0 && kept == 0 && declined == 0 && ask.isEmpty()
    }

    companion object {
        /**
         * The vault's answer for an upload is the file that went up: as long as the ciphertext ([size] bytes, SHA-256
         * [sha256]) — the server's hash when it gives one. Only then may the original go.
         */
        fun verified(node: DNode, size: Long, sha256: String): Boolean =
            !node.isDir && node.size == size && (node.sha256 == null || node.sha256.equals(sha256, ignoreCase = true))
    }
}

/** [DriveVaultMove.Originals] on the phone. */
class AndroidOriginals @Inject constructor(@ApplicationContext private val context: Context) : DriveVaultMove.Originals {

    private val resolver get() = context.contentResolver

    override fun origin(uri: Uri): DriveVaultMove.Origin {
        if (uri.scheme == "file") return uri.path?.let { DriveVaultMove.Origin.LocalFile(it) } ?: DriveVaultMove.Origin.Unsupported
        if (uri.scheme != "content") return DriveVaultMove.Origin.Unsupported
        val authority = uri.authority
        if (authority == DriveDocumentsProvider.authority(context.packageName)) {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            return (DriveDocs.parse(id) as? DriveDocs.Id.Node)?.let { DriveVaultMove.Origin.Drive(it.id) } ?: DriveVaultMove.Origin.Unsupported
        }
        DeviceFileProvider.fileOf(context, uri)?.let { return DriveVaultMove.Origin.LocalFile(it.path) }
        if (authority == MediaStore.AUTHORITY) return DriveVaultMove.Origin.Media(uri)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val flags = runCatching {
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            }.getOrNull() ?: 0
            val media = runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            return DriveVaultMove.Origin.Document(uri, flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0, media)
        }
        return DriveVaultMove.Origin.Unsupported
    }

    override fun deleteDocument(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)

    override fun deleteFile(path: String): Boolean {
        val f = File(path)
        if (!f.isFile) return false
        if (!f.delete() || f.exists()) return false
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(f.path), null, null) }
        return true
    }

    override fun allFilesAccess(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    override fun deleteMedia(uri: Uri): Boolean = resolver.delete(uri, null, null) > 0

    override fun deleteRequest(uris: List<Uri>): IntentSender = MediaStore.createDeleteRequest(resolver, uris).intentSender

    override fun exists(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
}
