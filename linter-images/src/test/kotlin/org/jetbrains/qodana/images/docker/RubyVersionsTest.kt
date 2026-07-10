package org.jetbrains.qodana.images.docker

import org.jetbrains.qodana.images.EnvContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RubyVersionsTest {
    private val file: Path = Path.of("docker/ruby-versions.txt")

    data class Row(
        val version: String,
        val digest: String,
        val default: Boolean,
    )

    private fun rows(): List<Row> =
        Files
            .readAllLines(file)
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                assertTrue(parts.size == 2 || (parts.size == 3 && parts[2] == "default"), "bad row: '$line'")
                Row(parts[0], parts[1], parts.getOrNull(2) == "default")
            }

    @Test
    fun `covers ruby 3_2 3_3 3_4 with 3_3 the default`() {
        val rows = rows()
        assertEquals(listOf("3.2", "3.3", "3.4"), rows.map { it.version }.sorted())
        // The default-version assertion is a LITERAL (3.3), not re-derived from the version-file's default marker,
        // so any intentional move of the default is caught by this test. This is not tautological — a deliberate
        // change to ruby-versions.txt's default marker must break this test.
        val defaults = rows.filter { it.default }
        assertEquals(1, defaults.size, "exactly one default ruby row")
        assertEquals("3.3", defaults.single().version, "3.3 is the default (no-suffix) ruby")
        rows.forEach { row ->
            val digest = row.digest
            assertTrue(digest.startsWith("dhi.io/ruby:") && "@sha256:" in digest, "bad digest: $digest")
        }
    }

    @Test
    fun `digests match phase-0-decisions ruby base pins (no drift)`() {
        val byVer = rows().associate { it.version to it.digest }
        assertEquals(EnvContract.pin("QD_RUBY_BASE_IMAGE"), byVer["3.3"], "3.3 digest == phase-0 primary")
        assertEquals(EnvContract.pin("QD_RUBY_32_BASE_IMAGE"), byVer["3.2"], "3.2 digest == phase-0")
        assertEquals(EnvContract.pin("QD_RUBY_34_BASE_IMAGE"), byVer["3.4"], "3.4 digest == phase-0")
    }

    @Test
    fun `default row digest equals qodana-ruby env base image`() {
        val default = rows().single { it.default }
        assertEquals(EnvContract.parseEnv("qodana-ruby")["QD_BASE_IMAGE"], default.digest, "default == qodana-ruby.env")
    }
}
