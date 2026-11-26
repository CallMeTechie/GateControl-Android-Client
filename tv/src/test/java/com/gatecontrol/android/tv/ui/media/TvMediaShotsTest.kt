package com.gatecontrol.android.tv.ui.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.media.MediaDetail
import com.gatecontrol.android.media.MediaLink
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.tv.media.TvDetailUi
import com.gatecontrol.android.tv.media.TvMediaUi
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The page "Medien" of GateControl TV with the rows of „Ergänzt“ in German, as the person sees it; with
 * `MEDIA_SHOTS=<dir>` saved there (for a look, not part of CI). The TV has one (dark) theme.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = Application::class, qualifiers = "de-w960dp-h540dp-land-xhdpi")
class TvMediaShotsTest {

    @get:Rule
    val rule = createComposeRule()

    private val source = MediaSource("s1", MediaSourceKind.JELLYFIN, "NAS", "http://192.168.1.20:8096", "u1", "Anna", "tok")
    private val now = System.currentTimeMillis()

    private fun movie(id: String, name: String, year: Int, minutes: Int, added: Long? = null, position: Long = 0) =
        MediaTitle(id, "s1", MediaTitle.Kind.MOVIE, name, year = year, runtimeMs = minutes * 60_000L, positionMs = position, addedAt = added, posterId = id, backdropId = id)

    private fun episode(id: String, series: String, season: Int, n: Int, name: String, minutes: Int, position: Long = 0) =
        MediaTitle(id, "s1", MediaTitle.Kind.EPISODE, name, seriesId = "x$id", seriesName = series, season = season, episode = n, runtimeMs = minutes * 60_000L, positionMs = position, posterId = id, backdropId = id)

    private fun series(id: String, name: String, seasons: Int? = null, newCount: Int? = null) =
        MediaTitle(id, "s1", MediaTitle.Kind.SERIES, name, childCount = seasons, newCount = newCount, posterId = id)

    private val sternwarte = movie("m9", "Sternwarte 9", 2026, 112, added = now - 86_400_000)

    private val ui = TvMediaUi(
        sources = listOf(source),
        source = source,
        resume = listOf(movie("m1", "Kobaltnacht", 2025, 108, position = 2_530_000), episode("e24", "Station Eisvogel", 2, 4, "Der Leuchtturm", 45, position = 1_620_000)),
        nextUp = listOf(episode("e16", "Ankerplatz 12", 1, 6, "Die Flaute", 44), episode("e41", "Hofgeflüster", 4, 1, "Neue Staffel", 51), episode("e25", "Station Eisvogel", 2, 5, "Treibeis", 47)),
        nextUpTotal = 3,
        latestMovies = listOf(sternwarte, movie("m4", "Kupferstadt", 2025, 124, added = now - 3 * 86_400_000), movie("m2", "Moorlicht", 2017, 98)),
        latestEpisodes = listOf(series("s3", "Graufeld", newCount = 3), series("s7", "Hofgeflüster", newCount = 1), episode("e25", "Station Eisvogel", 2, 5, "Treibeis", 47)),
        favorites = listOf(series("s12", "Ankerplatz 12", 1), sternwarte),
        favoritesTotal = 2,
        movies = listOf(movie("m5", "Achterwasser", 2019, 101), movie("m1", "Kobaltnacht", 2025, 108), movie("m6", "Der Pegelwart", 2018, 95), movie("m4", "Kupferstadt", 2025, 124), movie("m2", "Moorlicht", 2017, 98)),
        moviesTotal = 412,
        series = listOf(series("s12", "Ankerplatz 12", 1), series("s3", "Graufeld", 3), series("s7", "Hofgeflüster", 4)),
        seriesTotal = 65,
        collections = listOf(MediaTitle("b1", "s1", MediaTitle.Kind.COLLECTION, "Sternwarte-Reihe", childCount = 3, posterId = "b1")),
        collectionsTotal = 1,
    )

    private fun picture(seed: String, wide: Boolean): Bitmap {
        val (w, h) = if (wide) 320 to 180 else 200 to 300
        val tones = listOf(0xFF1F3A5F to 0xFF8C5A3C, 0xFF24453A to 0xFF0E1A14, 0xFF3B2A5A to 0xFF7A4C8C, 0xFF5A4A2A to 0xFF1E1A10, 0xFF2E3B47 to 0xFF59666F)
        val (a, b) = tones[(seed.hashCode() and 0x7fffffff) % tones.size]
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), a.toInt(), b.toInt(), Shader.TileMode.CLAMP) })
            c.drawCircle(w * 0.5f, h * 0.42f, w * 0.18f, Paint().apply { color = 0x22FFFFFF })
        }
    }

    private val key: (MediaTitle, Boolean) -> String = { t, b -> t.id + b }

    private fun pictures(titles: List<MediaTitle>) = titles.flatMap { t -> listOf(key(t, false) to picture(t.id, false), key(t, true) to picture(t.id, true)) }.toMap()

    private fun all() = ui.resume + ui.nextUp + ui.latestMovies + ui.latestEpisodes + ui.favorites + ui.movies + ui.series + ui.collections

    private fun shot(name: String) {
        val dir = System.getenv("MEDIA_SHOTS") ?: return
        val bmp = rule.onNodeWithTag("shot").captureToImage().asAndroidBitmap()
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        GateControlTvTheme {
            Box(Modifier.fillMaxSize().background(GateControlTvTheme.colors.bg).testTag("shot").padding(start = 30.dp, top = 24.dp)) { content() }
        }
    }

    @Test
    fun `the rows in German as on the phone`() {
        rule.setContent {
            Frame { TvMediaPage(TvMediaScreen(ui, link = MediaLink(atHome = true), pictures = pictures(all())), TvMediaActions(pictureKey = key)) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Als Nächstes").assertExists()
        rule.onNodeWithText("Neue Filme").assertExists()
        rule.onNodeWithText("3 neu").assertExists()
        shot("tv-uebersicht-dunkel")
    }

    @Test
    @Config(qualifiers = "de-w960dp-h2000dp-land-xhdpi")
    fun `shot - all rows at once`() {
        if (System.getenv("MEDIA_SHOTS") == null) return
        rule.setContent {
            Frame { TvMediaPage(TvMediaScreen(ui, link = MediaLink(atHome = true), pictures = pictures(all())), TvMediaActions(pictureKey = key)) }
        }
        rule.waitForIdle()
        shot("tv-uebersicht-ganz-dunkel")
    }

    @Test
    fun `a collection's page and the heart`() {
        val set = ui.collections.first()
        val films = listOf(movie("m7", "Sternwarte 7", 2019, 104), movie("m8", "Sternwarte 8", 2022, 110), sternwarte.copy(favorite = true))
        val detail = TvDetailUi(set.copy(backdropId = "b1"), MediaDetail(set, emptyList()), loading = false, items = films)
        rule.setContent {
            Frame { TvMediaPage(TvMediaScreen(ui, detail = detail, pictures = pictures(films + set.copy(backdropId = "b1"))), TvMediaActions(pictureKey = key)) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("♡ Favorit").assertExists()
        shot("tv-sammlung-dunkel")
    }
}
