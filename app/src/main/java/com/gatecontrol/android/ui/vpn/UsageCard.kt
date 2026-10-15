package com.gatecontrol.android.ui.vpn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.network.TrafficStats
import com.gatecontrol.android.ui.components.GcPagerCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pages of the start tab's throughput/data usage card (pure, unit-tested). */
object UsageCardPages {
    const val THROUGHPUT = "throughput"
    const val USAGE = "usage"

    /** Shown first until the person long-presses another page. */
    const val DEFAULT_FAVORITE = USAGE

    /** Throughput always; data usage only with the traffic right and once loaded. */
    fun pages(usageAvailable: Boolean): List<String> =
        if (usageAvailable) listOf(THROUGHPUT, USAGE) else listOf(THROUGHPUT)

    /** The stored favourite, the default when none (or an unknown one) is stored. */
    fun favorite(stored: String?): String = stored?.takeIf { it == THROUGHPUT || it == USAGE } ?: DEFAULT_FAVORITE
}

/** Favourite page of the usage card, kept in the DataStore. */
@HiltViewModel
class UsageCardViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Null until read from storage (the card then waits with jumping to it). */
    val favorite: StateFlow<String?> = settingsRepository.getUsageCardFavorite()
        .map { UsageCardPages.favorite(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFavorite(page: String) {
        if (page != UsageCardPages.THROUGHPUT && page != UsageCardPages.USAGE) return
        viewModelScope.launch { settingsRepository.setUsageCardFavorite(page) }
    }
}

/**
 * One card for "Durchsatz" and "Datennutzung": the content slides sideways,
 * a long press on a page makes it the one shown first (default: Datennutzung).
 */
@Composable
fun UsageCard(
    rxHistory: List<Long>,
    txHistory: List<Long>,
    connected: Boolean,
    rxSpeed: Long,
    txSpeed: Long,
    /** Null: no traffic right or not loaded (only the throughput page). */
    traffic: TrafficStats?,
    modifier: Modifier = Modifier,
    viewModel: UsageCardViewModel = hiltViewModel(),
) {
    val favorite by viewModel.favorite.collectAsStateWithLifecycle()
    val throughputTitle = stringResource(R.string.vpn_bandwidth)
    val usageTitle = stringResource(R.string.traffic_title)
    GcPagerCard(
        pageKeys = UsageCardPages.pages(usageAvailable = traffic != null),
        favoriteKey = favorite,
        onSetFavorite = viewModel::setFavorite,
        title = { key -> if (key == UsageCardPages.USAGE) usageTitle else throughputTitle },
        modifier = modifier,
    ) { key ->
        when (key) {
            UsageCardPages.USAGE -> TrafficUsageContent(traffic = traffic)
            else -> BandwidthContent(
                rxHistory = rxHistory,
                txHistory = txHistory,
                connected = connected,
                rxSpeed = rxSpeed,
                txSpeed = txSpeed,
            )
        }
    }
}
