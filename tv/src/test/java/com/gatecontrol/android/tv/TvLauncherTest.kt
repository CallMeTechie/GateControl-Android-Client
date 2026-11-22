package com.gatecontrol.android.tv

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Fire TV / Android TV launcher lists the app (leanback entry with a banner), no touchscreen needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvLauncherTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun leanbackLauncherOpensTvActivity() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(context.packageName)
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        assertEquals(listOf(TvActivity::class.java.name), activities.map { it.activityInfo.name })
    }

    @Test
    fun noPhoneLauncherEntry() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentActivities(intent, 0).isEmpty())
    }

    @Test
    fun hasBanner() {
        assertNotEquals(0, context.applicationInfo.banner)
    }

    @Test
    fun touchscreenAndLeanbackAreOptional() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_CONFIGURATIONS)
        val features = info.reqFeatures.orEmpty().associate { it.name to it.flags }
        assertEquals(setOf("android.hardware.touchscreen", "android.software.leanback"), features.keys)
        for ((name, flags) in features) {
            assertFalse(name, flags and android.content.pm.FeatureInfo.FLAG_REQUIRED != 0)
        }
    }
}
