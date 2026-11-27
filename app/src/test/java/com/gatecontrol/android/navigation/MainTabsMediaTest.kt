package com.gatecontrol.android.navigation

import com.gatecontrol.android.familie.PluginAvailability
import com.gatecontrol.android.layout.StartPages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Medien (stage 4b, question 21): a tab once a source is set up, before Drive, and pinnable to Start like the others. */
class MainTabsMediaTest {

    private val drive = PluginAvailability(drive = true)

    @Test
    fun `Medien is a tab only with a source, right before Drive`() {
        assertFalse(MediaRoutes.TAB in MainTabs.routes(network = true, plugins = drive))
        assertEquals(
            listOf(Screen.Vpn.route, MediaRoutes.TAB, Screen.Drive.route, Screen.Network.route),
            MainTabs.routes(network = true, plugins = drive, media = true),
        )
        assertEquals(listOf(Screen.Vpn.route, MediaRoutes.TAB), MainTabs.routes(network = false, plugins = PluginAvailability.NONE, media = true))
        assertTrue(MediaRoutes.TAB in MainTabs.BAR_ROUTES)
    }

    @Test
    fun `Medien pinned to Start leaves the bar and opens as its page`() {
        assertEquals(StartPages.MEDIA, MainTabs.pageOf(MediaRoutes.TAB))
        assertEquals(MediaRoutes.TAB, MainTabs.routeOf(StartPages.MEDIA))
        assertEquals(StartPages.MEDIA, MainTabs.pageFor(MediaRoutes.TAB))
        // a page on top of the tab is no page of Start
        assertEquals(null, MainTabs.pageFor(MediaRoutes.SEARCH))
        val routes = MainTabs.routes(network = false, plugins = drive, media = true)
        assertEquals(listOf(StartPages.MEDIA, StartPages.DRIVE), MainTabs.features(routes))
        assertEquals(listOf(Screen.Vpn.route, Screen.Drive.route), MainTabs.visible(routes, listOf(StartPages.MEDIA)))
        assertTrue(StartPages.isFeature(StartPages.MEDIA))
        assertTrue(StartPages.FEATURES.indexOf(StartPages.MEDIA) < StartPages.FEATURES.indexOf(StartPages.DRIVE))
    }

    @Test
    fun `the routes of the detail page and of adding a source`() {
        val t = com.gatecontrol.android.media.MediaTitle("m1", "jf", com.gatecontrol.android.media.MediaTitle.Kind.MOVIE, "Blaue Stunde & Söhne")
        assertEquals("media/title?source=jf&id=m1&name=Blaue%20Stunde%20%26%20S%C3%B6hne", MediaRoutes.detail(t))
        // „Andere Folge“: a series on the episode's season; a film never carries a season
        val series = com.gatecontrol.android.media.MediaTitle("s1", "jf", com.gatecontrol.android.media.MediaTitle.Kind.SERIES, "Sternenhafen", seasonId = "sea2")
        assertEquals("media/title?source=jf&id=s1&name=Sternenhafen&season=sea2", MediaRoutes.detail(series))
        assertEquals("media/title?source=jf&id=m1&name=Blaue%20Stunde%20%26%20S%C3%B6hne", MediaRoutes.detail(t.copy(seasonId = "x")))
    }
}
