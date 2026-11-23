package com.gatecontrol.android.tv.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gatecontrol.android.tv.frame.TvFrameFeed
import com.gatecontrol.android.tv.frame.TvFrameOrder
import com.gatecontrol.android.tv.frame.TvFrameSettings
import com.gatecontrol.android.tv.frame.TvFrameStatus
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Einstellungen › Fotorahmen: the hints of the states, the server's interval and order, a TV's own overriding them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "de-w960dp-h540dp-land-xhdpi")
class TvFrameOptionsTest {

    @get:Rule
    val rule = createComposeRule()

    private val server = TvFrameFeed(on = true, ready = true, seconds = 15, order = TvFrameOrder.DATE, items = emptyList())

    private fun show(status: TvFrameStatus, frame: TvFrameSettings = TvFrameSettings(), feed: TvFrameFeed? = server): MutableList<TvFrameSettings> {
        val changes = mutableListOf<TvFrameSettings>()
        var current by mutableStateOf(frame)
        rule.setContent {
            GateControlTvTheme {
                Column {
                    TvFrameOptions(current, status, feed, onChange = { changes += it; current = it }, onStart = {}, onRefresh = {})
                }
            }
        }
        return changes
    }

    @Test
    fun `nothing shared says where to share`() {
        show(TvFrameStatus.NoPhotos)
        rule.onNodeWithText(
            "Noch keine Fotos für den Fotorahmen freigegeben – in der Handy- oder Windows-App im Foto-Tab auswählen und „Für Fotorahmen freigeben“.",
        ).assertExists()
    }

    @Test
    fun `the other states have their own hints`() {
        show(TvFrameStatus.Unavailable)
        rule.onNodeWithText("Drive auf dem Server kennt den Fotorahmen", substring = true).assertExists()
    }

    @Test
    fun `shared photos are counted, interval and order as on the server`() {
        show(TvFrameStatus.Photos(12))
        rule.onNodeWithText("12 Fotos freigegeben").assertExists()
        rule.onNodeWithText("Wie am Server (15 Sekunden)").assertExists()
        rule.onNodeWithText("Wie am Server (Nach Datum, neueste zuerst)").assertExists()
    }

    @Test
    fun `the TV's own interval and order override the server's`() {
        show(TvFrameStatus.Photos(3), TvFrameSettings(seconds = 60, order = TvFrameOrder.RANDOM))
        rule.onNodeWithText("1 Minute").assertExists()
        rule.onNodeWithText("Zufällig, jedes Foto einmal pro Runde").assertExists()
        rule.onNodeWithText("Wie am Server", substring = true).assertDoesNotExist()
    }
}
