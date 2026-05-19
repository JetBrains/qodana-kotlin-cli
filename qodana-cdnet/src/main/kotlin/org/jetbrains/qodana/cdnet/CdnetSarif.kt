package org.jetbrains.qodana.cdnet

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.jetbrains.qodana.core.port.FileSystem
import org.slf4j.LoggerFactory
import java.nio.file.Path

object CdnetSarif {
    private val logger = LoggerFactory.getLogger(CdnetSarif::class.java)
    private val mapper = ObjectMapper().registerModule(kotlinModule())

    private const val CLT_FINGERPRINT = "contextRegionHash/v1"
    private const val QODANA_FINGERPRINT = "equalIndicator/v1"

    fun patchReport(
        sarifPath: Path,
        logDir: Path,
        fileSystem: FileSystem,
    ) {
        logger.info("Patching cdnet SARIF report: {}", sarifPath)
        val backupPath = logDir.resolve("clt.original.sarif.json")
        fileSystem.copy(sarifPath, backupPath)

        val root = mapper.readTree(sarifPath.toFile())
        val runs = root.path("runs")

        for (run in runs) {
            patchRules(run)
            patchTaxa(run)
            patchResults(run)
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(sarifPath.toFile(), root)
    }

    private fun patchRules(run: JsonNode) {
        val rules = run.path("tool").path("driver").path("rules")
        for (rule in rules) {
            val obj = rule as? ObjectNode ?: continue

            // Ensure both fullDescription and shortDescription exist
            if (!obj.has("fullDescription") && obj.has("shortDescription")) {
                obj.set<JsonNode>("fullDescription", obj.get("shortDescription"))
            }
            if (!obj.has("shortDescription") && obj.has("fullDescription")) {
                obj.set<JsonNode>("shortDescription", obj.get("fullDescription"))
            }

            // Set defaultConfiguration.enabled = true
            val defaultConfig = mapper.createObjectNode().put("enabled", true)
            obj.set<JsonNode>("defaultConfiguration", defaultConfig)
        }
    }

    private fun patchTaxa(run: JsonNode) {
        val taxa = run.path("tool").path("driver").path("taxa")
        for (taxon in taxa) {
            val obj = taxon as? ObjectNode ?: continue
            // If name is missing, use id
            if (!obj.has("name") || obj.get("name").asText().isBlank()) {
                val id = obj.get("id")?.asText() ?: continue
                obj.put("name", id)
            }
        }
    }

    private fun patchResults(run: JsonNode) {
        val results = run.path("results")
        for (result in results) {
            val obj = result as? ObjectNode ?: continue
            val fingerprints = obj.get("partialFingerprints") as? ObjectNode ?: continue

            val cltValue = fingerprints.get(CLT_FINGERPRINT)?.asText()
            val qodanaValue = fingerprints.get(QODANA_FINGERPRINT)?.asText()

            if (!cltValue.isNullOrBlank() && qodanaValue.isNullOrBlank()) {
                fingerprints.put(QODANA_FINGERPRINT, cltValue)
                fingerprints.remove(CLT_FINGERPRINT)
            }
        }
    }
}
