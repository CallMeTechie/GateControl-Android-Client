package com.gatecontrol.android.familie

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.familie.push.FamilieOpenActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** [context] in the app's own language (Settings → language), for texts made outside a screen. */
suspend fun familieLocalized(context: Context, settingsRepository: SettingsRepository): Context {
    val lang = runCatching { settingsRepository.getLocale().first() }.getOrNull() ?: return context
    val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
    return context.createConfigurationContext(config)
}

/**
 * The "Notfall-Karte" app shortcut (long press on the app icon): a dynamic
 * shortcut, there only while the emergency module is on for this member.
 * It opens the card through the non-exported [FamilieOpenActivity]; the
 * card itself works offline from the encrypted copy.
 */
@Singleton
class FamilieShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun update(emergency: Boolean) {
        runCatching {
            val existing = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == ID_EMERGENCY }
            if (emergency) {
                val ctx = familieLocalized(context, settingsRepository)
                val short = ctx.getString(R.string.familie_emergency_title)
                if (existing?.shortLabel?.toString() != short) {
                    ShortcutManagerCompat.pushDynamicShortcut(context, build(context, short, ctx.getString(R.string.familie_emergency_shortcut_long)))
                }
            } else if (existing != null) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID_EMERGENCY))
            }
        }.onFailure { Timber.w("Familie: shortcut not updated (%s)", it.javaClass.simpleName) }
    }

    companion object {
        /** Ids of the Familie shortcuts; other features that set the dynamic shortcuts keep them. */
        const val PREFIX = "familie:"
        const val ID_EMERGENCY = "familie:emergency"

        private fun build(context: Context, short: String, long: String): ShortcutInfoCompat =
            ShortcutInfoCompat.Builder(context, ID_EMERGENCY)
                .setShortLabel(short)
                .setLongLabel(long)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_emergency))
                .setIntent(FamilieOpenActivity.intent(context, FamilieDest.Emergency()).setAction(Intent.ACTION_VIEW))
                .setRank(0)
                .build()

        /** A Familie shortcut as the system returned it, rebuilt with its icon; null for other shortcuts. */
        fun rebuild(context: Context, existing: ShortcutInfoCompat): ShortcutInfoCompat? =
            if (existing.id == ID_EMERGENCY) {
                val short = existing.shortLabel.toString()
                build(context, short, existing.longLabel?.toString() ?: short)
            } else {
                null
            }
    }
}
