package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.port.FileSystem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CdnetSarifTest {
    /**
     * Minimal FileSystem implementation that delegates copy to java.nio.file.Files.
     */
    private object TestFileSystem : FileSystem {
        override fun read(path: Path): String = Files.readString(path)

        override fun readBytes(path: Path): ByteArray = Files.readAllBytes(path)

        override fun write(
            path: Path,
            content: String,
        ) {
            Files.writeString(path, content)
        }

        override fun writeBytes(
            path: Path,
            content: ByteArray,
        ) {
            Files.write(path, content)
        }

        override fun copy(
            source: Path,
            target: Path,
        ) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }

        override fun walk(
            root: Path,
            glob: String?,
        ): Sequence<Path> = emptySequence()

        override fun exists(path: Path): Boolean = Files.exists(path)

        override fun createDirectories(path: Path): Path = Files.createDirectories(path)

        override fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix)

        override fun delete(path: Path) {
            Files.deleteIfExists(path)
        }

        override fun extractArchive(
            archive: Path,
            target: Path,
        ) {
            error("Not needed in tests")
        }
    }

    private fun writeSarif(
        dir: Path,
        content: String,
    ): Path {
        val sarifPath = dir.resolve("qodana.sarif.json")
        Files.writeString(sarifPath, content)
        return sarifPath
    }

    @Test
    fun `fingerprint renamed from contextRegionHash to equalIndicator`(
        @TempDir tempDir: Path,
    ) {
        val logDir = tempDir.resolve("log").also { Files.createDirectories(it) }
        val sarif =
            writeSarif(
                tempDir,
                """
                {
                  "runs": [{
                    "tool": { "driver": { "rules": [], "taxa": [] } },
                    "results": [{
                      "partialFingerprints": {
                        "contextRegionHash/v1": "abc123"
                      }
                    }]
                  }]
                }
                """.trimIndent(),
            )

        CdnetSarif.patchReport(sarif, logDir, TestFileSystem)

        val patched = Files.readString(sarif)
        assertTrue(patched.contains("equalIndicator/v1"), "Should contain renamed fingerprint key")
        assertTrue(!patched.contains("contextRegionHash/v1"), "Should not contain original fingerprint key")
        assertTrue(patched.contains("abc123"), "Fingerprint value should be preserved")
    }

    @Test
    fun `missing fullDescription gets shortDescription`(
        @TempDir tempDir: Path,
    ) {
        val logDir = tempDir.resolve("log").also { Files.createDirectories(it) }
        val sarif =
            writeSarif(
                tempDir,
                """
                {
                  "runs": [{
                    "tool": {
                      "driver": {
                        "rules": [{
                          "id": "R1",
                          "shortDescription": { "text": "Short desc" }
                        }],
                        "taxa": []
                      }
                    },
                    "results": []
                  }]
                }
                """.trimIndent(),
            )

        CdnetSarif.patchReport(sarif, logDir, TestFileSystem)

        val patched = Files.readString(sarif)
        // fullDescription should now exist and match shortDescription
        assertTrue(patched.contains("fullDescription"), "fullDescription should be added")
        assertTrue(patched.contains("Short desc"), "Description text should be preserved")
    }

    @Test
    fun `taxa missing name gets id value`(
        @TempDir tempDir: Path,
    ) {
        val logDir = tempDir.resolve("log").also { Files.createDirectories(it) }
        val sarif =
            writeSarif(
                tempDir,
                """
                {
                  "runs": [{
                    "tool": {
                      "driver": {
                        "rules": [],
                        "taxa": [{
                          "id": "CAT001"
                        }]
                      }
                    },
                    "results": []
                  }]
                }
                """.trimIndent(),
            )

        CdnetSarif.patchReport(sarif, logDir, TestFileSystem)

        val patched = Files.readString(sarif)
        // The taxon should now have name = id
        assertTrue(
            patched.contains("\"name\" : \"CAT001\"") || patched.contains("\"name\":\"CAT001\""),
            "Taxa name should be set to id value. Actual content: $patched",
        )
    }

    @Test
    fun `original report is backed up to logDir`(
        @TempDir tempDir: Path,
    ) {
        val logDir = tempDir.resolve("log").also { Files.createDirectories(it) }
        val originalContent =
            """
            {
              "runs": [{
                "tool": { "driver": { "rules": [], "taxa": [] } },
                "results": []
              }]
            }
            """.trimIndent()
        val sarif = writeSarif(tempDir, originalContent)

        CdnetSarif.patchReport(sarif, logDir, TestFileSystem)

        val backupPath = logDir.resolve("clt.original.sarif.json")
        assertTrue(Files.exists(backupPath), "Backup file should exist")
        assertEquals(originalContent, Files.readString(backupPath))
    }

    @Test
    fun `existing qodana fingerprint is preserved and not overwritten`(
        @TempDir tempDir: Path,
    ) {
        val logDir = tempDir.resolve("log").also { Files.createDirectories(it) }
        val sarif =
            writeSarif(
                tempDir,
                """
                {
                  "runs": [{
                    "tool": { "driver": { "rules": [], "taxa": [] } },
                    "results": [{
                      "partialFingerprints": {
                        "contextRegionHash/v1": "newHash",
                        "equalIndicator/v1": "existingHash"
                      }
                    }]
                  }]
                }
                """.trimIndent(),
            )

        CdnetSarif.patchReport(sarif, logDir, TestFileSystem)

        val patched = Files.readString(sarif)
        assertTrue(patched.contains("existingHash"), "Existing qodana fingerprint should be preserved")
    }
}
