package com.gatecontrol.android.tv.gatedrop

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The list of received sends on the TV's disk (org.json of Android 9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvDropStoreJsonTest {
    @Test
    fun `written and read back`() {
        val list = listOf(
            TvDropEntry("s1", "Mila", 1_700_000_000_000, files = listOf(TvDropFile("IMG_2041.jpg", "image/jpeg", 4_100_000, "s1/0-IMG_2041.jpg"), TvDropFile("Rezept.pdf", "application/pdf", 90_000, null))),
            TvDropEntry("s2", null, 1_700_000_100_000, text = "Bis gleich", link = "https://example.com/a", seen = true),
        )
        assertEquals(list, TvDropStoreJson.read(TvDropStoreJson.write(list)))
    }

    @Test
    fun `odd entries are left out`() {
        assertEquals(listOf("ok"), TvDropStoreJson.read("""[{"sendId":""},{"sendId":"ok","at":5},42]""").map { it.sendId })
    }
}
