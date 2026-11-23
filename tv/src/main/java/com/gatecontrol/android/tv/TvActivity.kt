package com.gatecontrol.android.tv

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.quickactions.QuickActionsLoad
import com.gatecontrol.android.tunnel.TunnelState
import com.gatecontrol.android.tv.doorbell.TvForeground
import com.gatecontrol.android.tv.drive.TvDriveOpen
import com.gatecontrol.android.tv.drive.TvDriveViewModel
import com.gatecontrol.android.tv.ui.drive.TvDrivePage
import com.gatecontrol.android.tv.ui.drive.TvMediaActivity
import android.content.Intent
import com.gatecontrol.android.tv.home.TvHomeViewModel
import com.gatecontrol.android.tv.security.TvPinFunction
import com.gatecontrol.android.tv.ui.cameras.TvCamerasPage
import com.gatecontrol.android.tv.ui.components.TvConfirmDialog
import com.gatecontrol.android.tv.ui.components.TvPinDialog
import com.gatecontrol.android.tv.ui.home.TvDestination
import com.gatecontrol.android.tv.ui.home.TvStartRows
import com.gatecontrol.android.tv.ui.home.TvStartUi
import com.gatecontrol.android.tv.ui.settings.TvNoticeOptions
import com.gatecontrol.android.tv.ui.settings.TvSecurityOptions
import com.gatecontrol.android.tv.ui.settings.TvSplitTunnelOptions
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
    private val home: TvHomeViewModel by viewModels()
    private val prefs: TvPrefsViewModel by viewModels()
    private val drive: TvDriveViewModel by viewModels()

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
        TvForeground.onStart()
        pairing.setVisible(true)
    }

    override fun onStop() {
        pairing.setVisible(false)
        TvForeground.onStop()
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
        val tiles by home.tiles.collectAsStateWithLifecycle()
        val busy by home.busy.collectAsStateWithLifecycle()
        val cameras by home.cameras.collectAsStateWithLifecycle()
        val previews by home.previews.collectAsStateWithLifecycle()
        val notice by home.notice.collectAsStateWithLifecycle()
        val load by home.load.collectAsStateWithLifecycle()
        val pending by home.pending.collectAsStateWithLifecycle()
        val live by home.liveUi.collectAsStateWithLifecycle()
        val pinState by prefs.pin.collectAsStateWithLifecycle()
        val bell by prefs.doorbellSettings.collectAsStateWithLifecycle()
        val split by prefs.splitTunnel.collectAsStateWithLifecycle()
        val apps by prefs.apps.collectAsStateWithLifecycle()
        val bellCameras by prefs.cameras.collectAsStateWithLifecycle()
        val push by prefs.pushEnabled.collectAsStateWithLifecycle()
        var requested by remember { mutableStateOf<TvDestination?>(null) }
        var wanted by remember { mutableStateOf<String?>(null) }
        // A page waiting for the TV PIN (Einstellungen).
        var pinFor by remember { mutableStateOf<Pair<TvPinFunction, () -> Unit>?>(null) }
        val driveUi by drive.ui.collectAsStateWithLifecycle()
        val drivePreviews by drive.previews.collectAsStateWithLifecycle()
        val recent by drive.recent.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { home.refresh() }
        TvHome(
            status = status,
            startPage = {
                DisposableEffect(Unit) {
                    home.setVisible(true)
                    drive.loadRecent()
                    onDispose { home.setVisible(false) }
                }
                // with a TV PIN for Drive its files do not show on the start page either
                val driveOpen = !pinState.needsPin(TvPinFunction.DRIVE)
                TvStartRows(
                    ui = TvStartUi(
                        tiles, busy, cameras, previews, notice,
                        loading = load is QuickActionsLoad.Loading || load is QuickActionsLoad.Idle,
                        drive = if (driveOpen) recent else emptyList(),
                        drivePreviews = drivePreviews,
                    ),
                    onDrive = { node ->
                        when (drive.pressRecent(node)) {
                            TvDriveOpen.PHOTO, TvDriveOpen.VIDEO, TvDriveOpen.PREVIEW -> startActivity(Intent(this, TvMediaActivity::class.java))
                            TvDriveOpen.FOLDER -> requested = TvDestination.Drive
                            else -> Unit
                        }
                    },
                    onDrivePreview = drive::wantPreview,
                    onTile = home::press,
                    onSetpoint = { tile, control, value -> home.setControl(tile, control.id, value) },
                    onCamera = {
                        wanted = it.id
                        requested = TvDestination.Cameras
                    },
                )
            },
            camerasPage = {
                LaunchedEffect(wanted) { home.showLive(wanted) }
                TvCamerasPage(
                    cameras = cameras,
                    live = live,
                    sourceFactory = home.live::sourceFactory,
                    onShow = { wanted = it },
                    onLeave = {
                        wanted = null
                        home.stopLive()
                    },
                )
            },
            drivePage = {
                TvDrivePage(
                    ui = driveUi,
                    previews = drivePreviews,
                    onStart = drive::start,
                    onArea = drive::openArea,
                    onItem = { node ->
                        drive.press(node).also { open ->
                            if (open == TvDriveOpen.PHOTO || open == TvDriveOpen.VIDEO || open == TvDriveOpen.PREVIEW) {
                                startActivity(Intent(this, TvMediaActivity::class.java))
                            }
                        }
                    },
                    onBack = drive::back,
                    onMore = drive::more,
                    onPreview = drive::wantPreview,
                    onRetry = drive::reload,
                )
            },
            onOpen = { destination, open ->
                val function = when (destination) {
                    TvDestination.Settings -> TvPinFunction.SETTINGS
                    TvDestination.Drive -> TvPinFunction.DRIVE
                    else -> null
                }
                if (function != null && pinState.needsPin(function)) pinFor = function to open else open()
            },
            requested = requested,
            onRequestedShown = { requested = null },
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
                    splitTunnel = { TvSplitTunnelOptions(split, apps, prefs::loadApps, prefs::setSplitTunnel) },
                    notices = {
                        TvNoticeOptions(push, prefs::setPush, bell, prefs::setDoorbell, prefs.canDrawOverlays(), bellCameras, prefs::loadCameras)
                    },
                    security = { TvSecurityOptions(pinState, prefs::checkPin, prefs::setPin, prefs::removePin, prefs::setRequired) },
                )
            },
        )
        pinFor?.let { (function, open) ->
            TvPinDialog(
                title = stringResource(if (function == TvPinFunction.DRIVE) R.string.tv_pin_for_drive else R.string.tv_pin_for_settings),
                check = prefs::checkPin,
                onPassed = {
                    pinFor = null
                    open()
                },
                onDismiss = { pinFor = null },
            )
        }
        pending?.let { p ->
            if (p.needsPin) {
                TvPinDialog(stringResource(R.string.tv_pin_for_door), prefs::checkPin, home::pinPassed, home::cancelPending)
            } else if (p.needsConfirm) {
                TvConfirmDialog(
                    title = stringResource(R.string.tv_confirm_title, p.item.title),
                    text = p.item.subtitle,
                    confirm = stringResource(R.string.tv_confirm_run),
                    onConfirm = home::confirmPending,
                    onDismiss = home::cancelPending,
                )
            }
        }
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
