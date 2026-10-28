package com.gatecontrol.android.drive.vault

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.gatecontrol.android.drive.DriveSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** When an open vault locks (pure, unit-tested). */
internal object DriveVaultLockTimer {
    /** Milliseconds until the vaults lock for want of use (≤ 0: now). */
    fun remaining(now: Long, lastUse: Long, minutes: Int): Long = lastUse + minutes * 60_000L - now

    /**
     * The app went to the background: lock now? Not when the setting is off, nor when the vault itself handed the person
     * to another app a moment ago ([away]: the file picker for an upload, an app showing a decrypted file).
     */
    fun lockOnStop(lockOnLeave: Boolean, away: Boolean): Boolean = lockOnLeave && !away

    /** The timer is due, but an upload or download of the vault still runs: it waits this long and looks again. */
    const val BUSY_RECHECK_MS = 5_000L
}

/**
 * The vault lock of Drive 1.11 (like OneDrive's Personal Vault): an open vault locks after the time set without use
 * (standard 3 minutes, [touch] counts as use) and — unless switched off — as soon as the app goes to the background.
 * Locking is [DriveVaultSession.lock]; it waits for a running upload or download ([busy]).
 */
@Singleton
class DriveVaultLock @Inject constructor(
    private val session: DriveVaultSession,
    private val settings: DriveSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var lastUse = 0L
    @Volatile private var away = false
    @Volatile private var pending = false
    private val running = AtomicInteger(0)
    private var timer: Job? = null

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            away = false
        }

        override fun onStop(owner: LifecycleOwner) {
            val wasAway = away
            away = false
            if (!session.anyUnlocked()) return
            if (DriveVaultLockTimer.lockOnStop(settings.current.lockOnLeave, wasAway)) {
                Timber.i("Drive vault: locked (app in the background)")
                lockNow()
            }
        }
    }

    init {
        // the process lifecycle wants the main thread
        Handler(Looper.getMainLooper()).post { runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) } }
    }

    /** The person used the vault (opened it, a folder, a file …): the time without use starts again. */
    fun touch() {
        lastUse = SystemClock.elapsedRealtime()
        if (session.anyUnlocked()) start()
    }

    /** The vault hands the person to another app now (picker, viewer): leaving the app this once does not lock. */
    fun away() {
        away = true
        touch()
    }

    /** Runs [block] (an upload or a download of the vault) while the lock waits. */
    suspend fun <T> busy(block: suspend () -> T): T {
        running.incrementAndGet()
        touch()
        try {
            return block()
        } finally {
            running.decrementAndGet()
            touch()
            if (pending && running.get() == 0) lockNow()
        }
    }

    /** Locks every vault now (after a running transfer). */
    fun lockNow() {
        if (running.get() > 0) {
            pending = true
            return
        }
        pending = false
        synchronized(this) { timer?.cancel() }
        session.lock()
    }

    @Synchronized
    private fun start() {
        if (timer?.isActive == true) return
        timer = scope.launch {
            while (session.anyUnlocked()) {
                val wait = DriveVaultLockTimer.remaining(SystemClock.elapsedRealtime(), lastUse, settings.current.autoLockMinutes)
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                if (running.get() > 0) {
                    delay(DriveVaultLockTimer.BUSY_RECHECK_MS)
                    continue
                }
                Timber.i("Drive vault: locked (not used for a while)")
                session.lock()
                break
            }
        }
    }
}
