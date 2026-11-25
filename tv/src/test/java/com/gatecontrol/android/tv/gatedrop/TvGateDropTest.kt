package com.gatecontrol.android.tv.gatedrop

import android.app.Application
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.gatedrop.GATEDROP_PLUGIN
import com.gatecontrol.android.gatedrop.GateDropApi
import com.gatecontrol.android.gatedrop.GdDevice
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.gatedrop.GdItem
import com.gatecontrol.android.gatedrop.GdOwner
import com.gatecontrol.android.gatedrop.GdPerson
import com.gatecontrol.android.gatedrop.GdRecipients
import com.gatecontrol.android.gatedrop.GdResult
import com.gatecontrol.android.gatedrop.GdSendItem
import com.gatecontrol.android.gatedrop.GdSendResult
import com.gatecontrol.android.gatedrop.GdTargets
import com.gatecontrol.android.notify.model.PushMessage
import com.gatecontrol.android.scan.MediaScan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.File

/** GateDrop on the TV against a mocked plugin API: receiving into the TV's storage, the direct offer, sending on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvGateDropTest {

    private val api = mockk<GateDropApi>(relaxed = true)
    private val drive = mockk<DriveApi>(relaxed = true)
    private lateinit var context: Application

    private val sendId = "0123456789abcdef01234567"
    private val photo = "photo-bytes".toByteArray()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        coEvery { api.download("m1") } answers { Response.success(photo.toResponseBody("image/jpeg".toMediaType())) }
        coEvery { api.opened(any()) } returns GdResult.Ok(Unit)
    }

    private val cast = mockk<com.gatecontrol.android.tv.cast.TvCast>(relaxed = true)

    private fun drop() = TvGateDrop(context, api, drive) { cast }

    private fun send(items: List<GdItem>, text: String? = null) = GdEntry(sendId, at = 1_000L, from = "Mila", text = text, items = items)

    private val image = GdItem("m1", "IMG_2041.jpg", "image/jpeg", photo.size.toLong())
    private val blocked = GdItem("m2", "virus.exe", "application/octet-stream", 10, scan = MediaScan(MediaScan.INFECTED, blocked = true))

    @Test
    fun `a send comes onto the TV, its receipt goes to the server, it stays after a restart`() = runBlocking {
        val gd = drop()
        val kept = gd.accept(send(listOf(image, blocked)))!!

        assertEquals("Mila", kept.from)
        assertNotNull(kept.files[0].path)
        assertNull("blocked by the virus scan: not fetched", kept.files[1].path)
        assertEquals("photo-bytes", gd.file(kept, 0)!!.readText())
        assertNull(gd.file(kept, 1))
        assertFalse(kept.seen)
        assertNull("no transfer left", gd.progress.value)
        assertNull(gd.receiving.value)
        coVerify(exactly = 1) { api.opened(sendId) }
        coVerify(exactly = 0) { api.download("m2") }

        // a new process reads what the TV keeps
        val again = drop()
        assertEquals(listOf(sendId), again.entries.value.map { it.sendId })
        assertEquals("photo-bytes", again.file(again.entries.value[0], 0)!!.readText())
    }

    @Test
    fun `nothing fetchable and no text - nothing kept, no receipt`() = runBlocking {
        val gd = drop()
        assertNull(gd.accept(send(listOf(blocked))))
        assertTrue(gd.entries.value.isEmpty())
        coVerify(exactly = 0) { api.opened(any()) }
    }

    @Test
    fun `a message without files is kept`() = runBlocking {
        val gd = drop()
        val kept = gd.accept(send(emptyList(), text = "Essen ist fertig"))!!
        assertEquals("Essen ist fertig", kept.text)
        assertEquals(TvDropKind.TEXT, TvDropLogic.summary(kept).kind)
    }

    @Test
    fun `seen and delete on the TV`() = runBlocking {
        val gd = drop()
        val kept = gd.accept(send(listOf(image)))!!
        val file = gd.file(kept, 0)!!
        gd.seen(sendId)
        assertTrue(gd.entries.value.single().seen)
        gd.delete(sendId)
        assertTrue(gd.entries.value.isEmpty())
        // the files go in the background
        repeat(50) { if (file.exists()) Thread.sleep(20) }
        assertFalse(file.exists())
    }

    private fun push(data: String, kind: String? = PushMessage.KIND_DATA, topic: String = "plugin:$GATEDROP_PLUGIN:send", body: String = "") = PushMessage(
        seq = 1, id = 1, eventId = "e1", topic = topic, priority = "normal", title = "", body = body, createdAt = null, expiresAt = null,
        collapseKey = null, silent = true, route = null, actions = emptyList(), dataJson = data, kind = kind,
    )

    @Test
    fun `a direct offer is answered at once with unsupported so the server delivers`() {
        val gd = drop()
        val transfer = "fedcba9876543210fedcba98"
        assertTrue(gd.consume(push("""{"gatedrop":{"transfer":"$transfer","mode":"pull"}}""")))
        coVerify(timeout = 2_000) { api.transferResult(transfer, false, null, TvGateDrop.DIRECT_UNSUPPORTED) }
        assertFalse("not GateDrop's", gd.consume(push("""{"gatedrop":{"transfer":"$transfer"}}""", topic = "plugin:other:x")))
    }

    @Test
    fun `a device message of the phone goes to the cast, never asked about`() {
        val gd = drop()
        // the visible push of the plugin: its body is the start of the text
        assertTrue(gd.consume(push("""{"gatedrop":{"send":"$sendId"}}""", kind = null, topic = "plugin:$GATEDROP_PLUGIN:received", body = "gcmsg/1 {\"v\":1,\"type\":\"cast.play\"…")))
        io.mockk.verify { cast.onPush(sendId) }
        assertNull(gd.offer.value)
        // found in pending: handed over once, never on the page
        val message = send(emptyList(), text = "gcmsg/1 {\"v\":1}")
        coEvery { api.pending() } returns GdResult.Ok(listOf(message))
        gd.poll()
        io.mockk.verify(timeout = 2_000) { cast.onPending(message) }
        assertNull(gd.offer.value)
        assertTrue(gd.waiting.value.isEmpty())
    }

    @Test
    fun `a delivery push asks about the send`() {
        coEvery { api.sendDetail(sendId) } returns GdResult.Ok(send(listOf(image)))
        val gd = drop()
        gd.onNew(push("""{"gatedrop":{"send":"$sendId"}}""", kind = null))
        repeat(100) { if (gd.offer.value == null) Thread.sleep(20) }
        assertEquals(sendId, gd.offer.value?.entry?.id)
        // "Später": it waits on the page
        gd.later()
        assertNull(gd.offer.value)
        assertEquals(listOf(sendId), gd.waiting.value.map { it.id })
        // a poll does not ask about it again
        coEvery { api.pending() } returns GdResult.Ok(listOf(send(listOf(image))))
        gd.poll()
        Thread.sleep(200)
        assertNull(gd.offer.value)
    }

    @Test
    fun `a received file goes on to a device of the person`() = runBlocking {
        val uploaded = slot<File>()
        coEvery { api.upload(capture(uploaded), "image/jpeg", any()) } returns GdResult.Ok("up-1")
        val to = slot<GdRecipients>()
        val items = slot<List<GdSendItem>>()
        coEvery { api.send(capture(items), null, null, null, capture(to)) } returns GdResult.Ok(GdSendResult("s2"))
        val gd = drop()
        gd.accept(send(listOf(image)))

        val r = gd.sendReceived(sendId, 0, TvDropTarget("43", "Büro-PC", device = true, platform = "windows", online = true))

        assertEquals(TvDropSend.Done("Büro-PC"), r)
        assertEquals(listOf("43"), to.captured.devices)
        assertTrue(to.captured.people.isEmpty())
        assertEquals("up-1", items.captured.single().mediaId)
        assertEquals("IMG_2041.jpg", items.captured.single().name)
        assertEquals("photo-bytes", uploaded.captured.readText())
    }

    @Test
    fun `to a person, and what fails`() = runBlocking {
        coEvery { api.upload(any(), any(), any()) } returns GdResult.Ok("up-1")
        val to = slot<GdRecipients>()
        coEvery { api.send(any(), null, null, null, capture(to)) } returns GdResult.Failed("forbidden", 403)
        val gd = drop()
        gd.accept(send(listOf(image)))

        assertEquals(TvDropSend.Failed("forbidden"), gd.sendReceived(sendId, 0, TvDropTarget("7", "Anna", device = false, platform = null, online = false)))
        assertEquals(listOf("7"), to.captured.people)
        assertEquals(TvDropSend.Failed("gone"), gd.sendReceived("other", 0, TvDropTarget("7", "Anna", false, null, false)))

        val folder = DNode(id = 2, areaId = "fam", parentId = 1, name = "Urlaub", isDir = true, size = 0, mime = null, mediaId = null, rev = 1, mtime = null, updatedAt = null, updatedBy = null)
        assertEquals(TvDropSend.Failed("not_allowed"), gd.sendDrive(folder, TvDropTarget("43", "Büro-PC", true, "windows", true)))
    }

    @Test
    fun `targets, and GateDrop missing on the server`() = runBlocking {
        coEvery { api.targets() } returns GdResult.Ok(GdTargets(people = listOf(GdPerson("7", "Anna"))))
        val gd = drop()
        assertEquals(listOf("Anna"), gd.targets()!!.map { it.name })
        assertEquals(true, gd.available.value)

        coEvery { api.targets() } returns GdResult.Failed("http_404", 404)
        assertNull(gd.targets())
        assertEquals(false, gd.available.value)
    }

    @Test
    fun `a draft goes as text to an own device only`() = runBlocking {
        coEvery { api.targets() } returns GdResult.Ok(
            GdTargets(
                people = listOf(GdPerson("7", "Anna")),
                devices = listOf(GdDevice("d1", "Laptop", "windows", online = false), GdDevice("d2", "Pixel", "android", online = true)),
                peopleDevices = listOf(GdDevice("d9", "Tablet", "android", online = true, owner = GdOwner("u3", "Jonas"))),
            ),
        )
        val text = slot<String>()
        val to = slot<GdRecipients>()
        coEvery { api.send(emptyList(), capture(text), null, null, capture(to)) } returns GdResult.Ok(GdSendResult("s3"))
        val gd = drop()

        assertEquals(listOf("Pixel", "Laptop"), gd.ownDevices()!!.map { it.name })
        val pixel = gd.ownDevices()!!.first()
        assertEquals(TvDropSend.Done("Pixel"), gd.sendText("Bin gleich da.\n\ngatecontrol://chat/12", pixel))
        assertEquals("Bin gleich da.\n\ngatecontrol://chat/12", text.captured)
        assertEquals(listOf("d2"), to.captured.devices)
        assertTrue(to.captured.people.isEmpty())

        assertEquals(TvDropSend.Failed("not_allowed"), gd.sendText("Hallo", TvDropTarget("7", "Anna", false, null, false)))
        assertEquals(TvDropSend.Failed("empty"), gd.sendText("   ", pixel))
    }
}
