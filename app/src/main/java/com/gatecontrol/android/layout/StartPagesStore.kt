package com.gatecontrol.android.layout

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pages beside Start ([StartPages]): kept in the start tab's order
 * ([TabLayoutRepository], from the account where the server keeps it), so
 * the second phone shows the same pages and an admin's locked order
 * ([LayoutAccess.locked]) fixes them too. "Leiste ausblenden, wenn nur
 * Start bleibt" is this device's own choice ([SettingsRepository]): it only
 * changes how this screen looks, and an admin's lock does not take it away.
 */
@Singleton
class StartPagesStore @Inject constructor(
    private val tabLayout: TabLayoutRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    /** The page ids in their order (unknown ones included; [StartPages.visible] picks those to show). */
    val pages: StateFlow<List<String>> = tabLayout.layout.map { StartPages.pages(it.start) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val access: StateFlow<LayoutAccess> = tabLayout.access

    val hideBar: StateFlow<Boolean> = settingsRepository.getHideBarOnlyStart()
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** The start list as it is now (to take a change back, [restore]). */
    suspend fun snapshot(): List<String> = tabLayout.current().start

    /** [id] (a function or a new widget page) as the last page. */
    suspend fun pin(id: String) = tabLayout.updateStart { StartPages.pin(it, id) }

    /** The page [id] goes: a function becomes a tab again, a widget page's blocks go back to Start. */
    suspend fun unpin(id: String) = tabLayout.updateStart { StartPages.unpin(it, id) }

    /** A new empty widget page as the last page; its id. */
    suspend fun addWidgets(): String {
        var id = ""
        tabLayout.updateStart { saved -> StartPages.nextWidgets(saved).also { id = it }.let { StartPages.pin(saved, it) } }
        return id
    }

    /** The pages in the order of [order] (page ids). */
    suspend fun reorder(order: List<String>) = tabLayout.updateStart { StartPages.reorder(it, order) }

    /** "Rückgängig": the start list as it was before ([snapshot]). */
    suspend fun restore(start: List<String>) = tabLayout.updateStart { start }

    suspend fun setHideBar(value: Boolean) = settingsRepository.setHideBarOnlyStart(value)
}
