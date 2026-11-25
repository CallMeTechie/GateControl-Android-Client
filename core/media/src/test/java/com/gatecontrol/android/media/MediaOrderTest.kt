package com.gatecontrol.android.media

import com.gatecontrol.android.media.share.FakeShareFs
import com.gatecontrol.android.media.share.ShareLibrary
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone's grid and search: sorting and filtering by the chips, the merge of Jellyfin and the shares, the search. */
class MediaOrderTest {

    private fun film(id: String, name: String, year: Int? = null, added: Long? = null, played: Boolean = false, genres: List<String> = emptyList(), share: Boolean = false) =
        MediaTitle(id, if (share) "nas" else "jf", MediaTitle.Kind.MOVIE, name, year = year, addedAt = added, played = played, genres = genres, fromShare = share)

    @Test
    fun `sorted A-Z, newest first or by year`() {
        val all = listOf(
            film("a", "Moorlicht", 2017, 3),
            film("b", "Achterwasser", 2019, 1),
            film("c", "The Kobaltnacht", 2025, 2),
            film("d", "Sternwarte 9", null, null),
        )
        assertEquals(listOf("b", "c", "a", "d"), MediaLogic.ordered(all, MediaOrder()).map { it.id })
        assertEquals(listOf("a", "c", "b", "d"), MediaLogic.ordered(all, MediaOrder(MediaOrder.Sort.ADDED)).map { it.id })
        assertEquals(listOf("c", "b", "a", "d"), MediaLogic.ordered(all, MediaOrder(MediaOrder.Sort.YEAR)).map { it.id })
        assertTrue(MediaOrder().plain)
    }

    @Test
    fun `only unwatched, one genre`() {
        val all = listOf(
            film("a", "Moorlicht", played = true, genres = listOf("Drama")),
            film("b", "Achterwasser", genres = listOf("Komödie")),
            film("c", "Kobaltnacht", genres = listOf("drama", "Thriller")),
            MediaTitle("s", "jf", MediaTitle.Kind.SERIES, "Graufeld", unplayed = 0, played = true, genres = listOf("Drama")),
            MediaTitle("t", "jf", MediaTitle.Kind.SERIES, "Station Eisvogel", unplayed = 3, genres = listOf("Drama")),
        )
        assertEquals(listOf("b", "c", "t"), MediaLogic.filtered(all, MediaOrder(unplayed = true)).map { it.id })
        assertEquals(listOf("a", "c", "s", "t"), MediaLogic.filtered(all, MediaOrder(genre = "Drama")).map { it.id })
        assertEquals(listOf("Drama", "Komödie", "Thriller"), MediaLogic.genres(all))
    }

    @Test
    fun `a share's titles wait for the Jellyfin page they belong to`() {
        val order = MediaOrder(MediaOrder.Sort.YEAR)
        val jellyfin = listOf(film("j1", "Sternwarte 9", 2026), film("j2", "Kobaltnacht", 2025))
        val shares = listOf(film("n1", "Graufeld", 2025, share = true), film("n2", "Kupferstadt", 2030, share = true), film("n3", "Moorlicht", 2017, share = true))
        // Jellyfin has more pages: what sorts after its last title waits
        assertEquals(listOf("n2", "j1", "n1", "j2"), MediaLogic.merged(jellyfin, false, shares, order).map { it.id })
        assertEquals(listOf("n2", "j1", "n1", "j2", "n3"), MediaLogic.merged(jellyfin, true, shares, order).map { it.id })
        assertEquals(emptyList<String>(), MediaLogic.merged(emptyList(), false, shares, order).map { it.id })
        assertEquals(listOf("n2", "n1", "n3"), MediaLogic.merged(emptyList(), true, shares, order).map { it.id })
    }

    @Test
    fun `search by name, the ones that start with it first`() {
        val all = listOf(
            film("a", "Die Kobaltnacht"),
            film("b", "Kobalt"),
            film("c", "Moorlicht"),
            MediaTitle("e", "jf", MediaTitle.Kind.EPISODE, "Flut", seriesName = "Kobaltküste"),
        )
        assertEquals(listOf("b", "a", "e"), MediaLogic.search(all, "kobalt").map { it.id })
        assertEquals(emptyList<String>(), MediaLogic.search(all, "  ").map { it.id })
        assertEquals(listOf("c"), MediaLogic.search(all, "moor-licht").map { it.id })
    }

    @Test
    fun `a share lists, searches and knows its genres from its index`() = runTest {
        val b = ByteArray(4)
        val fs = FakeShareFs(
            mapOf(
                "Filme/Kobaltnacht (2025)/Kobaltnacht (2025).mkv" to b,
                "Filme/Achterwasser (2019).mp4" to b,
                "Filme/Moorlicht (2017).mkv" to b,
            ),
        )
        val source = MediaSource("nas", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme")
        val lib = ShareLibrary(source, fs, MapStore())
        val byYear = lib.list(movies = true, start = 0, limit = 2, order = MediaOrder(MediaOrder.Sort.YEAR)).valueOrNull()!!
        assertEquals(listOf("Kobaltnacht", "Achterwasser"), byYear.items.map { it.name })
        assertEquals(3, byYear.total)
        assertEquals(2, byYear.next)
        assertEquals(listOf("Moorlicht"), lib.search("moor", 10).valueOrNull()!!.map { it.name })
        assertEquals(emptyList<String>(), lib.genres(movies = true).valueOrNull())
    }

    private class MapStore : ShareProgressStore {
        val map = HashMap<String, ShareProgress>()
        override fun progress(key: String) = map[key]
        override fun all(prefix: String) = map.filterKeys { it.startsWith(prefix) }
        override fun setProgress(key: String, progress: ShareProgress) {
            map[key] = progress
        }
    }
}
