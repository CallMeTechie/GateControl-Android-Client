package com.gatecontrol.android.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Invisible, NON-exported activity that performs Quick Settings tile actions.
 *
 * Tile actions used to be extras on the exported MainActivity, so any app
 * could connect/disconnect the VPN with a crafted intent. This component is
 * `android:exported="false"`: only this app (the tile's PendingIntent) can
 * start it. It needs to be an activity (not a service) because connecting may
 * require the system VPN consent dialog.
 *
 * Not `noHistory`: a noHistory activity is finished as soon as the consent
 * dialog covers it and would never receive the result. It is excluded from
 * recents and finishes itself instead.
 */
@AndroidEntryPoint
class TileActionActivity : ComponentActivity() {

    @Inject lateinit var tileActionHandler: TileActionHandler

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            actionScope.launch { tileActionHandler.connect() }
        } else {
            Timber.d("TileActionActivity: VPN permission denied")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-created after a config change while the consent dialog is up:
        // the pending result is redelivered to the launcher above.
        if (savedInstanceState != null) return

        when (TileAction.parse(intent?.getStringExtra(EXTRA_ACTION))) {
            TileAction.CONNECT -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    try {
                        vpnPermission.launch(prepareIntent)
                    } catch (e: Exception) {
                        Timber.e(e, "TileActionActivity: cannot request VPN permission")
                        finish()
                    }
                    return
                }
                actionScope.launch { tileActionHandler.connect() }
            }
            TileAction.DISCONNECT -> actionScope.launch { tileActionHandler.disconnect() }
            null -> Timber.w("TileActionActivity: unknown tile action ignored")
        }
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "com.gatecontrol.android.extra.TILE_ACTION"

        /** Outlives the (immediately finished) activity; tunnel calls are short. */
        private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Explicit intent to this non-exported component. */
        fun intent(context: Context, action: TileAction): Intent =
            Intent(context, TileActionActivity::class.java)
                .putExtra(EXTRA_ACTION, action.wireValue)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
    }
}
