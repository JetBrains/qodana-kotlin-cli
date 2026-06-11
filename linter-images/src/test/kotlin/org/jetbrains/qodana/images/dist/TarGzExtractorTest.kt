package org.jetbrains.qodana.images.dist

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.isExecutable
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TarGzExtractorTest {
    /**
     * Writes a `.tar.gz` whose entries are exactly [entries] (path -> contents). Directory entries
     * are inferred from the slashes in each path; mirrors the real qodana dist that nests everything
     * under a single top-level directory.
     */
    private fun writeTarGz(
        archive: Path,
        entries: Map<String, String>,
    ) {
        TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(archive)))).use { tar ->
            for ((path, content) in entries) {
                val bytes = content.toByteArray()
                val entry = TarArchiveEntry(path)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    @Test
    fun `flattens the single top-level dir so target is the IDE root`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("qodana-QDJVM-253.200.tar.gz")
        writeTarGz(
            archive,
            mapOf(
                "qodana-QDJVM-253.200/product-info.json" to """{"productCode":"IU"}""",
                "qodana-QDJVM-253.200/bin/idea.sh" to "#!/bin/sh\n",
                "qodana-QDJVM-253.200/lib/app.jar" to "JAR",
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        // target IS the IDE root: product-info.json sits directly under it, not under a nested dir.
        val productInfo = target.resolve("product-info.json")
        assertTrue(Files.exists(productInfo), "product-info.json must be directly under target")
        assertEquals("""{"productCode":"IU"}""", productInfo.readText())
        assertTrue(Files.exists(target.resolve("bin/idea.sh")))
        assertTrue(Files.exists(target.resolve("lib/app.jar")))
        // No leftover wrapper directory under target.
        assertTrue(Files.notExists(target.resolve("qodana-QDJVM-253.200")))
    }

    @Test
    fun `does not flatten when there is no single top-level dir`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("flat.tar.gz")
        writeTarGz(
            archive,
            mapOf(
                "product-info.json" to """{"productCode":"IU"}""",
                "bin/idea.sh" to "#!/bin/sh\n",
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        assertTrue(Files.exists(target.resolve("product-info.json")))
        assertTrue(Files.exists(target.resolve("bin/idea.sh")))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // executable bit + symlinks are POSIX; the IDE images run on Linux.
    fun `preserves executable bit and symlinks`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("qodana-QDJVM-253.200.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(archive)))).use { tar ->
            // Executable launcher (mode 0755).
            val launcherBytes = "#!/bin/sh\n".toByteArray()
            val launcher = TarArchiveEntry("qodana-QDJVM-253.200/bin/idea.sh")
            launcher.mode = "755".toInt(8)
            launcher.size = launcherBytes.size.toLong()
            tar.putArchiveEntry(launcher)
            tar.write(launcherBytes)
            tar.closeArchiveEntry()

            // A real symlink entry pointing at the launcher.
            val link = TarArchiveEntry("qodana-QDJVM-253.200/bin/idea", TarArchiveEntry.LF_SYMLINK)
            link.linkName = "idea.sh"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
        }
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        val launcherOut = target.resolve("bin/idea.sh")
        assertTrue(launcherOut.isExecutable(), "extracted launcher must keep its executable bit")

        val symlinkOut = target.resolve("bin/idea")
        assertTrue(Files.isSymbolicLink(symlinkOut), "symlink entry must be extracted as a real symlink")
        assertEquals("idea.sh", symlinkOut.readSymbolicLink().toString())
    }

    @Test
    fun `fails loudly when the archive is missing`(
        @TempDir tmp: Path,
    ) {
        assertFailsWith<Exception> {
            TarGzExtractor().extractFlattened(tmp.resolve("nope.tar.gz"), tmp.resolve("staging"))
        }
    }
}
