package com.gatecontrol.android.widget

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
import androidx.glance.action.Action
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.push.PushOpenActivity
import com.gatecontrol.android.service.TileAction
import com.gatecontrol.android.service.TileActionActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Home-screen widget: VPN state with a connect/disconnect button and, on the
 * wide size, the unread messages of the notification center.
 *
 * Connect and disconnect go through the non-exported [TileActionActivity],
 * like the Quick Settings tile: the system VPN consent and the client
 * policy ("always on" allows no disconnect) apply the same way.
 */
class VpnWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun widgetState(): WidgetState
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetState()
        state.start()
        provideContent {
            val model by state.model.collectAsState()
            Content(model)
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 48.dp)
        private val WIDE = DpSize(250.dp, 48.dp)

        private val surface = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF161B22))
        private val onSurface = ColorProvider(day = Color(0xFF14181F), night = Color(0xFFE6EAF0))
        private val muted = ColorProvider(day = Color(0xFF5B6574), night = Color(0xFFA6AFBC))
        private val good = ColorProvider(day = Color(0xFF13A86A), night = Color(0xFF3DDC97))
        private val warn = ColorProvider(day = Color(0xFFB7791F), night = Color(0xFFF5B544))
        private val bad = ColorProvider(day = Color(0xFFD64545), night = Color(0xFFFF8080))
        private val buttonBg = ColorProvider(day = Color(0xFFE8EBEF), night = Color(0xFF262D38))
        private val badgeBg = ColorProvider(Color(0xFF3DDC97))
        private val badgeFg = ColorProvider(Color(0xFF03261A))
    }

    @Composable
    private fun Content(model: WidgetModel) {
        val context = LocalContext.current
        val wide = LocalSize.current.width >= WIDE.width
        val tint = when (model.vpn) {
            WidgetVpn.CONNECTED -> good
            WidgetVpn.CONNECTING, WidgetVpn.DISCONNECTING -> warn
            WidgetVpn.ERROR -> bad
            WidgetVpn.DISCONNECTED -> muted
        }
        val toggle = model.toggleConnects?.let { connects ->
            actionStartActivity(TileActionActivity.intent(context, if (connects) TileAction.CONNECT else TileAction.DISCONNECT))
        }
        val openApp = actionStartActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(surface)
                .cornerRadius(18.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                // Small widget: the whole widget is the switch. Wide: the body opens the app.
                .clickable(if (!wide && toggle != null) toggle else openApp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_vpn_tile),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = GlanceModifier.size(26.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = context.getString(stateLabel(model.vpn)),
                    style = TextStyle(color = onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    text = if (wide && model.host != null) model.host else context.getString(R.string.widget_title),
                    style = TextStyle(color = muted, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            if (wide) {
                Bell(model.unread, actionStartActivity(PushOpenActivity.inboxIntent(context)))
                if (toggle != null) {
                    Spacer(GlanceModifier.width(8.dp))
                    Box(
                        modifier = GlanceModifier
                            .background(if (model.toggleConnects == true) good else buttonBg)
                            .cornerRadius(14.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .clickable(toggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = context.getString(if (model.toggleConnects == true) R.string.widget_connect else R.string.widget_disconnect),
                            style = TextStyle(
                                color = if (model.toggleConnects == true) badgeFg else onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Bell(unread: Int, open: Action) {
        val context = LocalContext.current
        Box(
            modifier = GlanceModifier.size(40.dp).cornerRadius(20.dp).clickable(open),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_bell),
                contentDescription = if (unread > 0) context.getString(R.string.push_inbox_open, unread)
                else context.getString(R.string.push_inbox_open_none),
                colorFilter = ColorFilter.tint(muted),
                modifier = GlanceModifier.size(22.dp),
            )
            if (unread > 0) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = GlanceModifier.background(badgeBg).cornerRadius(8.dp).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (unread > 99) "99+" else unread.toString(),
                            style = TextStyle(color = badgeFg, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        }
    }

    private fun stateLabel(vpn: WidgetVpn): Int = when (vpn) {
        WidgetVpn.CONNECTED -> R.string.widget_connected
        WidgetVpn.CONNECTING -> R.string.widget_connecting
        WidgetVpn.DISCONNECTING -> R.string.widget_disconnecting
        WidgetVpn.ERROR -> R.string.widget_error
        WidgetVpn.DISCONNECTED -> R.string.widget_disconnected
    }
}

/** Registers [VpnWidget] with the launcher. */
class VpnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VpnWidget()
}
