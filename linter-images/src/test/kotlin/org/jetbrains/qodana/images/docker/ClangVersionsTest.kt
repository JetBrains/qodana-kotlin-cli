package org.jetbrains.qodana.images.docker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Guards the single-source clang version list (Phase 3, Task 3.9). */
class ClangVersionsTest {
    private val file: Path = Path.of("docker/clang-versions.txt")

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
}
