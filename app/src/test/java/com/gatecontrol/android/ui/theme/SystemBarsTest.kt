package com.gatecontrol.android.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bar icons follow the app's design: dark on the light page, light on the dark one, also when switched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemBarsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `dark icons on the light design, light ones on the dark design`() {
        val dark = mutableStateOf(false)
        rule.setContent { GcSystemBars(dark.value) }
        rule.waitForIdle()
        val window = rule.activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        assertTrue(bars.isAppearanceLightStatusBars)
        assertTrue(bars.isAppearanceLightNavigationBars)

        dark.value = true
        rule.waitForIdle()
        assertFalse(bars.isAppearanceLightStatusBars)
        assertFalse(bars.isAppearanceLightNavigationBars)

        dark.value = false
        rule.waitForIdle()
        assertTrue(bars.isAppearanceLightStatusBars)
    }
}
