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

    // A crash that never reaches idea.log (e.g. an IDE bootstrap failure before file logging is set up)
    // leaves no sarif and no log/ — but the container console still holds the cause. Persist it to the
    // host-owned dest so it survives even when the results dir is otherwise empty.
    @Test
    fun `writes the container console into dest when provided`(
        @TempDir tmp: Path,
    ) {
        val results = Files.createDirectories(tmp.resolve("results"))
        val dest = tmp.resolve("dest")

        ArtifactArchiver.archive(results, dest, stdout = "OUT-HEAD", stderr = "ERR-HEAD")

        assertEquals("OUT-HEAD", dest.resolve("container-stdout.txt").readText())
        assertEquals("ERR-HEAD", dest.resolve("container-stderr.txt").readText())
    }

    @Test
    fun `omits container console files when streams are null`(
        @TempDir tmp: Path,
    ) {
        val results = Files.createDirectories(tmp.resolve("results"))
        val dest = tmp.resolve("dest")

        ArtifactArchiver.archive(results, dest)

        assertFalse(Files.exists(dest.resolve("container-stdout.txt")))
        assertFalse(Files.exists(dest.resolve("container-stderr.txt")))
    }
}
