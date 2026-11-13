package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatDeviceConfig
import com.gatecontrol.android.chat.ChatDeviceSettings
import com.gatecontrol.android.ui.components.DeviceAuthResult
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.SecureScreenEffect
import com.gatecontrol.android.ui.components.rememberGcDeviceAuth
import com.gatecontrol.android.ui.start.PageArrivalEffect
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The chats' app lock (Messenger 1.4 settings, this device): unlocked once
 * per visit; after a minute in the background it asks again. Pure
 * decision in [relock]; unit-tested.
 */
object ChatLock {
    const val RELOCK_AFTER_MS = 60_000L

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    @Volatile private var pausedAtMs: Long? = null

    fun unlock() {
        _unlocked.value = true
        pausedAtMs = null
    }

    fun paused(nowMs: Long) {
        if (_unlocked.value) pausedAtMs = nowMs
    }

    fun resumed(nowMs: Long) {
        if (relock(pausedAtMs, nowMs)) _unlocked.value = false
        pausedAtMs = null
    }

    /** Locked again when the app was away longer than [RELOCK_AFTER_MS]. */
    fun relock(pausedAtMs: Long?, nowMs: Long): Boolean = pausedAtMs != null && nowMs - pausedAtMs > RELOCK_AFTER_MS
}

@HiltViewModel
class ChatGateViewModel @Inject constructor(settings: ChatDeviceSettings) : ViewModel() {
    val config: StateFlow<ChatDeviceConfig> = settings.config
}

/**
 * Wraps the chat screens: no screenshots / app switcher preview when
 * "Bildschirmfotos verhindern" is on (FLAG_SECURE), and the biometric /
 * screen lock prompt when "App-Sperre" is on.
 */
@Composable
fun ChatSecureGate(content: @Composable () -> Unit) {
    if (LocalInspectionMode.current) {
        content()
        return
    }
    val vm: ChatGateViewModel = hiltViewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val unlocked by ChatLock.unlocked.collectAsStateWithLifecycle()
    // Counted per window and only while a part of the chats shows: beside Start the pager composes them ahead.
    SecureScreenEffect(active = config.secure)
    LifecycleResumeEffect(Unit) {
        ChatLock.resumed(System.currentTimeMillis())
        onPauseOrDispose { ChatLock.paused(System.currentTimeMillis()) }
    }
    if (!config.appLock || unlocked) {
        content()
        return
    }
    val auth = rememberGcDeviceAuth()
    val lockTitle = stringResource(R.string.chat_lock_title)
    val lockSubtitle = stringResource(R.string.chat_lock_subtitle)
    val ask: () -> Unit = {
        auth.authenticate(lockTitle, lockSubtitle) { r ->
            // No screen lock any more: the lock cannot work, so it does not block.
            if (r == DeviceAuthResult.SUCCESS || r == DeviceAuthResult.UNAVAILABLE) ChatLock.unlock()
        }
    }
    // Only on the page in front: beside Start the pager composes the chats ahead of showing them.
    PageArrivalEffect { ask() }
    val extra = GateControlTheme.extraColors
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = extra.muted, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.chat_lock_locked), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            GcPrimaryButton(text = stringResource(R.string.chat_lock_unlock), onClick = ask, fillWidth = false)
        }
    }
}
