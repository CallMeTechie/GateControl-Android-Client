package com.gatecontrol.android.ui.rdp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RdpScreen(viewModel: RdpViewModel = hiltViewModel()) {
    val filteredRoutes by viewModel.filteredRoutes.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val statusFilter by viewModel.statusFilter.collectAsState()
    val selectedRoute by viewModel.selectedRoute.collectAsState()
    val connectState by viewModel.connectState.collectAsState()
    val activeSessions by viewModel.activeSessions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadRoutes()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rdp_title)) }
            )
        }
    ) { paddingValues ->

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.loadRoutes() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // --- Search bar ---
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.rdp_search)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null
                        )
                    },
                    singleLine = true
                )

                // --- Filter chips ---
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = statusFilter == StatusFilter.ALL,
                            onClick = { viewModel.setStatusFilter(StatusFilter.ALL) },
                            label = { Text(stringResource(R.string.rdp_filter_all)) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = statusFilter == StatusFilter.ONLINE,
                            onClick = { viewModel.setStatusFilter(StatusFilter.ONLINE) },
                            label = { Text(stringResource(R.string.rdp_filter_online)) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = statusFilter == StatusFilter.OFFLINE,
                            onClick = { viewModel.setStatusFilter(StatusFilter.OFFLINE) },
                            label = { Text(stringResource(R.string.rdp_filter_offline)) }
                        )
                    }
                }

                // --- Route list or empty state ---
                if (isLoading && filteredRoutes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (filteredRoutes.isEmpty()) {
                    EmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    val activeSessionRouteIds = activeSessions.map { it.routeId }.toSet()
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredRoutes, key = { it.id }) { route ->
                            RdpHostCard(
                                route = route,
                                isSessionActive = route.id in activeSessionRouteIds,
                                onConnect = { viewModel.selectRoute(route) },
                                onWol = { viewModel.sendWol(route.id) },
                                onClick = { viewModel.selectRoute(route) }
                            )
                        }
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
                onWol = { viewModel.sendWol(route.id) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.rdp_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp)
        )
    }
}
