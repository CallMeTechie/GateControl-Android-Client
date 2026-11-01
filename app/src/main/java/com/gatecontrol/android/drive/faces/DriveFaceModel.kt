package com.gatecontrol.android.drive.faces

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gatecontrol.android.BuildConfig
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SFace, the face feature model (39 MB), is not in the app: it is loaded once when the person switches on "Personen
 * erkennen" — from this app version's GitHub release, else from OpenCV Zoo on GitHub, else from Hugging Face; only the
 * file is fetched (the server sees the phone's address and the usual connection data, no photo). Checked against its
 * SHA-256, kept in `noBackupFilesDir/faces/`, resumed after an interruption; in WLAN unless the person says otherwise.
 * Switching off deletes it ([delete]).
 */
@Singleton
class DriveFaceModel @Inject constructor(@ApplicationContext private val context: Context) {

    private val fetcher = DriveFaceModelFetcher(File(context.noBackupFilesDir, "faces"), FILE, SHA256, SIZE, ::open)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val file: File get() = fetcher.file

    fun ready(): Boolean = fetcher.ready()

    fun delete() = fetcher.delete()

    /** Loads the model (blocking IO in the caller's job); [progress] hears bytes so far of [SIZE]. */
    suspend fun fetch(progress: (Long, Long) -> Unit, cancelled: () -> Boolean): DriveFaceModelFetcher.Result =
        withContext(Dispatchers.IO) { fetcher.fetch(sources(BuildConfig.VERSION_NAME), progress, cancelled) }

    private fun open(url: String, offset: Long): DriveFaceModelFetcher.Response? {
        val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val response = client.newCall(request).execute()
        val body = response.body
        return object : DriveFaceModelFetcher.Response {
            override val code: Int = response.code
            override val body: InputStream = body.byteStream()
            override fun close() = response.close()
        }
    }

    /** Where the model is, in this order (the app's own release first). */
    companion object {
        const val FILE = "face_recognition_sface_2021dec.onnx"
        const val SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
        const val SIZE = 38_696_353L

        fun sources(version: String): List<String> = listOf(
            "https://github.com/CallMeTechie/GateControl-Android-Client/releases/download/v$version/$FILE",
            "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/face_recognition_sface/$FILE",
            "https://huggingface.co/opencv/face_recognition_sface/resolve/main/$FILE",
        )
    }
}

/** How far the model is, for the settings and the people page. */
data class FaceModelState(val phase: Phase, val done: Long = 0L, val total: Long = DriveFaceModel.SIZE, val error: String? = null) {
    enum class Phase {
        /** Not here, nothing planned. */
        MISSING,
        /** Planned, waiting for WLAN (or another condition). */
        WAITING,
        LOADING,
        READY,
        FAILED,
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriveFaceModelEntryPoint {
    fun driveFaceModel(): DriveFaceModel
}

/** Loads the model; then the faces' job starts. */
class DriveFaceModelWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val model = EntryPointAccessors.fromApplication(applicationContext, DriveFaceModelEntryPoint::class.java).driveFaceModel()
        var shown = -1L
        val r = model.fetch(
            progress = { done, total ->
                // about every megabyte
                if (done - shown >= STEP || done == total) {
                    shown = done
                    setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                }
            },
            cancelled = { isStopped },
        )
        return when (r) {
            is DriveFaceModelFetcher.Result.Done -> Result.success().also { DrivePeopleWork.restart(applicationContext) }
            is DriveFaceModelFetcher.Result.Failed -> when (r.code) {
                DriveFaceModelFetcher.OFFLINE, DriveFaceModelFetcher.CANCELLED -> if (runAttemptCount < 8) Result.retry() else Result.failure(workDataOf(KEY_ERROR to r.code))
                else -> {
                    Timber.w("Drive faces: model not loaded (%s)", r.code)
                    Result.failure(workDataOf(KEY_ERROR to r.code))
                }
            }
        }
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val STEP = 1_000_000L
    }
}

/** Scheduling of the model's download: in WLAN, or — after the person agreed — on any network. */
object DriveFaceModelWork {
    private const val RUN = "drive-face-model"

    private fun model(context: Context) = EntryPointAccessors.fromApplication(context, DriveFaceModelEntryPoint::class.java).driveFaceModel()

    /** Load it unless it is here; [mobile]: also over mobile data (the person said so). */
    fun start(context: Context, mobile: Boolean = false) {
        runCatching {
            if (model(context).ready()) return
            val request = OneTimeWorkRequestBuilder<DriveFaceModelWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // a retry or "über mobile Daten" replaces what waits; a running download is kept
            WorkManager.getInstance(context).enqueueUniqueWork(RUN, if (mobile) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("Drive faces: model not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** "Erneut versuchen": anew, in WLAN (a failed job is done, so a new one starts). */
    fun retry(context: Context) = start(context)

    /** Switched off: no download, and the file goes. */
    fun cancelAndDelete(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(RUN) }
        runCatching { model(context).delete() }
    }

    /** The model's state from the file and the download's job. */
    fun state(context: Context): Flow<FaceModelState> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(RUN).map { infos ->
            stateOf(model(context).ready(), infos.lastOrNull())
        }

    internal fun stateOf(ready: Boolean, info: WorkInfo?): FaceModelState = when {
        ready -> FaceModelState(FaceModelState.Phase.READY, DriveFaceModel.SIZE)
        info == null -> FaceModelState(FaceModelState.Phase.MISSING)
        info.state == WorkInfo.State.RUNNING -> FaceModelState(
            FaceModelState.Phase.LOADING,
            info.progress.getLong(DriveFaceModelWorker.KEY_DONE, 0L),
            info.progress.getLong(DriveFaceModelWorker.KEY_TOTAL, DriveFaceModel.SIZE).takeIf { it > 0 } ?: DriveFaceModel.SIZE,
        )
        info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> FaceModelState(FaceModelState.Phase.WAITING)
        info.state == WorkInfo.State.FAILED -> FaceModelState(FaceModelState.Phase.FAILED, error = info.outputData.getString(DriveFaceModelWorker.KEY_ERROR))
        else -> FaceModelState(FaceModelState.Phase.MISSING)
    }
}
