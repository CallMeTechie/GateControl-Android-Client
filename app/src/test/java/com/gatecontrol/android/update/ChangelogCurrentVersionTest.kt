package com.gatecontrol.android.update

import com.gatecontrol.android.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * „Das ist neu“ for the version this build is: release.yml writes the version and its CHANGELOG.md section in one
 * commit; the app reads that section by the version code. Fails when the two no longer meet (v1.100–1.102 showed only
 * the hint to „Alle Änderungen“ because 1.102.0 had no code for the parser).
 */
class ChangelogCurrentVersionTest {

    private val changelog: String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "CHANGELOG.md") }
        .first { it.isFile }
        .readText()

    @Test
    fun `version name and code agree as release yml computes them`() {
        assertEquals(BuildConfig.VERSION_CODE, UpdateVersions.code(BuildConfig.VERSION_NAME))
        assertEquals(BuildConfig.VERSION_NAME, UpdateVersions.name(BuildConfig.VERSION_CODE))
    }

    @Test
    fun `the installed version has its notes in CHANGELOG md`() {
        val notes = ChangelogParser.parse(changelog, BuildConfig.VERSION_CODE - 1, BuildConfig.VERSION_CODE)
        assertTrue(notes.isNotEmpty(), "CHANGELOG.md has no entry for ${BuildConfig.VERSION_NAME}")
        assertTrue(notes.all { it.text.isNotBlank() })
    }

    @Test
    fun `every section of CHANGELOG md has a version the app can read`() {
        val headings = Regex("""^## \[([^\]]+)]""", RegexOption.MULTILINE).findAll(changelog).map { it.groupValues[1] }.toList()
        assertTrue(headings.isNotEmpty())
        val unreadable = headings.filter { UpdateVersions.code(it) == null }
        assertTrue(unreadable.isEmpty(), "unreadable versions: $unreadable")
    }
}
