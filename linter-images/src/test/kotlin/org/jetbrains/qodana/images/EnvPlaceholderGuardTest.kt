package org.jetbrains.qodana.images

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * No committed pin source (`.env` or phase-0-decisions.md) may carry an unresolved `<...>` placeholder.
 * This is the safety net for the deferred jvm internal-feed repoint (QD-15032): the executor must
 * substitute the real pin values before committing, never leave a `<PUBLISHED_FULL_BUILD>` marker.
 */
class EnvPlaceholderGuardTest {
    private val placeholder = Regex("""<[^>\s]+>""")

    private fun assertNoPlaceholderInValues(file: Path) {
        file.readText().lineSequence().forEach { line ->
            val value = line.substringAfter('=', "") // KEY = value rows in both .env and the doc
            assertTrue(
                !placeholder.containsMatchIn(value),
                "unresolved placeholder in ${file.fileName}: $line",
            )
        }
    }

    @Test
    fun `no env value contains an unresolved placeholder`() {
        Files.list(Path.of("docker/images")).use { stream ->
            stream.filter { it.toString().endsWith(".env") }.forEach { assertNoPlaceholderInValues(it) }
        }
    }

    @Test
    fun `no phase-0-decisions pin row contains an unresolved placeholder`() {
        assertNoPlaceholderInValues(Path.of("docs/phase-0-decisions.md"))
    }
}
