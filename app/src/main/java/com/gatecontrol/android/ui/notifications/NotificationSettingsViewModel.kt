package com.gatecontrol.android.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.notify.PushClient
import com.gatecontrol.android.notify.PushMode
import com.gatecontrol.android.notify.PushPlatform
import com.gatecontrol.android.notify.PushPrefsStore
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.PushStatus
import com.gatecontrol.android.notify.model.PushTopic
import com.gatecontrol.android.push.PortalLinkResolver
import com.gatecontrol.android.push.PushController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One topic row: switch on = messages of this topic are wanted on this device. */
data class TopicRow(val id: String, val label: String?, val muted: Boolean, val locked: Boolean)

enum class TestState { Idle, Sending, Sent, Failed }

data class NotificationSettingsUiState(
    val enabled: Boolean = false,
    val mode: PushMode = PushMode.ALWAYS,
    val status: PushStatus = PushStatus.Off,
    val host: String = "",
    val topics: List<TopicRow> = emptyList(),
    /** Battery optimisation restricts the app (card "GateControl ausnehmen"). */
    val batteryRestricted: Boolean = false,
    /** Android lets the app post notifications (POST_NOTIFICATIONS and app switch). */
    val notificationsAllowed: Boolean = true,
    /** First switch-on: propose the mode ("Immer, auch ohne VPN" recommended). */
    val modeDialogVisible: Boolean = false,
    val testState: TestState = TestState.Idle,
    val portalAvailable: Boolean = false,
)

/** One-shot requests to the screen (permissions, system dialogs, browser). */
sealed interface SettingsEffect {
    /** Ask for POST_NOTIFICATIONS (just switched on). */
    data object RequestNotificationPermission : SettingsEffect

    /** Ask to be exempted from battery optimisation (mode "always" just chosen). */
    data object RequestBatteryExemption : SettingsEffect
    data class OpenUrl(val url: String) : SettingsEffect
    data object PortalUnavailable : SettingsEffect
}

/** Settings → "Benachrichtigungen". */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val store: PushPrefsStore,
    private val repository: PushRepository,
    private val controller: PushController,
    private val platform: PushPlatform,
    private val portalLinkResolver: PortalLinkResolver,
    private val setupRepository: SetupRepository,
    client: PushClient,
) : ViewModel() {

    private data class Local(
        val batteryRestricted: Boolean = false,
        val notificationsAllowed: Boolean = true,
        val modeDialogVisible: Boolean = false,
        val testState: TestState = TestState.Idle,
    )

    private val local = MutableStateFlow(Local(batteryRestricted = platform.isBatteryRestricted()))
    private val effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effect: Flow<SettingsEffect> = effects.receiveAsFlow()

    private val host: String = runCatching { java.net.URI(setupRepository.getServerUrl()).host }.getOrNull().orEmpty()

    val uiState: StateFlow<NotificationSettingsUiState> = combine(
        store.settings, store.topics, client.status, local,
    ) { s, topics, status, l ->
        NotificationSettingsUiState(
            enabled = s.enabled,
            mode = s.mode,
            status = status,
            host = host,
            topics = topicRows(topics, s.mutedTopics),
            batteryRestricted = l.batteryRestricted,
            notificationsAllowed = l.notificationsAllowed,
            modeDialogVisible = l.modeDialogVisible,
            testState = l.testState,
            portalAvailable = setupRepository.getPortalUrl()?.startsWith("https://") == true,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettingsUiState())

    private fun topicRows(topics: List<PushTopic>, muted: Set<String>): List<TopicRow> =
        topics.map { TopicRow(it.id, if (it.isPlugin) it.label else null, it.id in muted && !it.isLocked, it.isLocked) }

    /** Screen resumed: Android state may have changed in the system settings. */
    fun refreshSystemState(notificationsAllowed: Boolean) {
        val restricted = platform.isBatteryRestricted()
        local.update { it.copy(batteryRestricted = restricted, notificationsAllowed = notificationsAllowed) }
        controller.onForeground()
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !store.current().modeChosen) {
                // Decision 2: on the first switch-on "Immer, auch ohne VPN" is proposed.
                local.update { it.copy(modeDialogVisible = true) }
                return@launch
            }
            store.setEnabled(enabled)
            if (enabled) afterEnable(store.current().mode)
        }
    }

    /** Mode picked in the first-time dialog: switches push on. */
    fun confirmMode(mode: PushMode) {
        viewModelScope.launch {
            local.update { it.copy(modeDialogVisible = false) }
            store.setMode(mode)
            store.setEnabled(true)
            afterEnable(mode)
        }
    }

    fun dismissModeDialog() {
        local.update { it.copy(modeDialogVisible = false) }
    }

    private suspend fun afterEnable(mode: PushMode) {
        if (!local.value.notificationsAllowed) effects.send(SettingsEffect.RequestNotificationPermission)
        else if (mode == PushMode.ALWAYS && local.value.batteryRestricted) effects.send(SettingsEffect.RequestBatteryExemption)
    }

    fun setMode(mode: PushMode) {
        viewModelScope.launch {
            store.setMode(mode)
            if (mode == PushMode.ALWAYS && store.current().enabled && local.value.batteryRestricted) {
                effects.send(SettingsEffect.RequestBatteryExemption)
            }
        }
    }

    /** Permission dialog answered (granted or not): continue with the battery exemption. */
    fun onNotificationPermissionResult(granted: Boolean) {
        local.update { it.copy(notificationsAllowed = granted) }
        viewModelScope.launch {
            val s = store.current()
            if (s.enabled && s.mode == PushMode.ALWAYS && local.value.batteryRestricted) {
                effects.send(SettingsEffect.RequestBatteryExemption)
            }
        }
    }

    fun setTopicEnabled(topic: String, enabled: Boolean) {
        if (topic == PushTopic.ADMIN_NOTICE) return
        viewModelScope.launch { store.setTopicMuted(topic, !enabled) }
    }

    fun requestTest() {
        if (local.value.testState == TestState.Sending) return
        viewModelScope.launch {
            local.update { it.copy(testState = TestState.Sending) }
            val ok = repository.requestTest().isSuccess
            local.update { it.copy(testState = if (ok) TestState.Sent else TestState.Failed) }
        }
    }

    fun retry() = controller.retry()

    fun openQuietHoursInPortal() {
        viewModelScope.launch {
            val url = portalLinkResolver.resolve(null)
            effects.send(if (url != null) SettingsEffect.OpenUrl(url) else SettingsEffect.PortalUnavailable)
        }
    }
}
