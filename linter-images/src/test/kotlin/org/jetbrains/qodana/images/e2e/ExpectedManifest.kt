package org.jetbrains.qodana.images.e2e

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Typed model of a fixture's `expected.json` (one per case). Mirrors the
 * committed `e2e/expected-manifest.schema.json`. Field names match the JSON
 * keys verbatim so jackson-module-kotlin maps them without annotations.
 */
data class ExpectedManifest(
    val case: String,
    val image: String,
    val description: String,
    val run: RunSpec = RunSpec(),
    val expectExitCode: Int = 0,
    val sarif: SarifExpectations = SarifExpectations(),
    val log: LogExpectations = LogExpectations(),
)

data class RunSpec(
    val network: String = "none",
    val capAdd: List<String> = emptyList(),
    val securityOpt: List<String> = emptyList(),
    val extraArgs: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val passEnv: List<String> = emptyList(),
    val failThreshold: Int? = null,
    val variants: List<Variant> = listOf(Variant()),
)

/** gitState ∈ {none, init-no-head, clean}. */
data class Variant(
    val id: String = "default",
    val qodanaYaml: String? = null,
    val gitState: String = "clean",
)

data class SarifExpectations(
    val tool: ToolExpectation? = null,
    val schemaValid: Boolean = false,
    val qodanaSeverityRequired: Boolean = false,
    val expectations: List<Expectation> = emptyList(),
)

data class ToolExpectation(
    val driverName: String? = null,
    val driverFullName: String? = null,
)

/**
 * presence ∈ {present, absent}; count like ">=1","==2","==0";
 * pin ∈ {confirmed, needs-pinning}.
 */
data class Expectation(
    val ruleId: String? = null,
    val ruleIdPattern: String? = null,
    val presence: String,
    val count: String? = null,
    val uriContains: String? = null,
    val messageContains: String? = null,
    val variant: String? = null,
    val pin: String = "confirmed",
    val guards: List<String> = emptyList(),
    val reason: String,
)

data class LogExpectations(
    val mustNotContain: List<LogRule> = emptyList(),
    // mustContain: a substring that MUST appear in idea.log. Drives the android
    // missing-SDK negative twin (QD-2179), which has no SARIF to assert against.
    val mustContain: List<LogRule> = emptyList(),
)

data class LogRule(
    val text: String,
    val guards: List<String> = emptyList(),
)

/** Loads an `expected.json` into [ExpectedManifest] using jackson-module-kotlin. */
object ManifestLoader {
    private val mapper = jacksonObjectMapper()

    fun load(path: Path): ExpectedManifest = mapper.readValue(Files.readString(path))
}
