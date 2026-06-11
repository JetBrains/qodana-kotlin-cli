package org.jetbrains.qodana.images.dist

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
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
    fun `fails loudly when the archive is missing`(
        @TempDir tmp: Path,
    ) {
        assertFailsWith<Exception> {
            TarGzExtractor().extractFlattened(tmp.resolve("nope.tar.gz"), tmp.resolve("staging"))
        }
    }
}
