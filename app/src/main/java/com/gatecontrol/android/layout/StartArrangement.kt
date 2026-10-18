package com.gatecontrol.android.layout

import com.gatecontrol.android.quickactions.QuickActionRepository
import com.gatecontrol.android.support.CrashReports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The start tab's blocks in their order: the stored order ([TabLayoutRepository],
 * from the account where the server keeps it) over what is pinned on this
 * device ([QuickActionRepository]). The pins follow the order — buttons in
 * it for shortcuts, Quick Settings tiles and the home-screen widget, the
 * widgets' widths — and a pin change goes into the order.
 */
@Singleton
class StartArrangement @Inject constructor(
    private val quickActions: QuickActionRepository,
    private val tabLayout: TabLayoutRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    val entries: StateFlow<List<StartEntry>> =
        combine(tabLayout.layout, quickActions.pins, quickActions.widgets) { layout, pins, widgets ->
            StartLayout.merge(layout.start, pins.map { it.key }, widgets.map { StartEntry.Widget(it.device, it.wide) })
        }.stateIn(scope, SharingStarted.Eagerly, StartLayout.merge(emptyList(), emptyList(), emptyList()))

    val access: StateFlow<LayoutAccess> = tabLayout.access

    init {
        // An order from the account (or arranged here): the pins take its order and widths.
        scope.launch {
            tabLayout.layout.map { it.start }.distinctUntilChanged().collect { saved ->
                if (saved.isEmpty()) return@collect
                val decoded = saved.mapNotNull(StartLayout::decode)
                quickActions.arrange(
                    tiles = decoded.filterIsInstance<StartEntry.Tile>().map { it.action },
                    widgets = decoded.filterIsInstance<StartEntry.Widget>().map { it.device },
                    widths = StartLayout.widths(saved),
                )
            }
        }
    }

    /** Arranged by the person: the new order of all blocks. */
    suspend fun save(entries: List<StartEntry>) = tabLayout.saveStart(StartLayout.encodeAll(entries))

    /** After pinning, unpinning or a new width (pin sheet): the order takes the pins as they are now. */
    suspend fun syncFromPins() {
        val merged = StartLayout.merge(
            tabLayout.current().start,
            quickActions.currentPins().map { it.key },
            quickActions.currentWidgets().map { StartEntry.Widget(it.device, it.wide) },
        )
        tabLayout.saveStart(StartLayout.encodeAll(merged))
    }

    /** Asks the server for the account's order (throttled). */
    fun refresh() {
        scope.launch { tabLayout.refresh() }
    }
}
