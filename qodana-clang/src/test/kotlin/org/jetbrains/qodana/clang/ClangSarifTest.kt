package org.jetbrains.qodana.clang

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ClangSarifTest {
    private val mapper = ObjectMapper()

    private fun writeSarif(
        dir: Path,
        json: String,
    ): Path = dir.resolve("qodana.sarif.json").also { Files.writeString(it, json) }

    private fun driver(sarif: Path) = mapper.readTree(sarif.toFile()).path("runs")[0].path("tool").path("driver")

    @Test
    fun `brands driver name fullName and version`(
        @TempDir dir: Path,
    ) {
        val sarif = writeSarif(dir, """{"runs":[{"tool":{"driver":{"name":"clang-tidy","rules":[]}}}]}""")
        ClangSarif.postProcess(sarif)
        assertEquals("QDCLC", driver(sarif).path("name").asText())
        assertEquals("Qodana Community for C/C++", driver(sarif).path("fullName").asText())
        assertEquals(BuildInfo.VERSION, driver(sarif).path("version").asText())
    }

    @Test
    fun `brands every run`(
        @TempDir dir: Path,
    ) {
        val sarif =
            writeSarif(
                dir,
                """{"runs":[
                   {"tool":{"driver":{"name":"clang-tidy"}}},
                   {"tool":{"driver":{"name":"clang-tidy"}}}
                ]}""",
            )
        ClangSarif.postProcess(sarif)
        val runs = mapper.readTree(sarif.toFile()).path("runs")
        assertEquals("QDCLC", runs[0].path("tool").path("driver").path("name").asText())
        assertEquals("QDCLC", runs[1].path("tool").path("driver").path("name").asText())
    }

    @Test
    fun `redirects self-referencing taxa to first taxon`(
        @TempDir dir: Path,
    ) {
        val sarif =
            writeSarif(
                dir,
                """{"runs":[{"tool":{"driver":{"name":"clang-tidy","taxa":[
                   {"id":"first"},
                   {"id":"self1","relationships":[{"target":{"id":"self1"}}]},
                   {"id":"self2","relationships":[{"target":{"id":"self2"}}]}
                ]}}}]}""",
            )
        ClangSarif.postProcess(sarif)
        val taxa = driver(sarif).path("taxa")
        assertEquals("first", taxa[1].path("relationships")[0].path("target").path("id").asText())
        assertEquals("first", taxa[2].path("relationships")[0].path("target").path("id").asText())
    }

    @Test
    fun `leaves non-self-referencing taxon untouched`(
        @TempDir dir: Path,
    ) {
        val sarif =
            writeSarif(
                dir,
                """{"runs":[{"tool":{"driver":{"name":"clang-tidy","taxa":[
                   {"id":"first"},
                   {"id":"b","relationships":[{"target":{"id":"first"}}]}
                ]}}}]}""",
            )
        ClangSarif.postProcess(sarif)
        assertEquals("first", driver(sarif).path("taxa")[1].path("relationships")[0].path("target").path("id").asText())
    }

    @Test
    fun `brands driver with no taxa without error`(
        @TempDir dir: Path,
    ) {
        val sarif = writeSarif(dir, """{"runs":[{"tool":{"driver":{"name":"clang-tidy"}}}]}""")
        ClangSarif.postProcess(sarif)
        assertEquals("QDCLC", driver(sarif).path("name").asText())
    }
}
