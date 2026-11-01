package com.gatecontrol.android.ui.drive

import com.gatecontrol.android.drive.DPeople
import com.gatecontrol.android.drive.DPerson
import com.gatecontrol.android.drive.DPersonDetail
import com.gatecontrol.android.drive.DPersonPatched
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DSimilar
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DrivePlacesLogic
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.mail.MailPimRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * "Wer ist das?" one after the other (Drive › Fotos › Personen). In v1.76.1 "Nächste Person" took the first person
 * without a name other than the one on the sheet, from the list the page had loaded before the sheet opened: after
 * saving A came B, after saving B came A again (still without a name in that list), and so on with the same two
 * pictures. Now the round asks `GET people` afresh and never shows anyone twice; at the end "Alle durchgesehen".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveNamingRoundTest {

    private val dispatcher = StandardTestDispatcher()

    /** A small Drive server: the viewer's people, sorted like `GET people` (named first, then by count). */
    private class FakeServer(vararg counts: Int) {
        val people = counts.mapIndexed { i, n -> DPerson(i + 1L, null, n, false, null, null) }.toMutableList()
        val patches = mutableListOf<Pair<Long, String>>()
        var offline = false

        fun list(): DResult<DPeople> = if (offline) {
            DResult.Offline
        } else {
            DResult.Ok(DPeople(people.sortedWith(compareByDescending<DPerson> { it.named }.thenByDescending { it.count }.thenBy { it.id }), null, null))
        }

        fun detail(id: Long): DResult<DPersonDetail> =
            people.firstOrNull { it.id == id }?.let { DResult.Ok(DPersonDetail(it, emptyList(), emptyList(), 0, null, null)) } ?: DResult.Failed("not_found", 404)

        fun patch(id: Long, name: String?, hidden: Boolean?): DResult<DPersonPatched> {
            val p = people.firstOrNull { it.id == id } ?: return DResult.Failed("not_found", 404)
            if (hidden != null) {
                patches += id to "hidden=$hidden"
                people[people.indexOf(p)] = p.copy(hidden = hidden)
                return DResult.Ok(DPersonPatched(people.first { it.id == id }, null))
            }
            patches += id to name.orEmpty()
            val other = people.firstOrNull { it.id != id && it.named && it.name.equals(name, ignoreCase = true) }
            if (other != null) {
                // the name is taken: this person goes into the other one
                people.remove(p)
                val into = other.copy(count = other.count + p.count)
                people[people.indexOf(other)] = into
                return DResult.Ok(DPersonPatched(into, other.id))
            }
            people[people.indexOf(p)] = p.copy(name = name?.takeIf { it.isNotBlank() })
            return DResult.Ok(DPersonPatched(people.first { it.id == id }, null))
        }
    }

    private fun api(server: FakeServer) = mockk<DriveApi>().also { api ->
        coEvery { api.people() } answers { server.list() }
        coEvery { api.person(any()) } answers { server.detail(firstArg()) }
        coEvery { api.similarPeople(any()) } returns DResult.Ok(emptyList<DSimilar>())
        coEvery { api.patchPerson(any(), any(), any(), any(), any()) } answers {
            server.patch(firstArg(), secondArg(), thirdArg())
        }
    }

    private fun viewModel(server: FakeServer): DriveNameViewModel {
        val familie = mockk<FamilieRepository>().also { coEvery { it.cachedProfile() } returns null }
        val mail = mockk<MailPimRepository>().also { every { it.cards } returns MutableStateFlow(emptyList()) }
        return DriveNameViewModel(api(server), familie, mail)
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** The person on the sheet, once it has loaded. */
    private fun TestScope.shown(vm: DriveNameViewModel): Long? {
        advanceUntilIdle()
        val s = vm.state.value
        return if (s.finished) null else s.person?.person?.id.also { assertEquals(s.id, it) }
    }

    @Test
    fun `the old rule on the list of the page alternates between the same two people`() {
        // so wählte v1.76.1 "Nächste Person": groups(list of the page).unnamed.firstOrNull { it.id != id }
        val page = FakeServer(9, 8, 7, 6, 5).people.toList()
        var id = 1L
        val order = mutableListOf(id)
        repeat(5) {
            id = DrivePlacesLogic.groups(page).unnamed.first { it.id != id }.id
            order += id
        }
        assertEquals(listOf(1L, 2L, 1L, 2L, 1L, 2L), order)
    }

    @Test
    fun `five people named, skipped and hidden each come once, then the round is through`() = runTest(dispatcher) {
        val server = FakeServer(9, 8, 7, 6, 5)
        val vm = viewModel(server)
        val order = mutableListOf<Long>()

        vm.start(1L)
        order += shown(vm)!!
        vm.save(order.last(), "Mama", null)
        advanceUntilIdle()
        assertEquals("save", vm.state.value.done?.what)
        vm.next()

        order += shown(vm)!!
        vm.next() // Überspringen

        order += shown(vm)!!
        vm.hide(order.last())
        advanceUntilIdle()
        assertEquals("hide", vm.state.value.done?.what)
        vm.next()

        order += shown(vm)!!
        vm.save(order.last(), "Papa", null)
        advanceUntilIdle()
        vm.next()

        order += shown(vm)!!
        vm.next() // Überspringen

        assertNull(shown(vm))
        assertTrue(vm.state.value.finished)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), order)
        // the names reached the server (PATCH people/<id>), the hidden one too
        assertEquals(listOf(1L to "Mama", 3L to "hidden=true", 4L to "Papa"), server.patches)
        assertEquals("Mama", server.people.first { it.id == 1L }.name)
        // skipped people are still there without a name, for the next round
        assertEquals(listOf(2L, 5L), DrivePlacesLogic.groups(server.people).unnamed.map { it.id })
    }

    @Test
    fun `a name that is taken merges, and the round goes on with someone new`() = runTest(dispatcher) {
        val server = FakeServer(9, 8, 7)
        val vm = viewModel(server)

        vm.start(1L)
        assertEquals(1L, shown(vm))
        vm.save(1L, "Tim", null)
        advanceUntilIdle()
        vm.next()
        assertEquals(2L, shown(vm))
        vm.save(2L, "tim", null) // same name: 2 goes into 1
        advanceUntilIdle()
        assertEquals(1L, vm.state.value.done?.into)
        vm.next()
        assertEquals(3L, shown(vm))
        vm.next()
        assertNull(shown(vm))
        assertEquals(listOf(1L, 3L), server.people.map { it.id })
    }

    @Test
    fun `without an answer from the server the round still does not come back`() = runTest(dispatcher) {
        val server = FakeServer(9, 8, 7)
        val vm = viewModel(server)

        vm.start(1L)
        assertEquals(1L, shown(vm))
        server.offline = true // the list stays as it was when the sheet opened
        vm.next()
        assertEquals(2L, shown(vm))
        vm.next()
        assertEquals(3L, shown(vm))
        vm.next()
        assertNull(shown(vm))
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a new round starts again from the person tapped`() = runTest(dispatcher) {
        val server = FakeServer(9, 8)
        val vm = viewModel(server)

        vm.start(1L)
        shown(vm)
        vm.next()
        assertEquals(2L, shown(vm))
        vm.next()
        assertNull(shown(vm))

        vm.start(2L)
        assertEquals(2L, shown(vm))
        vm.next()
        assertEquals(1L, shown(vm))
    }
}
