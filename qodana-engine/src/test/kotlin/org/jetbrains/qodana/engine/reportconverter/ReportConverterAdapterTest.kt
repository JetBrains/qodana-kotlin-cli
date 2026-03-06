package org.jetbrains.qodana.engine.reportconverter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportConverterAdapterTest {

    @Test
    fun `prepareOutputDir recreates non-empty directory`(@TempDir tmpDir: Path) {
        val outputDir = tmpDir.resolve("report")
        Files.createDirectories(outputDir)
        Files.writeString(outputDir.resolve("old.html"), "stale")
        Files.createDirectories(outputDir.resolve("nested"))
        Files.writeString(outputDir.resolve("nested").resolve("stale.txt"), "stale")

        ReportConverterAdapter().prepareOutputDir(outputDir)

        assertTrue(Files.exists(outputDir))
        val hasEntries = Files.list(outputDir).use { stream -> stream.findAny().isPresent }
        assertFalse(hasEntries)
    }

    @Test
    fun `prepareOutputDir creates missing directory`(@TempDir tmpDir: Path) {
        val outputDir = tmpDir.resolve("report")

        ReportConverterAdapter().prepareOutputDir(outputDir)

        assertTrue(Files.exists(outputDir))
    }
}
