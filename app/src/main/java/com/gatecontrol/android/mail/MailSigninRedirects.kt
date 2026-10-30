package com.gatecontrol.android.mail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The browser's answer to a Microsoft sign-in of a mail account, until the screen that asked takes it. */
object MailSigninRedirects {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun offer(raw: String) {
        _pending.value = raw
    }

    fun consume(raw: String) {
        _pending.update { if (it == raw) null else it }
    }
}
