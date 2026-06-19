package org.jetbrains.qodana.images.e2e

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactArchiverTest {
    @Test
    fun `archives sarif and the whole log tree`(
        @TempDir tmp: Path,
    ) {
        val results = Files.createDirectories(tmp.resolve("results"))
        Files.writeString(results.resolve("qodana.sarif.json"), "{}")
        val log = Files.createDirectories(results.resolve("log"))
        Files.writeString(log.resolve("idea.log"), "hello")
        val dest = tmp.resolve("dest")

        ArtifactArchiver.archive(results, dest)

        assertEquals("{}", dest.resolve("qodana.sarif.json").readText())
        assertEquals("hello", dest.resolve("log").resolve("idea.log").readText())
    }

    @Test
    fun `tolerates a results dir with no sarif and no log`(
        @TempDir tmp: Path,
    ) {
        val results = Files.createDirectories(tmp.resolve("results"))
        val dest = tmp.resolve("dest")

        ArtifactArchiver.archive(results, dest)

        assertTrue(Files.isDirectory(dest))
        assertFalse(Files.exists(dest.resolve("qodana.sarif.json")))
        assertFalse(Files.exists(dest.resolve("log")))
    }
}
