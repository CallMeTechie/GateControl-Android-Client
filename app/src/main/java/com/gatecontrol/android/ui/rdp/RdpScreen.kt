package com.gatecontrol.android.ui.rdp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.theme.GateControlTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdpScreen(viewModel: RdpViewModel = hiltViewModel()) {
    val routes by viewModel.routes.collectAsState()
    val filteredRoutes by viewModel.filteredRoutes.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val selectedRoute by viewModel.selectedRoute.collectAsState()
    val connectState by viewModel.connectState.collectAsState()
    val activeSessions by viewModel.activeSessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val extra = GateControlTheme.extraColors

    LaunchedEffect(Unit) {
        viewModel.loadRoutes()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GcScreenTitle(
                    title = stringResource(R.string.rdp_title),
                    subtitle = if (routes.isNotEmpty()) {
                        stringResource(
                            R.string.rdp_summary,
                            routes.size,
                            routes.count { it.status?.online == true },
                        )
                    } else {
                        null
                    },
                )

                // --- Search field ---
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.rdp_search), color = extra.faint) },
                    leadingIcon = {
                        Icon(GcIcons.Search, contentDescription = null, tint = extra.faint, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedBorderColor = extra.blue,
                        unfocusedBorderColor = extra.border2,
                        cursorColor = extra.blue,
                    ),
                )

                // --- Filter chips ---
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcFilterChip(
                        text = stringResource(R.string.rdp_filter_all),
                        selected = statusFilter == StatusFilter.ALL,
                        onClick = { viewModel.setStatusFilter(StatusFilter.ALL) },
                    )
                    GcFilterChip(
                        text = stringResource(R.string.rdp_filter_online),
                        selected = statusFilter == StatusFilter.ONLINE,
                        onClick = { viewModel.setStatusFilter(StatusFilter.ONLINE) },
                    )
                    GcFilterChip(
                        text = stringResource(R.string.rdp_filter_offline),
                        selected = statusFilter == StatusFilter.OFFLINE,
                        onClick = { viewModel.setStatusFilter(StatusFilter.OFFLINE) },
                    )
                }
            }

            // --- Route list, error, or empty state ---
            if (isLoading && filteredRoutes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (error != null && filteredRoutes.isEmpty()) {
                ErrorState(
                    errorType = error!!,
                    onRetry = { viewModel.loadRoutes() },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (filteredRoutes.isEmpty()) {
                EmptyState(
                    filtered = routes.isNotEmpty(),
                    onReset = {
                        viewModel.setSearchQuery("")
                        viewModel.setStatusFilter(StatusFilter.ALL)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val activeSessionRouteIds = activeSessions.map { it.routeId }.toSet()
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredRoutes, key = { it.id }) { route ->
                        RdpHostCard(
                            route = route,
                            isSessionActive = route.id in activeSessionRouteIds,
                            onConnect = { viewModel.selectRoute(route) },
                            onWol = { viewModel.sendWol(route.id) },
                            onClick = { viewModel.selectRoute(route) },
                        )
                    }
                }
            }
        }

        // --- Bottom sheet ---
        selectedRoute?.let { route ->
            RdpConnectSheet(
                route = route,
                connectState = connectState,
                onConnect = { password, forceBypass ->
                    viewModel.connect(route.id, password, forceBypass)
                },
                onDisconnect = { viewModel.disconnect(route.id) },
                onDismiss = { viewModel.dismissSheet() },
                onWol = { viewModel.sendWol(route.id) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Error state
// ---------------------------------------------------------------------------

@Composable
private fun ErrorState(
    errorType: ErrorType,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (errorType) {
        ErrorType.Forbidden -> stringResource(R.string.rdp_error_forbidden)
        ErrorType.Network -> stringResource(R.string.rdp_error_network)
        ErrorType.ServerError -> stringResource(R.string.rdp_error_server)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            GcOutlineButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
                fillWidth = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(filtered: Boolean, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(if (filtered) R.string.rdp_no_match else R.string.rdp_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            if (filtered) {
                Text(
                    stringResource(R.string.rdp_no_match_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GateControlTheme.extraColors.muted,
                    textAlign = TextAlign.Center,
                )
                GcOutlineButton(
                    text = stringResource(R.string.rdp_reset_filters),
                    onClick = onReset,
                    fillWidth = false,
                )
            }
        }
    }
}
