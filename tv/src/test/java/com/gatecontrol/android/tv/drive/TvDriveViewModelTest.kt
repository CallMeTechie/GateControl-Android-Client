package com.gatecontrol.android.tv.drive

import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DCrumb
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvDriveViewModelTest {

    private val api = mockk<DriveApi>()
    private val media = mockk<TvDriveMedia>(relaxed = true)
    private val selection = TvMediaSelection()

    private val family = DArea("fam", "family", "Familie", "write", "member", rootId = 100, archived = false)

    private fun node(id: Long, name: String, mime: String? = null, dir: Boolean = false, parent: Long = 100) = DNode(
        id = id, areaId = "fam", parentId = parent, name = name, isDir = dir, size = 1, mime = mime, mediaId = if (dir) null else "media$id-abcdef",
        rev = 1, mtime = null, updatedAt = null, updatedBy = null,
    )

    private fun me(licence: Boolean = true, seat: String? = "family") = DMe(
        userId = 1, name = "Anna", seat = seat, licenceValid = licence, tier = null, areas = listOf(family), latest = 0,
        trashDays = null, maxBytes = null, people = emptyMap(),
    )

    private fun folder(id: Long, items: List<DNode>, next: Int? = null) =
        DResult.Ok(DFolder(node(id, "f$id", dir = true), listOf(DCrumb(id, "f$id")), "write", items, items.size, next))

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `areas, into a folder, back up and out`() {
        coEvery { api.me() } returns DResult.Ok(me())
        val sub = node(200, "Fotos", dir = true)
        coEvery { api.folder(100, any(), any(), any(), any()) } returns folder(100, listOf(sub, node(201, "a.jpg", "image/jpeg")))
        coEvery { api.folder(200, any(), any(), any(), any()) } returns folder(200, listOf(node(300, "b.jpg", "image/jpeg", parent = 200)))
        val vm = TvDriveViewModel(api, media, selection)
        vm.start()
        assertEquals(listOf("fam"), vm.ui.value.areas.map { it.id })
        vm.openArea(family)
        assertEquals(listOf(200L, 201L), vm.ui.value.items.map { it.id })
        assertEquals(TvDriveOpen.FOLDER, vm.press(sub))
        assertEquals(listOf("Familie", "Fotos"), vm.ui.value.stack.path())
        assertEquals(listOf(300L), vm.ui.value.items.map { it.id })
        assertTrue(vm.back())
        assertEquals(listOf("Familie"), vm.ui.value.stack.path())
        assertEquals(listOf(200L, 201L), vm.ui.value.items.map { it.id })
        assertTrue(vm.back())
        assertTrue(vm.ui.value.stack.atAreas)
        // at the areas Zurück belongs to the rail
        assertFalse(vm.back())
    }

    @Test
    fun `a picture hands the folder's pictures to the viewer`() {
        coEvery { api.me() } returns DResult.Ok(me())
        val a = node(1, "a.jpg", "image/jpeg")
        val v = node(2, "v.mp4", "video/mp4")
        val b = node(3, "b.jpg", "image/jpeg")
        coEvery { api.folder(100, any(), any(), any(), any()) } returns folder(100, listOf(a, v, b))
        val vm = TvDriveViewModel(api, media, selection)
        vm.openArea(family)
        assertEquals(TvDriveOpen.PHOTO, vm.press(b))
        assertEquals(listOf(1L, 3L), selection.items.map { it.id })
        assertEquals(1, selection.at)
        assertEquals(TvDriveOpen.VIDEO, vm.press(v))
        assertEquals(listOf(2L), selection.items.map { it.id })
    }

    @Test
    fun `the next page when the focus nears the end`() {
        coEvery { api.folder(100, any(), any(), 0, any()) } returns folder(100, listOf(node(1, "a.jpg", "image/jpeg")), next = 1)
        coEvery { api.folder(100, any(), any(), 1, any()) } returns folder(100, listOf(node(2, "b.jpg", "image/jpeg")))
        val vm = TvDriveViewModel(api, media, selection)
        vm.openArea(family)
        vm.more()
        assertEquals(listOf(1L, 2L), vm.ui.value.items.map { it.id })
        vm.more()
        coVerify(exactly = 2) { api.folder(100, any(), any(), any(), any()) }
    }

    @Test
    fun `problems say why`() {
        coEvery { api.me() } returns DResult.Ok(me(licence = false))
        val vm = TvDriveViewModel(api, media, selection)
        vm.start()
        assertEquals(TvDriveProblem.NOT_LICENSED, vm.ui.value.problem)
        coEvery { api.me() } returns DResult.Offline
        vm.reload()
        assertEquals(TvDriveProblem.OFFLINE, vm.ui.value.problem)
        assertEquals(TvDriveProblem.NOT_ON, TvDriveViewModel.problemOf(DResult.Failed("no_seat", 403)))
        assertEquals(TvDriveProblem.FAILED, TvDriveViewModel.problemOf(DResult.Failed("boom", 500)))
    }
}
