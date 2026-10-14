package com.gatecontrol.android.quickactions

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.appwidget.updateAll
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.FamilieShortcuts
import timber.log.Timber

/**
 * Brings the pinned quick actions to the places outside the app: app
 * shortcuts (long press on the icon), the Quick Settings tiles and the
 * "Schnellaktionen" widget.
 */
object QuickActionSurfaces {
    /** Launchers show about four; more only clutter the menu. */
    const val MAX_SHORTCUTS = 4

    suspend fun refresh(context: Context, pins: List<QuickActionItem>) {
        runCatching { publishShortcuts(context, pins) }
            .onFailure { Timber.w("Quick actions: shortcuts not updated (%s)", it.javaClass.simpleName) }
        QuickActionTiles.ALL.forEach { cls ->
            runCatching { TileService.requestListeningState(context, ComponentName(context, cls)) }
        }
        runCatching { QuickActionsWidget().updateAll(context) }
            .onFailure { Timber.w("Quick actions: widget not updated (%s)", it.javaClass.simpleName) }
    }

    private fun publishShortcuts(context: Context, pins: List<QuickActionItem>) {
        // Other features' shortcuts (Familie: "Notfall-Karte") stay and come first.
        // Rebuilt (the system hands them back without their icon).
        val kept = ShortcutManagerCompat.getDynamicShortcuts(context).mapNotNull { FamilieShortcuts.rebuild(context, it) }
        val shortcuts = pins.take((MAX_SHORTCUTS - kept.size).coerceAtLeast(0)).mapIndexed { index, item ->
            ShortcutInfoCompat.Builder(context, "qa:${item.key}")
                .setShortLabel(item.title)
                .setLongLabel(listOfNotNull(item.title, item.subtitle).joinToString(" · ").take(25))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_quick_action_shortcut))
                .setIntent(QuickActionActivity.intent(context, item))
                .setRank(kept.size + index)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, kept + shortcuts)
    }
}
