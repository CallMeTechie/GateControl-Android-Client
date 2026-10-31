package com.gatecontrol.android.layout

import com.gatecontrol.android.mail.MailContactLogic
import com.gatecontrol.android.mail.MailPimRepository
import com.gatecontrol.android.mail.StartContactPin
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
    private val mail: MailPimRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

    /** The blocks of the start tab (key null) and of each widget page beside it ([StartPages]); Mail's favourites and calendar widget too. */
    val pages: StateFlow<Map<String?, List<StartEntry>>> =
        combine(tabLayout.layout, quickActions.pins, quickActions.widgets, mail.startPins) { layout, pins, widgets, mailPins ->
            StartPages.merge(layout.start, pins.map { it.key }, widgets.map { StartEntry.Widget(it.device, it.wide) }, mailPins.extras)
        }.stateIn(scope, SharingStarted.Eagerly, StartPages.merge(emptyList(), emptyList(), emptyList()))

    /** The start tab's blocks. */
    val entries: StateFlow<List<StartEntry>> = pages.map { it[null].orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, pages.value[null].orEmpty())

    val access: StateFlow<LayoutAccess> = tabLayout.access

    /** What Mail pins to Start on this device. */
    val mailPins: StateFlow<com.gatecontrol.android.mail.MailStartPins> = mail.startPins

    init {
        // An order from the account (or arranged here): the pins take its order and widths
        // (the pages' markers are no blocks: decode skips them).
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

    /** Arranged by the person: the new order of all blocks of [page] (null: the start tab). */
    suspend fun save(entries: List<StartEntry>, page: String? = null) =
        tabLayout.updateStart { StartPages.withEntries(it, page, StartLayout.encodeAll(entries)) }

    /**
     * After pinning, unpinning or a new width (pin sheet): the order takes
     * the pins as they are now; a block pinned just now joins [page] (the
     * widget page it was pinned from, null: the start tab).
     */
    suspend fun syncFromPins(page: String? = null) {
        val tiles = quickActions.currentPins().map { it.key }
        val widgets = quickActions.currentWidgets().map { StartEntry.Widget(it.device, it.wide) }
        val extras = mail.currentStartPins().extras
        tabLayout.updateStart { StartPages.syncPins(it, tiles, widgets, page, extras) }
    }

    /**
     * A favourite on Start ([on]) or off it; it joins [page] (a widget page, null: the start tab). Mail's pins are kept
     * on this device like the plugins' ones.
     */
    suspend fun pinContact(pin: StartContactPin, on: Boolean, page: String? = null) {
        mail.updateStartPins { MailContactLogic.withPin(it, pin, on) }
        syncFromPins(page)
    }

    /** The calendar widget on Start ([on]) or off it. */
    suspend fun pinAgenda(on: Boolean, page: String? = null) {
        mail.updateStartPins { it.copy(agenda = on) }
        if (on) mail.refreshUpcomingSoon()
        syncFromPins(page)
    }

    /** Asks the server for the account's order (throttled). */
    fun refresh() {
        scope.launch { tabLayout.refresh() }
    }
}
