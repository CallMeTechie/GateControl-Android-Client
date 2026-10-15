package com.gatecontrol.android.quickactions

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Home-screen widget with the pinned quick actions as buttons with their
 * icon (2 on the small size, up to 4 per row on the wide one, more rows
 * when taller). A
 * tap runs the action through the non-exported [QuickActionActivity], which
 * asks first where the action wants it.
 */
class QuickActionsWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun quickActionRepository(): QuickActionRepository
    }

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .quickActionRepository()
        provideContent {
            val pins by repository.pins.collectAsState()
            Content(pins)
        }
    }

    private companion object {
        val surface = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF161B22))
        val onSurface = ColorProvider(day = Color(0xFF14181F), night = Color(0xFFE6EAF0))
        val muted = ColorProvider(day = Color(0xFF5B6574), night = Color(0xFFA6AFBC))
        val buttonBg = ColorProvider(day = Color(0xFFE8EBEF), night = Color(0xFF262D38))
        val onBg = ColorProvider(day = Color(0xFFD5F5E6), night = Color(0xFF173B2C))
        val accent = ColorProvider(day = Color(0xFF13A86A), night = Color(0xFF3DDC97))
        val BUTTON_WIDTH = 96.dp
        val ICON_SIZE = 18.dp
        val ROW_HEIGHT = 52.dp
    }

    @Composable
    private fun Content(pins: List<QuickActionItem>) {
        val context = LocalContext.current
        val size = LocalSize.current
        val perRow = (size.width.value / (BUTTON_WIDTH.value + 8f)).toInt().coerceIn(1, 4)
        val rows = (size.height.value / (ROW_HEIGHT.value + 4f)).toInt().coerceAtLeast(1)
        val shown = pins.take(perRow * rows)
        val openApp = actionStartActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        Column(
            modifier = GlanceModifier.fillMaxSize().background(surface).cornerRadius(18.dp).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shown.isEmpty()) {
                Row(
                    modifier = GlanceModifier.fillMaxSize().clickable(openApp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(ImageProvider(R.drawable.ic_quick_action), null, colorFilter = ColorFilter.tint(muted), modifier = GlanceModifier.size(22.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        context.getString(R.string.quick_actions_widget_empty),
                        style = TextStyle(color = muted, fontSize = 13.sp),
                        maxLines = 2,
                    )
                }
                return@Column
            }
            shown.chunked(perRow).forEachIndexed { i, row ->
                if (i > 0) Spacer(GlanceModifier.height(4.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    row.forEachIndexed { j, item ->
                        if (j > 0) Spacer(GlanceModifier.width(6.dp))
                        ActionButton(context, item, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }

    @Composable
    private fun ActionButton(context: Context, item: QuickActionItem, modifier: GlanceModifier) {
        // The action's own icon (SVG path from the server) drawn into a bitmap
        // in white and tinted here; the bolt when it has none.
        val iconPx = (ICON_SIZE.value * context.resources.displayMetrics.density).toInt()
        val glyph = QuickActionIcons.bitmap(item.icon, iconPx, android.graphics.Color.WHITE)
        val iconTint = ColorFilter.tint(if (item.isOn) accent else onSurface)
        Row(
            modifier = modifier
                .height(ROW_HEIGHT)
                .background(if (item.isOn) onBg else buttonBg)
                .cornerRadius(14.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clickable(actionStartActivity(QuickActionActivity.intent(context, item))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                if (glyph != null) ImageProvider(glyph) else ImageProvider(R.drawable.ic_quick_action),
                contentDescription = null,
                colorFilter = iconTint,
                modifier = GlanceModifier.size(ICON_SIZE),
            )
            Spacer(GlanceModifier.width(6.dp))
            Column(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    style = TextStyle(color = onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    when {
                        item.toggle -> context.getString(if (item.isOn) R.string.quick_action_on else R.string.quick_action_off)
                        else -> item.subtitle ?: item.pluginName
                    },
                    style = TextStyle(color = if (item.isOn) accent else muted, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Registers [QuickActionsWidget] with the launcher. */
class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}
