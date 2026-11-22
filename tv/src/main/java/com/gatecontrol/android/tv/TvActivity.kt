package com.gatecontrol.android.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gatecontrol.android.tv.ui.home.TvHome
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme

/** The only screen of GateControl TV in stage 0: the start page with the rail (Fire TV / Android TV launcher). */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GateControlTvTheme {
                TvHome()
            }
        }
    }
}
