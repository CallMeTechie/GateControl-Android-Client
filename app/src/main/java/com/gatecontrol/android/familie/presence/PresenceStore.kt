package com.gatecontrol.android.familie.presence

import android.content.Context
import androidx.core.content.edit
import com.gatecontrol.android.familie.FamilieStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's own presence settings (plain SharedPreferences, no
 * reflection): whether I share my presence (my consent, as the server last
 * confirmed it) and what was reported last. Bound to the login
 * ([FamilieStorage.ownerKey]); another server or token starts empty.
 */
@Singleton
class PresenceStore @Inject constructor(
    @ApplicationContext context: Context,
    private val storage: FamilieStorage,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _share = MutableStateFlow(readShare())

    /** I share my presence (consent given and confirmed by the server). */
    val share: StateFlow<Boolean> = _share.asStateFlow()

    private fun ownerMatches(): Boolean = prefs.getString(KEY_OWNER, null) == storage.ownerKey()

    private fun readShare(): Boolean = runCatching { ownerMatches() && prefs.getBoolean(KEY_SHARE, false) }.getOrDefault(false)

    @Synchronized
    fun setShare(share: Boolean) {
        val owner = storage.ownerKey()
        val otherLogin = prefs.getString(KEY_OWNER, null) != owner
        val changed = share != _share.value
        prefs.edit {
            if (otherLogin) clear().putString(KEY_OWNER, owner)
            putBoolean(KEY_SHARE, share)
            // A new consent starts fresh: the current state is reported again.
            if (changed) remove(KEY_LAST).remove(KEY_LAST_AT)
        }
        _share.value = share
    }

    /** What was reported last: true home, false away, null nothing (for this login). */
    fun lastReported(): Boolean? = runCatching {
        if (!ownerMatches() || !prefs.contains(KEY_LAST)) null else prefs.getBoolean(KEY_LAST, false)
    }.getOrNull()

    @Synchronized
    fun reported(home: Boolean, atMs: Long) {
        if (!ownerMatches()) return
        prefs.edit { putBoolean(KEY_LAST, home).putLong(KEY_LAST_AT, atMs) }
    }

    /** No member / plugin gone / logged out: nothing is kept. */
    @Synchronized
    fun clear() {
        prefs.edit { clear() }
        _share.value = false
    }

    private companion object {
        const val PREFS = "familie_presence"
        const val KEY_OWNER = "owner"
        const val KEY_SHARE = "share"
        const val KEY_LAST = "last_home"
        const val KEY_LAST_AT = "last_at"
    }
}
