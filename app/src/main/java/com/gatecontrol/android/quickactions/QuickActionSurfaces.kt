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
                .setIcon(shortcutIcon(context, item))
                .setIntent(QuickActionActivity.intent(context, item))
                .setRank(kept.size + index)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, kept + shortcuts)
    }

    /** The action's icon on the accent circle (like the bolt shortcut icon), else that bolt. */
    private fun shortcutIcon(context: Context, item: QuickActionItem): IconCompat {
        val px = (48 * context.resources.displayMetrics.density).toInt()
        val bitmap = QuickActionIcons.bitmap(item.displayIcon, px, SHORTCUT_GLYPH, background = SHORTCUT_BG, glyphFraction = 0.5f)
        return if (bitmap != null) IconCompat.createWithBitmap(bitmap)
        else IconCompat.createWithResource(context, R.drawable.ic_quick_action_shortcut)
    }

    /** Colours of ic_quick_action_shortcut. */
    private const val SHORTCUT_BG = 0xFF3DDC97.toInt()
    private const val SHORTCUT_GLYPH = 0xFF03261A.toInt()
}
