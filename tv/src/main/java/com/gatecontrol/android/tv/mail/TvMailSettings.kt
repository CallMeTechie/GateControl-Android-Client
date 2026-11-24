package com.gatecontrol.android.tv.mail

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen › Nachrichten of GateControl TV (Frage 14 a): which mail accounts of the person who paired the TV show on it.
 * None by default — mail belongs to one person and the TV stands in the living room. Its own small file next to the
 * TV's settings; the ids are the Mail plugin's account ids, nothing secret.
 */
@Singleton
class TvMailSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(read())

    /** The accounts shown on the TV (empty: Mail shows nothing but the hint where to choose). */
    val accounts: StateFlow<Set<Long>> = _accounts.asStateFlow()

    fun setAccount(id: Long, on: Boolean) {
        if (id <= 0) return
        set(if (on) _accounts.value + id else _accounts.value - id)
    }

    /** Only the ids still among the person's accounts stay (an account removed on the phone goes here too). */
    fun keepOnly(existing: Collection<Long>) {
        val kept = _accounts.value.intersect(existing.toSet())
        if (kept != _accounts.value) set(kept)
    }

    /** Unpaired: another person's accounts must never show. */
    fun clear() = set(emptySet())

    private fun set(ids: Set<Long>) {
        val clean = TvMailLogic.cleanAccounts(ids)
        prefs.edit { putStringSet(KEY_ACCOUNTS, clean.map { it.toString() }.toSet()) }
        _accounts.value = clean
    }

    private fun read(): Set<Long> =
        TvMailLogic.cleanAccounts(prefs.getStringSet(KEY_ACCOUNTS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() })

    private companion object {
        const val FILE = "gatecontrol_tv_mail"
        const val KEY_ACCOUNTS = "accounts"
    }
}
