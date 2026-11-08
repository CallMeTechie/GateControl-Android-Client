package com.gatecontrol.android.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.homecache.HomeCacheLan
import com.gatecontrol.android.network.ApiClientProvider
import com.gatecontrol.android.network.HomeCache
import com.gatecontrol.android.network.UpdateCheckResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where the own APK download of the GitHub build stands (Settings › Updates). */
sealed interface ApkUpdateState {
    data object Idle : ApkUpdateState

    /** Loading [done] of [total] bytes ([total] 0: unknown); [home]: from the home gateway's cache. */
    data class Downloading(val done: Long, val total: Long, val home: Boolean) : ApkUpdateState

    /** Checking hash and signature. */
    data object Verifying : ApkUpdateState

    /**
     * Checked and ready for Android's installer; [home]: it came from the home gateway. [needsPermission]: GateControl
     * may not install apps yet ("Unbekannte Apps installieren") — the system page for it is open, the installer follows
     * once it is allowed ([ApkUpdater.continueAfterPermission]).
     */
    data class Ready(val version: String, val home: Boolean, val needsPermission: Boolean = false) : ApkUpdateState

    /** [code]: `network`, `sha256`, `signature`, `package`, `version`, `invalid`, `space`. */
    data class Failed(val code: String) : ApkUpdateState
}

/** Pure rules of the own APK download (unit-tested). */
object ApkUpdateLogic {
    /** At most this large (the APK is around 100 MB). */
    const val MAX_BYTES = 1_000L * 1024 * 1024

    /** Only an https address of the update answer is loaded (the server's own proxy or GitHub). */
    fun allowedUrl(url: String?): Boolean {
        val u = url?.trim()?.toHttpUrlOrNull() ?: return false
        return u.scheme == "https" && u.username.isEmpty() && u.password.isEmpty()
    }

    /** [url] lies on the own server [server] (then the app's own client, with its token, may load it). */
    fun sameHost(url: String, server: String): Boolean {
        val a = url.trim().toHttpUrlOrNull() ?: return false
        val b = server.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrlOrNull() ?: return false
        return a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }

    /** The bytes are what the server named; without a name only the signature decides. */
    fun shaMatches(expected: String?, actual: String): Boolean {
        val want = HomeCache.normalizeSha256(expected) ?: return expected == null
        return want == actual.lowercase()
    }

    /**
     * The APK may update this app: the same package, a newer version and — mandatory — signed with the certificate of
     * the installed app ([installed], SHA-256 of each signer): the same signers, or after a key rotation a lineage
     * ([apkHistory]) that holds all of the installed ones. Returns null when fine, else the code.
     */
    fun verify(
        apkPackage: String?,
        apkVersionCode: Long?,
        apkSigners: Set<String>,
        apkHistory: Set<String>,
        ownPackage: String,
        ownVersionCode: Long,
        installed: Set<String>,
    ): String? {
        if (apkPackage == null || apkVersionCode == null) return "invalid"
        if (apkPackage != ownPackage) return "package"
        if (apkVersionCode <= ownVersionCode) return "version"
        if (installed.isEmpty() || apkSigners.isEmpty()) return "signature"
        val same = apkSigners == installed || (apkHistory.isNotEmpty() && installed.all { it in apkHistory })
        return if (same) null else "signature"
    }
}

/**
 * The own APK download of the GitHub build ([BuildConfig.DISTRIBUTION] `github`; the Play build updates through Play
 * only). The update check is asked again right before (fresh `lan` hint, the device's LAN addresses only while at
 * home): at home the APK comes from the home gateway's cache ([HomeCacheLan] — pinned certificate, SHA-256 checked),
 * otherwise, or on any error there, from `downloadUrl`. Before Android's installer opens, the APK is checked: its
 * SHA-256 against the server's (when it names one) and — always — package, version and signing certificate against
 * the installed app. The APK lies in `cacheDir/update` (handed to the installer through the FileProvider).
 */
@Singleton
class ApkUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setup: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val homeCache: HomeCacheLan,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow<ApkUpdateState>(ApkUpdateState.Idle)
    val state: StateFlow<ApkUpdateState> = _state.asStateFlow()

    private val dir: File get() = File(context.cacheDir, DIR)
    private val apk: File get() = File(dir, FILE)

    /** The own download is offered (GitHub build only). */
    val enabled: Boolean get() = !PlayUpdater.enabled

    fun reset() {
        if (_state.value !is ApkUpdateState.Downloading && _state.value != ApkUpdateState.Verifying) _state.value = ApkUpdateState.Idle
    }

    /** Loads and checks the update [offer] names (the answer of the check in the settings). */
    suspend fun download(offer: UpdateCheckResponse, currentVersion: String): ApkUpdateState = lock.withLock {
        val r = try {
            run(offer, currentVersion)
        } catch (e: CancellationException) {
            // left the screen mid-way: nothing half-loaded stays, the next tap starts afresh
            withContext(NonCancellable + Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
            _state.value = ApkUpdateState.Idle
            throw e
        } catch (e: Exception) {
            Timber.w("Update: own download failed (%s)", e.javaClass.simpleName)
            ApkUpdateState.Failed("network")
        }
        if (r !is ApkUpdateState.Ready) withContext(Dispatchers.IO) { runCatching { dir.deleteRecursively() } }
        _state.value = r
        r
    }

    private suspend fun run(offer: UpdateCheckResponse, currentVersion: String): ApkUpdateState = withContext(Dispatchers.IO) {
        runCatching { dir.deleteRecursively() }
        dir.mkdirs()
        _state.value = ApkUpdateState.Downloading(0, offer.fileSize ?: 0, home = false)
        val server = setup.getServerUrl()
        // asked again now: the hint holds ten minutes, and only while this device is at home
        val fresh = if (server.isBlank()) null else runCatching {
            apiClientProvider.getClient(server).checkUpdateWithLocal(currentVersion, "android", "android", homeCache.localHeader())
        }.getOrNull()?.takeIf { it.available && it.version == offer.version }
        val answer = fresh ?: offer
        val expected = HomeCache.normalizeSha256(answer.sha256)
        var home = false
        // 1. from the home gateway (any error: the normal way, without a word)
        HomeCache.parse(answer.lan)?.takeIf { expected == null || it.sha256 == expected }?.let { hint ->
            _state.value = ApkUpdateState.Downloading(0, answer.fileSize ?: 0, home = true)
            homeCache.fetch(hint, expected ?: hint.sha256, answer.fileSize) { done, total ->
                _state.value = ApkUpdateState.Downloading(done, total, home = true)
            }?.let { f ->
                if (f.renameTo(apk) || runCatching { f.copyTo(apk, overwrite = true); f.delete() }.isSuccess) home = true
            }
        }
        // 2. the normal download
        val sha = if (home) {
            sha256(apk)
        } else {
            val url = answer.downloadUrl ?: offer.downloadUrl
            if (!ApkUpdateLogic.allowedUrl(url)) return@withContext ApkUpdateState.Failed("invalid")
            when (val got = fetch(url!!.trim(), server, answer.fileSize)) {
                null -> return@withContext ApkUpdateState.Failed("network")
                else -> got
            }
        }
        _state.value = ApkUpdateState.Verifying
        if (!ApkUpdateLogic.shaMatches(expected, sha)) return@withContext ApkUpdateState.Failed("sha256")
        verify(apk)?.let { return@withContext ApkUpdateState.Failed(it) }
        ApkUpdateState.Ready(answer.version ?: offer.version.orEmpty(), home)
    }

    /** [url] into [apk], hashing on the way; null on any error (nothing kept). Stops when the caller is cancelled. */
    private suspend fun fetch(url: String, server: String, size: Long?): String? {
        val client = if (server.isNotBlank() && ApkUpdateLogic.sameHost(url, server)) {
            // the server's own proxy (private release repository): the app's client with its token
            apiClientProvider.mediaStreamClient(server)
        } else {
            PLAIN
        }
        val request = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
        return try {
            client.newCall(request).execute().use { res ->
                // a redirect to http is not followed (followSslRedirects off): no update over plain http
                if (!res.isSuccessful || res.request.url.scheme != "https") return null
                val body = res.body
                val total = body.contentLength().takeIf { it > 0 } ?: size ?: 0
                if (total > ApkUpdateLogic.MAX_BYTES) return null
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { inp ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = inp.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > ApkUpdateLogic.MAX_BYTES) return null
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            _state.value = ApkUpdateState.Downloading(done, total, home = false)
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: IOException) {
            Timber.w("Update: download failed (%s)", e.javaClass.simpleName)
            null
        }
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Package, version and signing certificate of [file] against this app; null when it may update it. */
    private fun verify(file: File): String? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive: PackageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.path, flags)
            }
        }.getOrNull() ?: return "invalid"
        val own: PackageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, flags)
            }
        }.getOrNull() ?: return "signature"
        fun hashes(list: Array<android.content.pm.Signature>?): Set<String> =
            list.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        val apkInfo = archive.signingInfo
        val ownInfo = own.signingInfo
        return ApkUpdateLogic.verify(
            apkPackage = archive.packageName,
            apkVersionCode = archive.longVersionCode,
            apkSigners = hashes(apkInfo?.apkContentsSigners),
            apkHistory = if (apkInfo?.hasPastSigningCertificates() == true) hashes(apkInfo.signingCertificateHistory) else emptySet(),
            ownPackage = context.packageName,
            ownVersionCode = BuildConfig.VERSION_CODE.toLong(),
            installed = hashes(ownInfo?.apkContentsSigners),
        )
    }

    /** GateControl may hand an APK to Android's installer ("Unbekannte Apps installieren" allowed). */
    fun mayInstall(): Boolean = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * Hands the checked APK to Android's installer (the system install intent through the FileProvider; Android asks
     * the person, the app never installs silently). Without "Unbekannte Apps installieren" for GateControl the system's
     * page for it opens first and the state says so ([ApkUpdateState.Ready.needsPermission]). True: the installer opened.
     */
    fun install(from: Context): Boolean {
        val ready = _state.value as? ApkUpdateState.Ready ?: return false
        if (!enabled || !apk.isFile) return false
        if (!mayInstall()) {
            _state.value = ready.copy(needsPermission = true)
            runCatching {
                from.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return false
        }
        if (ready.needsPermission) _state.value = ready.copy(needsPermission = false)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            from.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Timber.w("Update: no installer for the APK")
            false
        }
    }

    /** Back from the system page: allowed now → the installer opens (only while it is waiting for that). */
    fun continueAfterPermission(from: Context) {
        val ready = _state.value as? ApkUpdateState.Ready ?: return
        if (ready.needsPermission && mayInstall()) install(from)
    }

    /**
     * At app start: an APK of an earlier run goes (installed by now, or given up; ~100 MB in the cache). Nothing while
     * a download of this run is under way.
     */
    fun cleanup() {
        if (_state.value != ApkUpdateState.Idle) return
        runCatching { if (dir.exists()) dir.deleteRecursively() }
    }

    private companion object {
        const val DIR = "update"
        const val FILE = "update.apk"
        const val APK_MIME = "application/vnd.android.package-archive"

        /** For GitHub's addresses: no token of the app, no redirect to http, long enough for a slow line. */
        val PLAIN: OkHttpClient = OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
