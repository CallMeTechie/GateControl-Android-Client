package com.gatecontrol.android.vault

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Import (mockup A10): a Bitwarden JSON export (unencrypted), or a CSV of Bitwarden, 1Password, KeePass(XC), Chrome /
 * Edge or Firefox — the columns are recognised by their names. Read on the device only. Pure, unit-tested.
 */
object VaultImport {
    data class Result(val entries: List<EntryData>, val source: String) {
        val logins get() = entries.count { it.type == "login" }
        val withTotp get() = entries.count { it.totp.isNotEmpty() }
        val notes get() = entries.count { it.type == "note" }
        val cards get() = entries.count { it.type == "card" }
    }

    sealed class Outcome {
        data class Ok(val result: Result) : Outcome()
        /** A password-protected export: export again without a password. */
        data object Encrypted : Outcome()
        data object Unknown : Outcome()
    }

    fun parse(text: String): Outcome {
        val t = text.trimStart('﻿').trim()
        if (t.startsWith("{")) return bitwardenJson(t)
        return csv(t)
    }

    // ─── Bitwarden JSON ──────────────────────────────────────────────

    private fun bitwardenJson(text: String): Outcome {
        val o = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return Outcome.Unknown
        if (o.bool("encrypted") == true) return Outcome.Encrypted
        val items = o.arr("items") ?: return Outcome.Unknown
        val out = items.mapNotNull { e -> bitwardenItem(e as? JsonObject ?: return@mapNotNull null) }
        return Outcome.Ok(Result(out, "Bitwarden"))
    }

    private fun bitwardenItem(i: JsonObject): EntryData? {
        val name = i.str("name").orEmpty()
        val notes = i.str("notes").orEmpty()
        val fav = i.bool("favorite") == true
        val custom = i.arr("fields")?.mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            EntryData.CustomField(fo.str("name").orEmpty(), fo.str("value").orEmpty(), fo.int("type") == 1)
        }.orEmpty().filter { it.name.isNotBlank() || it.value.isNotBlank() }
        return when (i.int("type")) {
            1 -> {
                val l = i.obj("login") ?: JsonObject()
                val urls = l.arr("uris")?.mapNotNull { (it as? JsonObject)?.str("uri") }.orEmpty()
                EntryData(
                    type = "login", title = name.ifBlank { urls.firstNotNullOfOrNull { VaultMatch.host(it) }.orEmpty() }, fav = fav, notes = notes,
                    urls = urls, apps = urls.filter { it.startsWith("androidapp://") }.map { it.removePrefix("androidapp://") },
                    fields = mapOf("username" to l.str("username").orEmpty(), "password" to l.str("password").orEmpty(), "totp" to l.str("totp").orEmpty()),
                    custom = custom,
                )
            }
            2 -> EntryData(type = "note", title = name, fav = fav, notes = notes, custom = custom)
            3 -> {
                val c = i.obj("card") ?: JsonObject()
                val month = c.text("expMonth").padStart(2, '0').takeIf { it != "00" }
                val year = c.text("expYear").takeLast(2)
                EntryData(
                    type = "card", title = name, fav = fav, notes = notes, custom = custom,
                    fields = mapOf(
                        "holder" to c.str("cardholderName").orEmpty(), "number" to c.str("number").orEmpty(),
                        "expiry" to if (month != null && year.isNotEmpty()) "$month/$year" else "", "cvc" to c.str("code").orEmpty(),
                    ),
                )
            }
            4 -> {
                val d = i.obj("identity") ?: JsonObject()
                val full = listOf(d.str("firstName"), d.str("middleName"), d.str("lastName")).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
                val address = listOf(d.str("address1"), d.str("address2"), listOfNotNull(d.str("postalCode"), d.str("city")).joinToString(" "), d.str("country"))
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
                EntryData(
                    type = "identity", title = name, fav = fav, notes = notes, custom = custom,
                    fields = mapOf("name" to full, "email" to d.str("email").orEmpty(), "phone" to d.str("phone").orEmpty(), "address" to address),
                )
            }
            else -> null
        }?.let { e -> e.copy(fields = e.fields.filterValues { it.isNotEmpty() }) }
    }

    /** A string or a number as text ("3" and 3 alike). */
    private fun JsonObject.text(k: String): String =
        (get(k) as? com.google.gson.JsonPrimitive)?.takeIf { it.isString || it.isNumber }?.asString.orEmpty()

    // ─── CSV ─────────────────────────────────────────────────────────

    /** RFC 4180: quotes, doubled quotes, line breaks inside quotes; comma or semicolon separated. */
    fun csvRows(text: String): List<List<String>> {
        val firstLine = text.substringBefore('\n')
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(c)
                }
            } else when (c) {
                '"' -> quoted = true
                sep -> { row.add(cell.toString()); cell.clear() }
                '\r' -> Unit
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    private val COLUMNS = mapOf(
        "title" to listOf("title", "name", "account", "item name"),
        "url" to listOf("url", "login_uri", "uri", "website", "web site", "login url", "origin"),
        "username" to listOf("username", "login_username", "user name", "user", "login", "email", "e-mail"),
        "password" to listOf("password", "login_password"),
        "notes" to listOf("notes", "note", "extra", "comments"),
        "totp" to listOf("totp", "login_totp", "otpauth", "one-time password", "otp"),
        "type" to listOf("type"),
        "favorite" to listOf("favorite", "favourite"),
    )

    private fun csv(text: String): Outcome {
        val rows = csvRows(text)
        if (rows.size < 2) return Outcome.Unknown
        val header = rows.first().map { it.trim().lowercase() }
        val col = COLUMNS.mapValues { (_, names) -> names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } }
        if (col["password"] == null && col["notes"] == null) return Outcome.Unknown
        val source = when {
            "login_uri" in header -> "Bitwarden"
            "httprealm" in header -> "Firefox"
            "group" in header -> "KeePass"
            "otpauth" in header -> "1Password"
            header.take(5) == listOf("name", "url", "username", "password", "note") -> "Chrome / Edge"
            else -> "CSV"
        }
        fun get(r: List<String>, k: String) = col[k]?.let { r.getOrNull(it) }?.trim().orEmpty()
        val out = rows.drop(1).mapNotNull { r ->
            val url = get(r, "url")
            val password = get(r, "password")
            val username = get(r, "username")
            val notes = get(r, "notes")
            val title = get(r, "title").ifBlank { VaultMatch.host(url).orEmpty() }
            val isNote = get(r, "type") == "note" || (password.isEmpty() && username.isEmpty() && url.isEmpty())
            if (title.isBlank() && password.isBlank() && notes.isBlank()) return@mapNotNull null
            if (isNote) {
                EntryData(type = "note", title = title.ifBlank { notes.take(40) }, notes = notes)
            } else {
                EntryData(
                    type = "login", title = title, notes = notes, fav = get(r, "favorite") == "1",
                    urls = listOfNotNull(url.takeIf { it.isNotBlank() }),
                    fields = mapOf("username" to username, "password" to password, "totp" to get(r, "totp")).filterValues { it.isNotEmpty() },
                )
            }
        }
        return Outcome.Ok(Result(out, source))
    }

    /** Entries the vault already has (same title, user name and site) are left out. */
    fun withoutDuplicates(found: List<EntryData>, existing: List<EntryData>): Pair<List<EntryData>, Int> {
        fun key(e: EntryData) = Triple(e.title.trim().lowercase(), e.username.trim().lowercase(), e.urls.firstOrNull()?.let { VaultMatch.host(it)?.let(VaultMatch::site) }.orEmpty())
        val have = existing.map(::key).toHashSet()
        val seen = HashSet<Triple<String, String, String>>()
        val keep = found.filter { e -> val k = key(e); k !in have && seen.add(k) }
        return keep to (found.size - keep.size)
    }
}
