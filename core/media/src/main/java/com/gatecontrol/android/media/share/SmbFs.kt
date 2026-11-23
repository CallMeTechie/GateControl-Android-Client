package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSourceKind
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * An SMB2/3 share (`\\nas\filme`) through smbj: the fastest way at home. One connection and session per share, made
 * again when it broke. The NTLM and SMB3 signing run on Bouncy Castle ([BCSecurityProvider]: Android's JCE has no
 * MD4). [socketFactory]: at home bound to the Wi-Fi (also while the tunnel is up). The password is sent only in the
 * NTLM exchange, never logged.
 */
class SmbFs(
    private val address: MediaAddress.Smb,
    private val user: String?,
    private val password: String?,
    socketFactory: SocketFactory? = null,
) : ShareFs {
    override val kind = MediaSourceKind.SMB

    private val config: SmbConfig = SmbConfig.builder(SmbConfig.createDefaultConfig())
        .withSecurityProvider(BCSecurityProvider())
        .withAuthenticators(NtlmAuthenticator.Factory())
        .withTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .withSoTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .apply { if (socketFactory != null) withSocketFactory(socketFactory) }
        .build()

    private val client = SMBClient(config)
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Volatile
    private var closed = false

    /** The share, connected now (or again). Blocking. */
    @Synchronized
    private fun disk(): DiskShare {
        if (closed) throw IOException("closed")
        share?.takeIf { it.isConnected }?.let { return it }
        drop()
        val c = client.connect(address.host, address.port)
        connection = c
        val auth = if (user.isNullOrEmpty()) {
            AuthenticationContext.guest()
        } else {
            val (domain, name) = if (user.contains('\\')) user.substringBefore('\\') to user.substringAfter('\\') else "" to user
            AuthenticationContext(name, password.orEmpty().toCharArray(), domain)
        }
        val s = c.authenticate(auth)
        session = s
        val d = s.connectShare(address.share) as? DiskShare ?: throw IOException("not a disk share")
        share = d
        return d
    }

    @Synchronized
    private fun drop() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null
        session = null
        connection = null
    }

    /** [path] below the share's folder in SMB's way ("Filme\\Neu"). */
    internal fun smbPath(path: String): String =
        listOf(address.path, path).filter { it.isNotEmpty() }.joinToString("/").replace('/', '\\')

    /** [block] on the share; once more on a fresh connection when the old one had broken. */
    private fun <T> withShare(block: (DiskShare) -> T): T = try {
        block(disk())
    } catch (e: SMBApiException) {
        throw e
    } catch (e: IOException) {
        drop()
        block(disk())
    } catch (e: RuntimeException) {
        // smbj wraps a broken transport in its runtime exceptions
        if (e.causeOf<SMBApiException>() != null) throw e
        drop()
        block(disk())
    }

    override suspend fun list(dir: String): MediaResult<List<ShareEntry>> = withContext(Dispatchers.IO) {
        val clean = SharePaths.clean(dir) ?: return@withContext MediaResult.NotFound
        result {
            withShare { it.list(smbPath(clean)) }.mapNotNull { f ->
                val name = f.fileName
                if (name == "." || name == ".." || ShareNames.hidden(name) || SharePaths.clean(name) != name) return@mapNotNull null
                if (f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L) return@mapNotNull null
                ShareEntry(
                    name = name,
                    path = SharePaths.join(clean, name),
                    directory = f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = f.endOfFile,
                    modifiedAt = runCatching { f.lastWriteTime.toEpochMillis() }.getOrNull()?.takeIf { it > 0 },
                )
            }
        }
    }

    override suspend fun bytes(path: String, max: Int): ByteArray? = withContext(Dispatchers.IO) {
        val clean = SharePaths.clean(path)?.takeIf { it.isNotEmpty() } ?: return@withContext null
        try {
            openFile(clean).use { f ->
                val size = f.fileInformation.standardInformation.endOfFile
                if (size > max || size < 0) return@use null
                val out = ByteArray(size.toInt())
                var at = 0
                while (at < out.size) {
                    val n = f.read(out, at.toLong(), at, out.size - at)
                    if (n <= 0) break
                    at += n
                }
                if (at == out.size) out else out.copyOf(at)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("SMB: picture not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    private fun openFile(path: String): File = withShare {
        it.openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
    }

    override fun open(path: String, position: Long): ShareInput {
        val clean = SharePaths.clean(path)?.takeIf { it.isNotEmpty() } ?: throw IOException("bad path")
        val f = try {
            openFile(clean)
        } catch (e: SMBApiException) {
            throw IOException("SMB: ${e.status}", e)
        } catch (e: RuntimeException) {
            throw IOException("SMB: not opened", e)
        }
        val length = try {
            f.fileInformation.standardInformation.endOfFile
        } catch (e: RuntimeException) {
            runCatching { f.close() }
            throw IOException("SMB: no length", e)
        }
        val chunk = (connection?.negotiatedProtocol?.maxReadSize ?: CHUNK).coerceIn(64 * 1024, CHUNK)
        return ShareInput(length, SmbInput(f, position, length, chunk))
    }

    override fun close() {
        closed = true
        drop()
        runCatching { client.close() }
    }

    /** [block]'s answer, SMB's errors as [MediaResult]. */
    private inline fun <T> result(block: () -> T): MediaResult<T> = try {
        MediaResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val status = e.causeOf<SMBApiException>()?.status
        Timber.d("SMB: %s (%s)", status ?: "not reached", e.javaClass.simpleName)
        when (status) {
            null -> MediaResult.Offline
            NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED, NtStatus.STATUS_PASSWORD_EXPIRED -> MediaResult.Unauthorized
            NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE, NtStatus.STATUS_NOT_A_DIRECTORY -> MediaResult.NotFound
            else -> MediaResult.Failed(0)
        }
    }

    companion object {
        const val TIMEOUT_S = 20L

        /** The bytes of one read (SMB2.1+ servers allow 1 MB and more). */
        const val CHUNK = 1024 * 1024
    }
}

private inline fun <reified T : Throwable> Throwable.causeOf(): T? {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < 8) {
        if (t is T) return t
        t = t.cause
    }
    return null
}

/** A file of the share from [start] in reads of [chunk] bytes (the player's loader thread reads ahead of the picture). */
internal class SmbInput(private val file: File, start: Long, private val length: Long, private val chunk: Int) : InputStream() {
    private var position = start
    private var buffer = ByteArray(0)
    private var at = 0

    private fun fill(): Boolean {
        if (at < buffer.size) return true
        if (position >= length) return false
        val b = ByteArray(minOf(chunk.toLong(), length - position).toInt())
        val n = try {
            file.read(b, position, 0, b.size)
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException("SMB: read failed", e)
        }
        if (n <= 0) return false
        buffer = if (n == b.size) b else b.copyOf(n)
        at = 0
        position += n
        return true
    }

    override fun read(): Int = if (fill()) buffer[at++].toInt() and 0xff else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, buffer.size - at)
        System.arraycopy(buffer, at, b, off, n)
        at += n
        return n
    }

    override fun available(): Int = buffer.size - at

    override fun close() {
        runCatching { file.closeNoWait() }
    }
}
