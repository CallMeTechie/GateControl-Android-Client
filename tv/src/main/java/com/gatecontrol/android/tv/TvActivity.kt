package com.gatecontrol.android.tv

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.pair.PairState
import com.gatecontrol.android.tv.pair.PairingViewModel
import com.gatecontrol.android.tv.pair.TvPairingApi
import com.gatecontrol.android.tv.tunnel.TvSupervisor
import com.gatecontrol.android.tv.ui.components.TvPillTone
import com.gatecontrol.android.tv.ui.home.TvHome
import com.gatecontrol.android.tv.ui.home.TvTopStatus
import com.gatecontrol.android.tv.ui.pair.TvPairScreen
import com.gatecontrol.android.tv.ui.settings.TvConnectionUi
import com.gatecontrol.android.tv.ui.settings.TvSettingsPage
import com.gatecontrol.android.tv.ui.settings.TvUpdateUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.update.TvUpdateViewModel
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * The only activity of GateControl TV (Fire TV / Android TV launcher): the pairing until the TV is set up, then the
 * start page with the rail. Asks for the VPN consent (`VpnService.prepare`, once per install) when the VPN is
 * switched on or right after the pairing.
 */
@AndroidEntryPoint
class TvActivity : ComponentActivity() {

    private val pairing: PairingViewModel by viewModels()
    private val main: TvMainViewModel by viewModels()
    private val updates: TvUpdateViewModel by viewModels()

    @Inject lateinit var pairingApi: TvPairingApi
    @Inject lateinit var supervisor: TvSupervisor

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        main.onConsent(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GateControlTvTheme {
                val state by pairing.state.collectAsStateWithLifecycle()
                if (state is PairState.Paired) {
                    val pairedNow by pairing.pairedNow.collectAsStateWithLifecycle()
                    // Just paired: the tunnel is on by default — the consent once, then connect.
                    LaunchedEffect(pairedNow) {
                        if (pairedNow) {
                            pairing.consumePairedNow()
                            setVpn(true)
                        }
                    }
                    Paired()
                } else {
                    TvPairScreen(state, onEvent = pairing::dispatch)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        pairing.setVisible(true)
    }

    override fun onStop() {
        pairing.setVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // back from the system page "Unbekannte Apps installieren": the installer follows if it is allowed now
        updates.onResume(this)
        // the push service, if Fire OS refused to start it from the background
        supervisor.onForeground()
    }

    @Composable
    private fun Paired() {
        val tunnel by main.tunnelState.collectAsStateWithLifecycle()
        val autostart by main.autostartTunnel.collectAsStateWithLifecycle()
        val openOnBoot by main.openOnBoot.collectAsStateWithLifecycle()
        val consentDenied by main.consentDenied.collectAsStateWithLifecycle()
        val check by updates.check.collectAsStateWithLifecycle()
        val download by updates.download.collectAsStateWithLifecycle()
        val host = main.serverHost()
        val status = when (tunnel) {
            is TunnelState.Connected -> TvTopStatus(stringResource(R.string.tv_status_vpn_on, host), TvPillTone.Accent)
            is TunnelState.Connecting, is TunnelState.Reconnecting -> TvTopStatus(stringResource(R.string.tv_vpn_connecting), TvPillTone.Info)
            else -> TvTopStatus(stringResource(R.string.tv_status_vpn_off, host), TvPillTone.Neutral)
        }
        TvHome(
            status = status,
            settingsPage = {
                TvSettingsPage(
                    connection = TvConnectionUi(tunnel, host, main.tunnelAddress(), autostart, openOnBoot, consentDenied),
                    update = TvUpdateUi(updates.version, check, download),
                    deviceName = pairingApi.deviceName(),
                    onVpn = ::setVpn,
                    onAutostartTunnel = main::setAutostartTunnel,
                    onOpenOnBoot = main::setOpenOnBoot,
                    onCheckUpdate = updates::checkNow,
                    onInstallUpdate = { updates.install(this) },
                    onUnpair = { main.unpair { pairing.reset() } },
                )
            },
        )
    }

    /** VPN on (consent first, if not given yet) or off. */
    private fun setVpn(on: Boolean) {
        if (!on) {
            main.disconnect()
            return
        }
        val consent = runCatching { VpnService.prepare(this) }.getOrNull()
        if (consent == null) {
            main.connect()
            return
        }
        runCatching { vpnConsent.launch(consent) }.onFailure {
            // no dialog for it on this system
            Timber.w("VPN consent could not be asked (%s)", it.javaClass.simpleName)
            main.onConsent(false)
        }
    }
}
