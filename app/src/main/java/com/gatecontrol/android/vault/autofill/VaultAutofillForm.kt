package com.gatecontrol.android.vault.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/** What decides whether a field is a user name or a password — plain data, so the rules are unit-tested. */
data class FieldDesc(
    val hints: List<String> = emptyList(),
    val inputType: Int = 0,
    val idEntry: String? = null,
    val hint: String? = null,
    val htmlType: String? = null,
    val htmlAutocomplete: String? = null,
    val htmlName: String? = null,
    val isText: Boolean = true,
)

/**
 * Login forms in another app or a browser page (mockup A7): which field takes the user name, which the password.
 * Android's autofill hints first, then the input type, then HTML attributes, then the field's names (German and
 * English). A text field right before the password counts as user name when nothing else says so.
 */
object VaultAutofillRules {
    private val PASSWORD_WORDS = listOf("password", "passwort", "kennwort", "pass", "pwd", "pin", "geheim")
    private val USER_WORDS = listOf("user", "login", "email", "e-mail", "mail", "benutzer", "konto", "account", "kunden", "anmelde", "nutzer", "identifier")
    private val NOT_LOGIN = listOf("search", "suche", "captcha", "otp", "code", "token", "zip", "plz", "phone", "telefon")

    private fun words(d: FieldDesc) = listOfNotNull(d.idEntry, d.hint, d.htmlName).joinToString(" ").lowercase()

    fun isPassword(d: FieldDesc): Boolean {
        if (d.hints.any { it.equals(View.AUTOFILL_HINT_PASSWORD, true) || it.contains("password", true) }) return true
        val variation = d.inputType and InputType.TYPE_MASK_VARIATION
        val klass = d.inputType and InputType.TYPE_MASK_CLASS
        if (klass == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return true
        if (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        if (d.htmlType.equals("password", true)) return true
        if (d.htmlAutocomplete?.contains("password", true) == true) return true
        val w = words(d)
        return w.isNotEmpty() && PASSWORD_WORDS.any { it in w } && NOT_LOGIN.none { it in w }
    }

    fun isUsername(d: FieldDesc): Boolean {
        if (isPassword(d)) return false
        if (d.hints.any { it.equals(View.AUTOFILL_HINT_USERNAME, true) || it.equals(View.AUTOFILL_HINT_EMAIL_ADDRESS, true) || it.contains("username", true) || it.contains("email", true) }) return true
        val variation = d.inputType and InputType.TYPE_MASK_VARIATION
        if ((d.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        ) return true
        if (d.htmlType.equals("email", true)) return true
        if (d.htmlAutocomplete?.let { it.contains("username", true) || it.contains("email", true) } == true) return true
        val w = words(d)
        return w.isNotEmpty() && USER_WORDS.any { it in w } && NOT_LOGIN.none { it in w }
    }

    /** Indices into [fields] (in screen order): the user name field (or null) and the password fields. */
    fun classify(fields: List<FieldDesc>): Pair<Int?, List<Int>> {
        val passwords = fields.indices.filter { fields[it].isText && isPassword(fields[it]) }
        var user = fields.indices.firstOrNull { fields[it].isText && isUsername(fields[it]) }
        if (user == null && passwords.isNotEmpty()) {
            // the text field just before the first password
            user = (passwords.first() - 1 downTo 0).firstOrNull { fields[it].isText && !isPassword(fields[it]) }
        }
        return user to passwords
    }
}

/** A login form found in an AssistStructure: where to fill, and (for saving) what was typed. */
data class VaultAutofillForm(
    val packageName: String,
    val webDomain: String?,
    val usernameId: AutofillId?,
    val passwordIds: List<AutofillId>,
    val username: String,
    val password: String,
) {
    val ids: List<AutofillId> get() = listOfNotNull(usernameId) + passwordIds

    companion object {
        /** Walks the structure; null when it has no login field. */
        fun parse(structure: AssistStructure): VaultAutofillForm? {
            val nodes = ArrayList<AssistStructure.ViewNode>()
            var webDomain: String? = null
            fun walk(n: AssistStructure.ViewNode) {
                if (n.webDomain != null && webDomain == null) webDomain = n.webDomain
                if (n.autofillId != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.visibility == View.VISIBLE) nodes += n
                for (i in 0 until n.childCount) walk(n.getChildAt(i))
            }
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)
            if (nodes.isEmpty()) return null
            val descs = nodes.map { n ->
                val attrs = n.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase() to it.second }
                FieldDesc(
                    hints = n.autofillHints?.toList().orEmpty(),
                    inputType = n.inputType,
                    idEntry = n.idEntry,
                    hint = n.hint,
                    htmlType = attrs["type"],
                    htmlAutocomplete = attrs["autocomplete"],
                    htmlName = attrs["name"] ?: attrs["id"],
                    isText = n.htmlInfo == null || n.htmlInfo?.tag.equals("input", true),
                )
            }
            val (user, passwords) = VaultAutofillRules.classify(descs)
            if (user == null && passwords.isEmpty()) return null
            fun text(i: Int?) = i?.let { nodes[it].autofillValue?.takeIf { v -> v.isText }?.textValue?.toString() }.orEmpty()
            return VaultAutofillForm(
                packageName = structure.activityComponent?.packageName.orEmpty(),
                webDomain = webDomain?.takeIf { it.isNotBlank() },
                usernameId = user?.let { nodes[it].autofillId },
                passwordIds = passwords.mapNotNull { nodes[it].autofillId },
                username = text(user),
                password = passwords.firstNotNullOfOrNull { text(it).takeIf { s -> s.isNotEmpty() } }.orEmpty(),
            )
        }
    }
}
