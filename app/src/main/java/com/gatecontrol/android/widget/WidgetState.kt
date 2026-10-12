package com.gatecontrol.android.widget

import com.gatecontrol.android.support.CrashReports
import android.content.Context
import androidx.glance.appwidget.updateAll
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.service.ClientPolicyManager
import com.gatecontrol.android.tunnel.TunnelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the home-screen widget shows, kept up to date while the app
 * process lives; each change redraws the widgets on the home screen.
 */
@Singleton
class WidgetState @Inject constructor(
    @ApplicationContext private val context: Context,
    tunnelManager: TunnelManager,
    private val repository: PushRepository,
    private val setupRepository: SetupRepository,
    clientPolicyManager: ClientPolicyManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashReports.coroutineHandler)
    private var started = false

    val model: StateFlow<WidgetModel> = combine(
        tunnelManager.state,
        repository.inbox.items,
        clientPolicyManager.policy,
    ) { state, items, policy ->
        widgetModel(state, host(), unreadCount(items), policy.canDisconnect)
    }.stateIn(scope, SharingStarted.Eagerly, WidgetModel())

    private fun host(): String? = runCatching { java.net.URI(setupRepository.getServerUrl()).host }.getOrNull()

    /** Called once from the application: loads the saved inbox and redraws the widgets on changes. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { repository.inbox.load() }
            model.drop(1).collect {
                runCatching { VpnWidget().updateAll(context) }
                    .onFailure { Timber.w("Widget: update failed (%s)", it.javaClass.simpleName) }
            }
        }
    }
}
