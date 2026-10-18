package com.gatecontrol.android.update

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

class ReleaseNotesRepositoryTest {
    private val prefs = mockk<UpdatePrefs>(relaxed = true) {
        every { notes(any(), any()) } returns null
    }
    private val md = "## [1.46.0] - 2026-10-19\n\n### Features\n- status and stories (#94)\n"

    @Test
    fun `fetched notes are parsed and cached per step`() = runTest {
        var calls = 0
        val repo = ReleaseNotesRepository(prefs) { calls++; md }
        repo.load(14502, 14600)
        val expected = listOf(ReleaseNote("Status and stories", ReleaseNote.Kind.Feature))
        assertEquals(ReleaseNotesState.Loaded(expected), repo.stateFor(14502, 14600))
        verify { prefs.setNotes(14502, 14600, expected) }
        repo.load(14502, 14600)
        assertEquals(1, calls)
    }

    @Test
    fun `cached notes need no request`() = runTest {
        val cached = listOf(ReleaseNote("Cached", ReleaseNote.Kind.Fix))
        every { prefs.notes(14502, 14600) } returns cached
        val repo = ReleaseNotesRepository(prefs) { error("no request expected") }
        repo.load(14502, 14600)
        assertEquals(ReleaseNotesState.Loaded(cached), repo.stateFor(14502, 14600))
    }

    @Test
    fun `offline or empty means unavailable, not retried right away`() = runTest {
        var calls = 0
        val repo = ReleaseNotesRepository(prefs) { calls++; throw IOException("offline") }
        repo.load(14502, 14600)
        assertEquals(ReleaseNotesState.Unavailable, repo.stateFor(14502, 14600))
        repo.load(14502, 14600)
        assertEquals(1, calls)

        val empty = ReleaseNotesRepository(prefs) { "# Changelog\n" }
        empty.load(14502, 14600)
        assertEquals(ReleaseNotesState.Unavailable, empty.stateFor(14502, 14600))
    }
}
