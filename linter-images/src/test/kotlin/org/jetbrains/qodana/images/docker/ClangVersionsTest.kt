package org.jetbrains.qodana.images.docker

import org.jetbrains.qodana.images.EnvContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Guards the single-source clang version list (Phase 3, Task 3.9). */
class ClangVersionsTest {
    private val file: Path = Path.of("docker/clang-versions.txt")
    private val imagesDir: Path = Path.of("docker/images")

    private fun rows(): List<Pair<Int, String>> =
        Files
            .readAllLines(file)
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                assertEquals(2, parts.size, "each row is '<clang-major> <os-codename>', was: '$line'")
                parts[0].toInt() to parts[1]
            }

    @Test
    fun `covers the locked clang matrix bookworm 16-19 and trixie 20-21`() {
        val rows = rows()
        val bookworm = rows.filter { it.second == "bookworm" }.map { it.first }.sorted()
        val trixie = rows.filter { it.second == "trixie" }.map { it.first }.sorted()
        assertEquals(listOf(16, 17, 18, 19), bookworm, "bookworm clang majors")
        assertEquals(listOf(20, 21), trixie, "trixie clang majors")
    }

    @Test
    fun `clang majors are unique across the set`() {
        val majors = rows().map { it.first }
        assertEquals(majors.size, majors.toSet().size, "duplicate clang major in clang-versions.txt")
    }

    // The resolver derives CLANG_OS from clang-versions.txt for NON-default majors, but the default cell
    // (empty version) emits no build args and ships the .env's baked CLANG_OS as-is — never cross-checked
    // against this file. Editing a clang-major→OS row here without updating the .env would split-brain the
    // default cell vs every version cell for that major, silently. Bind every CLANG-bearing .env's default
    // OS to its clang-versions.txt row so that drift is a build-time contract failure. Discovered by
    // scanning docker/images/*.env (a future CLANG-bearing image is covered automatically).
    @Test
    fun `every CLANG-bearing env's CLANG_OS matches its clang-versions row`() {
        val osByMajor = rows().associate { it.first to it.second }
        val slugs =
            Files.list(imagesDir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".env") }
                    .map { it.fileName.toString().removeSuffix(".env") }
                    .sorted()
                    .toList()
            }
        val clangEnvs = slugs.filter { "CLANG" in EnvContract.parseEnv(it) }
        assertTrue(clangEnvs.isNotEmpty(), "expected at least one CLANG-bearing .env under docker/images")
        clangEnvs.forEach { slug ->
            val env = EnvContract.parseEnv(slug)
            val major = env.getValue("CLANG").toInt()
            val expectedOs = osByMajor[major] ?: error("$slug.env CLANG=$major has no clang-versions.txt row")
            assertEquals(expectedOs, env["CLANG_OS"], "$slug CLANG_OS must match clang-versions.txt for clang $major")
        }
    }
}
