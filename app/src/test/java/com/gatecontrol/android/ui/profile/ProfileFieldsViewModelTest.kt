package com.gatecontrol.android.ui.profile

import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.profile.ProfileDetails
import com.gatecontrol.android.profile.ProfileDetailsJson
import com.gatecontrol.android.profile.ProfileEdit
import com.gatecontrol.android.profile.ProfileExport
import com.gatecontrol.android.profile.ProfileRepository
import com.gatecontrol.android.profile.ProfileState
import com.gatecontrol.android.profile.ProfileSupport
import com.gatecontrol.android.profile.MyProfile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * The fields on the page „Profil“: a number is checked as the chat by number does before anything is sent; after the
 * first number the question whether others may recognise the person by it (once, only when never asked); an edited
 * number sends only what changed; a refusal stays in the sheet; „Meine Daten“ waits for the save dialog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileFieldsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ProfileFieldsViewModel>()

    @BeforeEach fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun details(phones: String = "[]", answered: Boolean = false, family: Boolean = true): ProfileDetails = ProfileDetailsJson.parse(
        JsonParser.parseString(
            """{"userId":7,"username":"jenny","displayName":"Jenny Backes","family":$family,"phones":$phones,
            "recognition":{"byPhone":$answered,"phoneAnswered":$answered},"features":{"phones":true,"emailConfirm":true,"recognition":true}}""",
        ).asJsonObject,
    )!!

    private val one = """[{"id":11,"kind":"mobile","number":"+4915100000000","visibility":"none","useForRecognition":true,"state":"unconfirmed"}]"""

    private val me = MyProfile(7, "Jenny Backes", "JB", "pink", "#DB2777", null, emptyList())
    private val state = MutableStateFlow(ProfileState(me, ProfileSupport.SUPPORTED, details()))
    private val repo = mockk<ProfileRepository> { every { state } returns this@ProfileFieldsViewModelTest.state }

    private fun vm() = ProfileFieldsViewModel(repo).also { models += it }

    @Test
    fun `the first number - checked, sent, then the question`() = runTest(dispatcher) {
        coEvery { repo.addPhone(any(), any(), any(), any()) } returns ProfileEdit.Done(details(one))
        val m = vm()
        m.addPhone()
        m.updateDraft { it.copy(input = "0151 0000") }
        m.savePhone()
        // not a number yet: nothing goes out
        assertEquals("number", m.refusal.value?.field)
        coVerify(exactly = 0) { repo.addPhone(any(), any(), any(), any()) }
        m.updateDraft { it.copy(input = "+49 151 00000000") }
        assertNull(m.refusal.value)
        m.savePhone()
        advanceUntilIdle()
        coVerify { repo.addPhone("mobile", "+4915100000000", "none", true) }
        assertNull(m.phone.value)
        assertTrue(m.consent.value)
        // the answer goes to the server, also „Nein“
        val body = slot<JsonObject>()
        coEvery { repo.updateFields(capture(body)) } returns ProfileEdit.Done(details(one, answered = true))
        m.answerConsent(false)
        advanceUntilIdle()
        assertFalse(m.consent.value)
        assertEquals("""{"recognition":{"byPhone":false}}""", body.captured.toString())
    }

    @Test
    fun `no question when asked before or for a second number`() = runTest(dispatcher) {
        state.value = state.value.copy(details = details(answered = true))
        coEvery { repo.addPhone(any(), any(), any(), any()) } returns ProfileEdit.Done(details(one, answered = true))
        val m = vm()
        m.addPhone()
        m.updateDraft { it.copy(input = "+4915100000000") }
        m.savePhone()
        advanceUntilIdle()
        assertFalse(m.consent.value)

        state.value = state.value.copy(details = details(one))
        coEvery { repo.addPhone(any(), any(), any(), any()) } returns ProfileEdit.Done(details(one))
        val n = vm()
        n.addPhone()
        n.updateDraft { it.copy(input = "+4930000000", kind = "work") }
        n.savePhone()
        advanceUntilIdle()
        assertFalse(n.consent.value)
    }

    @Test
    fun `an edited number sends only what changed, a refusal stays in the sheet`() = runTest(dispatcher) {
        state.value = state.value.copy(details = details(one))
        coEvery { repo.updatePhone(any(), any(), any(), any(), any()) } returns ProfileEdit.Refused("RATE_LIMITED", null, "Zu viele Änderungen.")
        val m = vm()
        m.editPhone(11)
        assertEquals("+49 151 00000000", m.phone.value?.input)
        assertEquals("unconfirmed", m.phone.value?.state)
        m.updateDraft { it.copy(visibility = "family") }
        m.savePhone()
        advanceUntilIdle()
        coVerify { repo.updatePhone(11, null, null, "family", null) }
        assertEquals("Zu viele Änderungen.", m.refusal.value?.message)
        assertEquals("family", m.phone.value?.visibility)
        // nothing changed: the sheet closes without a question to the server
        m.closePhone()
        m.editPhone(11)
        m.savePhone()
        assertNull(m.phone.value)
        coVerify(exactly = 1) { repo.updatePhone(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `removing a number`() = runTest(dispatcher) {
        state.value = state.value.copy(details = details(one))
        coEvery { repo.removePhone(11) } returns ProfileEdit.Done(details())
        val m = vm()
        val messages = mutableListOf<Int>()
        val collector = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { m.messages.collect { messages += it } }
        m.editPhone(11)
        m.removePhone()
        advanceUntilIdle()
        assertNull(m.phone.value)
        assertEquals(listOf(R.string.profile_phone_removed), messages)
        collector.cancel()
    }

    @Test
    fun `fields - names share one visibility, an empty display name is not sent`() = runTest(dispatcher) {
        val body = slot<JsonObject>()
        coEvery { repo.updateFields(capture(body)) } returns ProfileEdit.Done(details())
        val m = vm()
        m.open(ProfileEditor.NAMES)
        m.saveNames("Jenny", "", "family")
        advanceUntilIdle()
        assertEquals("""{"firstName":"Jenny","lastName":null,"visibility":{"firstName":"family","lastName":"family"}}""", body.captured.toString())
        assertNull(m.editor.value)
        m.open(ProfileEditor.DISPLAY_NAME)
        m.saveDisplayName("  ")
        assertEquals("displayName", m.refusal.value?.field)
        coVerify(exactly = 1) { repo.updateFields(any()) }
    }

    @Test
    fun `locked fields open no dialog`() = runTest(dispatcher) {
        val locked = ProfileDetailsJson.parse(
            JsonParser.parseString("""{"userId":7,"username":"j","locked":{"displayName":true,"email":true,"directory":"AD"}}""").asJsonObject,
        )!!
        state.value = state.value.copy(details = locked)
        val m = vm()
        m.open(ProfileEditor.DISPLAY_NAME)
        m.open(ProfileEditor.EMAIL)
        assertNull(m.editor.value)
        m.open(ProfileEditor.ABOUT)
        assertEquals(ProfileEditor.ABOUT, m.editor.value)
    }

    @Test
    fun `a further address opens the code when one was sent`() = runTest(dispatcher) {
        coEvery { repo.addEmail("jenny.privat@example.com") } returns ProfileEdit.Done(details(), codeSent = true, emailId = 3)
        coEvery { repo.confirmEmail(3, "123456") } returns ProfileEdit.Refused("WRONG_CODE", "code", null)
        val m = vm()
        m.open(ProfileEditor.ADD_EMAIL)
        m.addEmail("kein-at")
        coVerify(exactly = 0) { repo.addEmail(any()) }
        m.addEmail(" jenny.privat@example.com ")
        advanceUntilIdle()
        assertEquals(3L, m.emailCode.value)
        m.confirmEmail("12")
        coVerify(exactly = 0) { repo.confirmEmail(any(), any()) }
        m.confirmEmail("123456")
        advanceUntilIdle()
        assertEquals("WRONG_CODE", m.refusal.value?.code)
        assertEquals(3L, m.emailCode.value)
    }

    @Test
    fun `my data waits for the save dialog and then leaves the memory`() = runTest(dispatcher) {
        coEvery { repo.export() } returns ProfileExport.File("gatecontrol-meine-daten-2026-11-15.json", "{}".toByteArray())
        val m = vm()
        m.startExport()
        advanceUntilIdle()
        assertEquals("gatecontrol-meine-daten-2026-11-15.json", m.export.value?.name)
        assertFalse(m.exporting.value)
        m.exportDone(null)
        assertNull(m.export.value)
    }

    @Test
    fun `server codes have a text`() {
        assertEquals(R.string.profile_error_duplicate, ProfileFieldsViewModel.messageOf("DUPLICATE"))
        assertEquals(R.string.profile_error_code_expired, ProfileFieldsViewModel.messageOf("CODE_EXPIRED"))
        assertEquals(R.string.profile_error_offline, ProfileFieldsViewModel.messageOf(ProfileRepository.CODE_OFFLINE))
        assertEquals(R.string.profile_error_failed, ProfileFieldsViewModel.messageOf("SOMETHING"))
    }

    @Test
    fun `a draft never shows its number in a log line`() {
        assertFalse(PhoneDraft(input = "+4915100000000").toString().contains("0000"))
    }
}
