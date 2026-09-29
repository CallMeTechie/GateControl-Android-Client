package com.gatecontrol.android.ui.services

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * Services published through GateControl, inside the "Netzwerk" tab. While
 * the VPN is down a banner explains why they may be unreachable.
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
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
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
                else -> GcCard(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.Top) {
                    uiState.services.forEach { service ->
                        ServiceRow(service = service, onClick = { viewModel.openService(service.url) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(service: VpnService, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    GcListRow(
        title = service.name,
        description = service.domain,
        descriptionMono = true,
        onClick = onClick,
        leading = {
            GcIconSquare {
                Text(
                    service.name.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize),
                    color = extra.accentText,
                )
            }
        },
        trailing = {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (service.hasAuth) {
                    GcChip(stringResource(R.string.services_auth_badge), tone = GcTone.Info, small = true)
                }
                Icon(GcIcons.External, contentDescription = null, tint = extra.faint, modifier = Modifier.size(18.dp))
            }
        },
    )
}
