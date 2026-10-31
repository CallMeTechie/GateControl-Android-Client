package com.gatecontrol.android.mail

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
 * How mails are shown on this phone ("Mail-Einstellungen › Darstellung"): "Mails dunkel darstellen" (on by default)
 * shows a mail dark while the app is dark ([MailLogic.showDark], [MailDark]); off, mails stay light as they were made.
 */
@Singleton
class MailDisplaySettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _darkMails = MutableStateFlow(prefs.getBoolean(KEY_DARK, true))
    val darkMails: StateFlow<Boolean> = _darkMails.asStateFlow()

    fun setDarkMails(on: Boolean) {
        prefs.edit { putBoolean(KEY_DARK, on) }
        _darkMails.value = on
    }

    private companion object {
        const val PREFS = "mail_display"
        const val KEY_DARK = "dark_mails"
    }
}
