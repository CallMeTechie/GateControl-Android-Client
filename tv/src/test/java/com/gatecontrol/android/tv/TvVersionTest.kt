package com.gatecontrol.android.tv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** GateControl TV has no version of its own: release.yml bumps the phone app's, both APKs carry it. */
class TvVersionTest {

    private val phoneBuild = File(System.getProperty("gc.phoneBuildFile")!!).readText()

    @Test
    fun versionNameIsThePhoneApps() {
        val phone = Regex("""versionName = "([0-9.]+)"""").find(phoneBuild)!!.groupValues[1]
        assertEquals(phone, BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeIsThePhoneApps() {
        val phone = Regex("""versionCode = ([0-9]+)""").find(phoneBuild)!!.groupValues[1].toInt()
        assertEquals(phone, BuildConfig.VERSION_CODE)
    }

    @Test
    fun ownApplicationId() {
        assertEquals("com.gatecontrol.client.tv", BuildConfig.APPLICATION_ID)
    }
}
