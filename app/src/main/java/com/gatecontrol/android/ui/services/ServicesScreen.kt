package com.gatecontrol.android.ui.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.network.VpnService
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Services published through GateControl, inside the "Netzwerk" tab:
 * favourites on top, a status dot for monitored routes, and a tap opens the
 * service in a Custom Tab — signed in right away when the route allows the
 * automatic login from the apps. While the VPN is down a banner explains why
 * they may be unreachable.
 */
@Composable
fun ServicesContent(
    vpnConnected: Boolean,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServicesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val extra = GateControlTheme.extraColors

    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { url ->
            openInApp(context, url)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!vpnConnected) {
            item {
                GcBanner(tone = GcTone.Info) {
                    Text(
                        stringResource(R.string.services_vpn_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    GcPrimaryButton(
                        text = stringResource(R.string.vpn_connect),
                        onClick = onConnect,
                        fillWidth = false,
                        minHeight = 40.dp,
                    )
                }
            }
        }
        item {
            when {
                uiState.isLoading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                uiState.services.isEmpty() -> Text(
                    text = stringResource(R.string.services_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
                else -> {
                    val sections = uiState.sections
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (sections.favorites.isNotEmpty()) {
                            GcSectionLabel(stringResource(R.string.services_favorites), Modifier.padding(start = 4.dp))
                            ServiceCard(sections.favorites, uiState, viewModel)
                            if (sections.others.isNotEmpty()) {
                                GcSectionLabel(stringResource(R.string.services_all), Modifier.padding(start = 4.dp, top = 6.dp))
                            }
                        }
                        if (sections.others.isNotEmpty()) ServiceCard(sections.others, uiState, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(services: List<VpnService>, uiState: ServicesUiState, viewModel: ServicesViewModel) {
    GcCard(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.Top) {
        services.forEach { service ->
            ServiceRow(
                service = service,
                favorite = service.domain in uiState.favorites,
                opening = uiState.openingId == service.id,
                onClick = { viewModel.openService(service) },
                onToggleFavorite = { viewModel.toggleFavorite(service) },
            )
        }
    }
}

/** Custom Tab (in-app browser); any browser when none supports it, nothing without one. */
private fun openInApp(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    }.recoverCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun ServiceRow(
    service: VpnService,
    favorite: Boolean,
    opening: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    GcListRow(
        title = service.name,
        description = service.domain,
        descriptionMono = true,
        onClick = onClick,
        leading = {
            Box {
                GcIconSquare {
                    Text(
                        service.name.trim().take(1).uppercase(),
                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                        color = extra.accentText,
                    )
                }
                StatusDot(service.status, Modifier.align(Alignment.BottomEnd))
            }
        },
        trailing = {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    service.appLogin == true ->
                        GcChip(stringResource(R.string.services_auto_login_badge), tone = GcTone.Ok, small = true)
                    service.hasAuth || service.auth == "route" || service.auth == "basic" ->
                        GcChip(stringResource(R.string.services_auth_badge), tone = GcTone.Info, small = true)
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) GcIcons.StarFilled else GcIcons.Star,
                        contentDescription = stringResource(
                            if (favorite) R.string.services_favorite_remove else R.string.services_favorite_add,
                        ),
                        tint = if (favorite) extra.warn else extra.faint,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (opening) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = extra.faint)
                } else {
                    Icon(GcIcons.External, contentDescription = null, tint = extra.faint, modifier = Modifier.size(18.dp))
                }
            }
        },
    )
}

/** Green / red dot for monitored routes; nothing when the route is not monitored. */
@Composable
private fun StatusDot(status: String?, modifier: Modifier = Modifier) {
    val (color, label) = when (status) {
        "up" -> GateControlTheme.extraColors.accentText to stringResource(R.string.services_status_up)
        "down" -> MaterialTheme.colorScheme.error to stringResource(R.string.services_status_down)
        else -> return
    }
    Box(
        modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = label },
    )
}
