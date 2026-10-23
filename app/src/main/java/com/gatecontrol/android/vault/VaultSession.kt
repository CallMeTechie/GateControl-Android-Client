package com.gatecontrol.android.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The unlocked state: the account keys and the vault keys, in memory only. Locks itself when the app has been in the
 * background longer than the chosen time (default 5 minutes; "immediately" locks on leaving) and on [lock]. Locking
 * zeroes every key.
 */
@Singleton
class VaultSession @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("vault_settings", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val lockTask = Runnable { lock() }

    @Volatile private var keys: VaultCrypto.Keys? = null
    /** vault id → key generation → key; old generations stay until locked (an unsent save may still use one). */
    private val vaultKeys = HashMap<String, HashMap<Int, ByteArray>>()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Minutes in the background before locking; 0 = when leaving the app. */
    var autoLockMinutes: Int
        get() = prefs.getInt(KEY_AUTO_LOCK, DEFAULT_AUTO_LOCK)
        set(v) = prefs.edit { putInt(KEY_AUTO_LOCK, v.coerceIn(0, 24 * 60)) }

    /** Seconds before a copied secret leaves the clipboard. */
    val clipboardSeconds: Int get() = prefs.getInt(KEY_CLIPBOARD, 30)

    private var observing = false

    /** Starts watching the app's foreground state (once, from the main thread). */
    fun watchLifecycle() {
        if (observing) return
        observing = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = handler.removeCallbacks(lockTask)

            override fun onStop(owner: LifecycleOwner) {
                if (keys == null) return
                val minutes = autoLockMinutes
                if (minutes <= 0) lock() else handler.postDelayed(lockTask, minutes * 60_000L)
            }
        })
    }

    @Synchronized
    fun open(k: VaultCrypto.Keys) {
        keys?.takeIf { it !== k }?.wipe()
        keys = k
        _unlocked.value = true
    }

    fun keys(): VaultCrypto.Keys? = keys

    @Synchronized
    fun lock() {
        handler.removeCallbacks(lockTask)
        keys?.wipe()
        keys = null
        vaultKeys.values.forEach { m -> m.values.forEach { it.fill(0) } }
        vaultKeys.clear()
        _unlocked.value = false
    }

    /** The vault's keys (current and, during a key change, the new one), opened once per session. */
    @Synchronized
    fun keysOf(vault: VVault): Map<Int, ByteArray> {
        val k = keys ?: return emptyMap()
        val m = vaultKeys.getOrPut(vault.id) { HashMap() }
        if (vault.keyRev !in m) runCatching { m[vault.keyRev] = VaultCrypto.openSealed(vault.sealedKey, k.publicKey, k.secretKey) }
        val p = vault.pending
        if (p?.sealedKey != null && p.keyRev !in m) runCatching { m[p.keyRev] = VaultCrypto.openSealed(p.sealedKey, k.publicKey, k.secretKey) }
        return HashMap(m)
    }

    @Synchronized
    fun putKey(vaultId: String, keyRev: Int, key: ByteArray) {
        vaultKeys.getOrPut(vaultId) { HashMap() }[keyRev] = key.copyOf()
    }

    companion object {
        private const val KEY_AUTO_LOCK = "auto_lock_minutes"
        private const val KEY_CLIPBOARD = "clipboard_seconds"
        const val DEFAULT_AUTO_LOCK = 5
        val AUTO_LOCK_CHOICES = listOf(0, 1, 5, 15, 60)
    }
}
