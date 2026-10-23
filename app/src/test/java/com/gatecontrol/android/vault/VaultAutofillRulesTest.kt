package com.gatecontrol.android.vault

import android.text.InputType
import com.gatecontrol.android.vault.autofill.FieldDesc
import com.gatecontrol.android.vault.autofill.VaultAutofillRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Which fields of a login form Vault fills (autofill, mockup A7). */
class VaultAutofillRulesTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    @Test
    fun `native app - input types decide`() {
        val f = listOf(FieldDesc(inputType = text, idEntry = "search"), FieldDesc(inputType = email), FieldDesc(inputType = password))
        assertEquals(1 to listOf(2), VaultAutofillRules.classify(f))
    }

    @Test
    fun `autofill hints win over everything`() {
        val f = listOf(FieldDesc(hints = listOf("username")), FieldDesc(hints = listOf("password")))
        assertEquals(0 to listOf(1), VaultAutofillRules.classify(f))
    }

    @Test
    fun `web page - html type and autocomplete, German field names`() {
        val f = listOf(
            FieldDesc(htmlType = "text", htmlName = "suche"),
            FieldDesc(htmlType = "text", htmlName = "benutzername"),
            FieldDesc(htmlType = "password", htmlAutocomplete = "current-password"),
        )
        assertEquals(1 to listOf(2), VaultAutofillRules.classify(f))
    }

    @Test
    fun `the text field before the password is the user name when nothing else tells`() {
        val f = listOf(FieldDesc(inputType = text, idEntry = "field1"), FieldDesc(inputType = text, idEntry = "field2"), FieldDesc(inputType = password))
        assertEquals(1 to listOf(2), VaultAutofillRules.classify(f))
    }

    @Test
    fun `sign-up form - both password fields, no login form at all - nothing`() {
        val signUp = listOf(FieldDesc(inputType = email), FieldDesc(htmlType = "password", htmlName = "new"), FieldDesc(htmlType = "password", htmlName = "repeat"))
        assertEquals(0 to listOf(1, 2), VaultAutofillRules.classify(signUp))
        val search = listOf(FieldDesc(inputType = text, idEntry = "search_box"))
        assertEquals(null to emptyList<Int>(), VaultAutofillRules.classify(search))
    }

    @Test
    fun `a one-time code field is no password`() {
        val f = listOf(FieldDesc(inputType = text, hint = "SMS-Code"), FieldDesc(inputType = text, idEntry = "otp_code"))
        assertEquals(null to emptyList<Int>(), VaultAutofillRules.classify(f))
    }
}
