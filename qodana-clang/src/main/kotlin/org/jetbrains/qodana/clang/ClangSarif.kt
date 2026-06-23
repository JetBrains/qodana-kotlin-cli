package org.jetbrains.qodana.clang

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jetbrains.qodana.core.product.Linters
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Post-processes the merged clang-tidy SARIF: brands the driver as Qodana's linter and repairs a
 * clang-tidy quirk where a taxon's relationship targets itself. Ports qodana-cli
 * `internal/platform/sarif.go` (driver branding) and `clang/run.go:147` (`fixupClangLinterTaxa`).
 *
 * Every run is processed: the Kotlin merge appends one run per translation unit (see the QD-15116
 * spec's "Divergences from the Go source").
 */
object ClangSarif {
    private val logger = LoggerFactory.getLogger(ClangSarif::class.java)
    private val mapper = ObjectMapper()

    fun postProcess(sarifPath: Path) {
        logger.info("Post-processing clang SARIF report: {}", sarifPath)
        val root = mapper.readTree(sarifPath.toFile())
        for (run in root.path("runs")) {
            brandDriver(run)
            fixupTaxa(run)
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(sarifPath.toFile(), root)
    }

    private fun brandDriver(run: JsonNode) {
        val driver = run.path("tool").path("driver") as? ObjectNode ?: return
        driver.put("name", Linters.CLANG.productCode)
        driver.put("fullName", Linters.CLANG.presentableName)
        driver.put("version", BuildInfo.VERSION)
    }

    // clang-tidy sometimes emits a taxon whose sole relationship targets its own id; redirect such
    // a target to the first taxon's id.
    private fun fixupTaxa(run: JsonNode) {
        val taxa = run.path("tool").path("driver").path("taxa")
        if (!taxa.isArray || taxa.isEmpty) return
        val firstId = taxa[0].path("id").asText().takeIf { it.isNotEmpty() } ?: return
        for (taxon in taxa) redirectSelfReference(taxon, firstId)
    }

    private fun redirectSelfReference(
        taxon: JsonNode,
        firstId: String,
    ) {
        val relationships = taxon.path("relationships")
        if (!relationships.isArray || relationships.size() != 1) return
        val target = relationships[0].path("target")
        val taxonId = taxon.path("id").asText()
        if (target is ObjectNode && taxonId.isNotEmpty() && target.path("id").asText() == taxonId) {
            target.put("id", firstId)
        }
    }
}
