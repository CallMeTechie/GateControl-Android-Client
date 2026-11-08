package com.gatecontrol.android

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `File.createTempFile` throws "Prefix string too short" for a prefix of fewer than 3 characters — at run time only
 * (the upload into a vault crashed with the prefix "up"). Every literal prefix in the app's sources must have ≥ 3.
 * Gradle runs unit tests with the module directory as working dir.
 */
class TempFilePrefixTest {

    private val call = Regex("""createTempFile\(\s*"([^"]*)"""")

    @Test
    fun `every literal temp file prefix has at least 3 characters`() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val short = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().flatMap { (i, line) ->
                call.findAll(line).map { it.groupValues[1] }.filter { it.length < 3 }.map { "${f.name}:${i + 1} \"$it\"" }
            }
        }.toList()
        assertTrue(short.isEmpty(), "too short for File.createTempFile: $short")
    }
}
