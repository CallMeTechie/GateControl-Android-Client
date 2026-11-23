package com.gatecontrol.android.common

import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

/**
 * Failed background coroutines of the shared modules' app-wide scopes: logged, then handed to [sink] (the phone
 * app writes a crash report there, `CrashReports`), and the app keeps running instead of crashing.
 */
object BackgroundErrors {
    /** Set once by the app at start; without one the error is only logged. */
    @Volatile
    var sink: ((Throwable) -> Unit)? = null

    val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught error in a background task")
        runCatching { sink?.invoke(throwable) }
    }
}
