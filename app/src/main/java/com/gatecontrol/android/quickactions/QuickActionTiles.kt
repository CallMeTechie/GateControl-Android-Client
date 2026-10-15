package com.gatecontrol.android.quickactions

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tiles for the first three pinned quick actions (slot 1–3).
 * A switch shows its last known state; a tap runs the action right away,
 * except actions that want a confirmation: those open the confirmation and,
 * on a locked device, only after unlocking. An empty slot opens the app.
 */
abstract class QuickActionTileService(private val slot: Int) : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun quickActionRepository(): QuickActionRepository
    }

    private val repository: QuickActionRepository
        get() = EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java).quickActionRepository()

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val item = repository.currentPins().getOrNull(slot)
            withContext(Dispatchers.Main) { render(item) }
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val item = repository.currentPins().getOrNull(slot)
            withContext(Dispatchers.Main) {
                when {
                    item == null -> open(Intent(this@QuickActionTileService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    item.confirm -> {
                        val run = { open(QuickActionActivity.intent(this@QuickActionTileService, item)) }
                        if (isLocked) unlockAndRun(run) else run()
                    }
                    else -> {
                        // Optimistic: flip the switch now, the result corrects it.
                        if (item.toggle) render(item.copy(state = if (item.isOn) "off" else "on"))
                        scope.launch {
                            val result = repository.run(item)
                            withContext(Dispatchers.Main) {
                                QuickActionActivity.showResult(applicationContext, item, result)
                                render(repository.currentPins().getOrNull(slot))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun render(item: QuickActionItem?) {
        val tile = qsTile ?: return
        // The action's own icon (drawn as a white mask, tinted by the system), else the bolt.
        val px = (24 * resources.displayMetrics.density).toInt()
        tile.icon = item?.icon?.let { QuickActionIcons.bitmap(it, px, android.graphics.Color.WHITE) }
            ?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(this, R.drawable.ic_quick_action)
        if (item == null) {
            tile.label = getString(R.string.quick_action_tile_label, slot + 1)
            tile.subtitle = getString(R.string.quick_action_tile_unset)
            tile.state = Tile.STATE_INACTIVE
        } else {
            tile.label = item.title
            tile.subtitle = when {
                item.toggle -> getString(if (item.isOn) R.string.quick_action_on else R.string.quick_action_off)
                else -> item.subtitle ?: item.pluginName
            }
            tile.state = when {
                !item.toggle -> Tile.STATE_INACTIVE
                item.isOn -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
        }
        tile.updateTile()
    }

    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, slot, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    }
}

class QuickActionTile1 : QuickActionTileService(0)
class QuickActionTile2 : QuickActionTileService(1)
class QuickActionTile3 : QuickActionTileService(2)

object QuickActionTiles {
    val ALL = listOf(QuickActionTile1::class.java, QuickActionTile2::class.java, QuickActionTile3::class.java)
}
