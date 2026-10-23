package com.gatecontrol.android.ui.vault

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.vault.Entry
import com.gatecontrol.android.vault.EntryData
import com.gatecontrol.android.vault.VEmergencyList
import com.gatecontrol.android.vault.VPerson
import com.gatecontrol.android.vault.VResult
import com.gatecontrol.android.vault.VaultBiometric
import com.gatecontrol.android.vault.VaultCrypto
import com.gatecontrol.android.vault.VaultImport
import com.gatecontrol.android.vault.VaultRepository
import com.gatecontrol.android.vault.VaultSession
import com.gatecontrol.android.vault.VaultStore
import com.gatecontrol.android.vault.VaultView
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the Vault tab stands. */
enum class VaultStatus { LOADING, UNAVAILABLE, NOT_SET_UP, LOCKED, UNLOCKED }

/** Shared by every Vault screen: the repository's state and its actions, results as callbacks. */
@HiltViewModel
class VaultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val repository: VaultRepository,
    private val session: VaultSession,
    private val store: VaultStore,
    val biometric: VaultBiometric,
) : ViewModel() {

    private val checked = MutableStateFlow(false)
    private val unreachable = MutableStateFlow(false)

    init {
        repository.ensureLoaded()
        session.watchLifecycle()
    }

    val state = repository.state
    val entries: StateFlow<List<Entry>> = repository.entries
    val vaults: StateFlow<List<VaultView>> = repository.vaults
    val sync = repository.sync
    val notices = repository.notices
    val unreadable = repository.unreadable
    val unlocked = repository.unlocked

    val status: StateFlow<VaultStatus> = combine(repository.state, repository.unlocked, checked, unreachable) { s, open, done, offline ->
        when {
            open && s.account != null -> VaultStatus.UNLOCKED
            s.account != null -> VaultStatus.LOCKED
            s.known -> VaultStatus.NOT_SET_UP
            offline -> VaultStatus.UNAVAILABLE
            !done -> VaultStatus.LOADING
            else -> VaultStatus.UNAVAILABLE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VaultStatus.LOADING)

    /** Asks the server whether Vault is set up (the copy answers while offline). */
    fun check() {
        viewModelScope.launch {
            unreachable.value = false
            val r = repository.refreshAccount()
            unreachable.value = r !is VResult.Ok && !repository.state.value.known
            checked.value = true
        }
    }

    fun syncNow() = repository.syncNow()
    fun lock() = repository.lock()
    fun clearNotices() = repository.clearNotices()
    fun owner(): String = store.owner()
    fun myUserId(): Int? = repository.myUserId()
    fun mySafetyNumber(): String? = session.keys()?.publicKey?.let { VaultCrypto.safetyNumber(it) }

    var autoLockMinutes: Int
        get() = session.autoLockMinutes
        set(v) {
            session.autoLockMinutes = v
        }
    val clipboardSeconds get() = session.clipboardSeconds

    /** A failure as one line for the screen. */
    fun message(r: VResult<*>): String? = when (r) {
        is VResult.Ok -> null
        VResult.Offline -> context.getString(R.string.vault_error_offline)
        VResult.NotConfigured -> context.getString(R.string.vault_error_offline)
        is VResult.Failed -> when (r.code) {
            "last_manager" -> context.getString(R.string.vault_last_manager)
            "rekeying" -> context.getString(R.string.vault_rekey_needed)
            else -> context.getString(R.string.vault_error_generic, r.code)
        }
    }

    // ─── setup and unlock ──────────────────────────────────────────

    fun setup(password: String, done: (VResult<String>) -> Unit) {
        viewModelScope.launch { done(repository.setup(password, context.getString(R.string.vault_personal))) }
    }

    fun unlock(password: String, done: (VaultRepository.Unlock) -> Unit) {
        viewModelScope.launch { done(repository.unlock(password)) }
    }

    fun unlockWithRecovery(text: String, done: (VaultRepository.Unlock) -> Unit) {
        viewModelScope.launch { done(repository.unlockWithRecovery(text)) }
    }

    fun completeRecovery(newPassword: String, done: (VResult<String>) -> Unit) {
        viewModelScope.launch { done(repository.completeRecovery(newPassword)) }
    }

    fun changePassword(newPassword: String, done: (VResult<String>) -> Unit) {
        viewModelScope.launch { done(repository.changePassword(newPassword)) }
    }

    fun biometricEnabled(): Boolean = biometric.enabled(owner())

    /** Shows the fingerprint prompt; [done] true when unlocked. */
    fun unlockWithFinger(activity: Context, done: (VaultBiometric.Outcome) -> Unit) {
        biometric.unlock(
            activity, owner(),
            context.getString(R.string.vault_bio_prompt_title), context.getString(R.string.vault_bio_prompt_negative),
        ) { outcome ->
            if (outcome is VaultBiometric.Outcome.Ok) {
                val r = repository.unlockWithUserKey(outcome.userKey)
                outcome.userKey.fill(0)
                done(if (r == VaultRepository.Unlock.Ok) outcome else VaultBiometric.Outcome.Failed)
            } else {
                done(outcome)
            }
        }
    }

    fun setBiometric(activity: Context, on: Boolean, done: (Boolean) -> Unit) {
        if (!on) {
            biometric.disable()
            return done(false)
        }
        val keys = session.keys() ?: return done(false)
        biometric.enable(
            activity, owner(), keys.userKey,
            context.getString(R.string.vault_bio_title), context.getString(R.string.vault_cancel),
        ) { ok -> done(ok) }
    }

    fun deleteAccount(done: (VResult<Unit>) -> Unit) {
        viewModelScope.launch {
            val r = repository.deleteAccount()
            if (r is VResult.Ok) biometric.disable()
            done(r)
        }
    }

    // ─── entries ───────────────────────────────────────────────────

    fun save(id: String?, vaultId: String, data: EntryData, done: (VaultRepository.Save) -> Unit) {
        viewModelScope.launch { done(repository.save(id, vaultId, data)) }
    }

    fun toggleFavorite(e: Entry) = save(e.id, e.vaultId, e.data.copy(fav = !e.data.fav)) { }

    fun trash(id: String, done: (VResult<Unit>) -> Unit) = viewModelScope.launch { done(repository.trash(id)) }
    fun restore(id: String, done: (VResult<Unit>) -> Unit) = viewModelScope.launch { done(repository.restore(id)) }
    fun purge(id: String, done: (VResult<Unit>) -> Unit) = viewModelScope.launch { done(repository.purge(id)) }

    fun history(id: String, done: (VResult<List<VaultRepository.Version>>) -> Unit) = viewModelScope.launch { done(repository.history(id)) }

    fun import(vaultId: String, list: List<EntryData>, done: (Int) -> Unit) = viewModelScope.launch { done(repository.saveMany(vaultId, list)) }

    fun parseImport(text: String): VaultImport.Outcome = VaultImport.parse(text)

    // ─── vaults and members ────────────────────────────────────────

    fun createVault(name: String, done: (VResult<*>) -> Unit) = viewModelScope.launch { done(repository.createVault(name)) }
    fun renameVault(id: String, name: String, done: (VResult<*>) -> Unit) = viewModelScope.launch { done(repository.renameVault(id, name)) }
    fun deleteVault(id: String, done: (VResult<*>) -> Unit) = viewModelScope.launch { done(repository.deleteVault(id)) }
    fun people(done: (VResult<List<VPerson>>) -> Unit) = viewModelScope.launch { done(repository.people()) }
    fun addMember(vaultId: String, p: VPerson, role: String, done: (VResult<*>) -> Unit) = viewModelScope.launch { done(repository.addMember(vaultId, p, role)) }
    fun setRole(vaultId: String, userId: Int, role: String, done: (VResult<*>) -> Unit) = viewModelScope.launch { done(repository.setRole(vaultId, userId, role)) }
    fun removeMember(vaultId: String, userId: Int, done: (VResult<*>) -> Unit) = viewModelScope.launch { done(repository.removeMember(vaultId, userId)) }

    // ─── emergency access ──────────────────────────────────────────

    private val _emergency = MutableStateFlow<VEmergencyList?>(null)
    val emergency: StateFlow<VEmergencyList?> = _emergency.asStateFlow()

    fun loadEmergency(done: (VResult<*>) -> Unit = {}) = viewModelScope.launch {
        val r = repository.emergency()
        if (r is VResult.Ok) _emergency.value = r.value
        done(r)
    }

    fun addTrusted(p: VPerson, hours: Int, done: (VResult<*>) -> Unit) = viewModelScope.launch {
        val r = repository.addTrusted(p, hours)
        loadEmergency()
        done(r)
    }

    fun removeTrusted(id: String, done: (VResult<*>) -> Unit) = viewModelScope.launch {
        val r = repository.removeTrusted(id)
        loadEmergency()
        done(r)
    }

    fun emergencyStep(id: String, step: String, done: (VResult<*>) -> Unit) = viewModelScope.launch {
        val r = repository.emergencyStep(id, step)
        loadEmergency()
        done(r)
    }

    private val _emergencyView = MutableStateFlow<VResult<VaultRepository.EmergencyView>?>(null)
    val emergencyView: StateFlow<VResult<VaultRepository.EmergencyView>?> = _emergencyView.asStateFlow()

    fun openEmergency(id: String) = viewModelScope.launch {
        _emergencyView.value = null
        _emergencyView.value = repository.openEmergency(id)
    }

    override fun onCleared() {
        // what a trusted person read stays in memory only while its screen is open
        _emergencyView.value = null
    }
}
