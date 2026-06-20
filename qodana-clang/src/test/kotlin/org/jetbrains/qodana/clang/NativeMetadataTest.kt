package org.jetbrains.qodana.clang

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeMetadataTest {
    @Test
    fun `qodana yaml model graph is reachable for the agent`() {
        val yaml =
            """
            profile:
              name: starter
            script:
              name: custom
            include:
              - name: included-rule
            exclude:
              - name: excluded-rule
            plugins:
              - {}
            dotnet:
              solution: app.sln
            php: {}
            cpp:
              buildSystem: cmake
            failureConditions:
              severityThresholds:
                critical: 1
              testCoverageThresholds:
                total: 80
            licenseRules:
              - keys: ["MIT"]
            dependencyIgnores:
              - {}
            dependencyOverrides:
              - name: override
                version: "1.0"
            projectLicenses:
              - key: Apache-2.0
            customDependencies:
              - name: dep
                version: "2.0"
            dependencySbomExclude:
              - {}
            modulesToAnalyze:
              - {}
            coverage: {}
            hardcodedPasswords: {}
            """.trimIndent()

        val config: org.jetbrains.qodana.core.model.QodanaYaml = YAML_MAPPER.readValue(yaml)
        assertNotNull(config)
    }

    @Test
    fun `compile commands model is reachable for the agent`() {
        val json =
            """
            [
              {
                "directory": "/tmp/project",
                "file": "src/main.cpp"
              }
            ]
            """.trimIndent()

        val commands: List<CompileCommand> = JSON_MAPPER.readValue(json)
        assertEquals(1, commands.size)
        assertEquals("/tmp/project", commands.single().directory)
        assertEquals("src/main.cpp", commands.single().file)
        assertEquals("", commands.single().command)
        assertEquals(emptyList(), commands.single().arguments)
    }

    @Test
    fun `committed reflect-config registers clang native Jackson models`() {
        val reflectConfig = Path.of(REFLECT_CONFIG_PATH)
        assertTrue(
            Files.exists(reflectConfig),
            "expected $reflectConfig to exist; regenerate via `./gradlew :qodana-clang:metadataCopy`",
        )

        val entries: List<Map<String, Any?>> =
            ObjectMapper().readValue(reflectConfig.toFile(), object : TypeReference<List<Map<String, Any?>>>() {})
        val byName = entries.filter { it["name"] is String }.associateBy { it["name"] as String }

        val problems = mutableListOf<String>()
        for (fqcn in requiredClangReflectiveModelClasses()) {
            val entry = byName[fqcn]
            if (entry == null) {
                problems.add("$fqcn: not registered")
            } else {
                if (entry["allDeclaredFields"] != true) {
                    problems.add("$fqcn: allDeclaredFields must be true (Jackson reads every field)")
                }
                if (entry["allDeclaredConstructors"] != true) {
                    problems.add("$fqcn: allDeclaredConstructors must be true (constructor drift guard)")
                }
                if (entry["queryAllDeclaredConstructors"] != true) {
                    problems.add("$fqcn: queryAllDeclaredConstructors must be true (native constructor lookup)")
                }
                if (entry["queryAllDeclaredMethods"] != true) {
                    problems.add("$fqcn: queryAllDeclaredMethods must be true (native method lookup)")
                }
            }
        }

        assertTrue(
            problems.isEmpty(),
            "qodana-clang reflect-config.json is incomplete:\n" +
                problems.joinToString("\n") { "  $it" } +
                "\nRe-run agent capture for qodana-clang and keep the full Kotlin default-argument constructors.",
        )
    }

    @Test
    fun `committed reflect-config registers clang native qodana-sarif Gson models`() {
        val reflectConfig = Path.of(REFLECT_CONFIG_PATH)
        assertTrue(Files.exists(reflectConfig), "expected $reflectConfig to exist")

        val entries: List<Map<String, Any?>> =
            ObjectMapper().readValue(reflectConfig.toFile(), object : TypeReference<List<Map<String, Any?>>>() {})
        val byName = entries.filter { it["name"] is String }.associateBy { it["name"] as String }

        val problems = mutableListOf<String>()
        for (fqcn in requiredClangSarifModelClasses) {
            val entry = byName[fqcn]
            when {
                entry == null -> problems.add("$fqcn: not registered")
                // SarifUtil deserializes the SARIF graph via Gson: it needs each model either
                // reflectively constructable (no-arg <init>) or its fields readable. The agent-captured
                // shape varies by type (data class / enum / type-adapter), so require at least one.
                entry["allDeclaredFields"] != true && !registersNoArgConstructor(entry) ->
                    problems.add("$fqcn: neither allDeclaredFields nor a no-arg constructor registered")
            }
        }

        assertTrue(
            problems.isEmpty(),
            "qodana-clang reflect-config.json is missing qodana-sarif Gson metadata:\n" +
                problems.joinToString("\n") { "  $it" } +
                "\nCopy the com.jetbrains.qodana.sarif.* entries from qodana-cli's reflect-config.json verbatim.",
        )
    }

    private companion object {
        val YAML_MAPPER = YAMLMapper().registerModule(kotlinModule())
        val JSON_MAPPER = ObjectMapper().registerModule(kotlinModule())

        const val REFLECT_CONFIG_PATH =
            "src/main/resources/META-INF/native-image/org.jetbrains.qodana/qodana-clang/reflect-config.json"
    }
}

private fun requiredClangReflectiveModelClasses(): List<String> =
    listOf(
        "org.jetbrains.qodana.clang.CompileCommand",
        "org.jetbrains.qodana.core.model.InspectScope",
        "org.jetbrains.qodana.core.model.QodanaYaml",
        "org.jetbrains.qodana.core.model.YamlCoverage",
        "org.jetbrains.qodana.core.model.YamlCoverageThresholds",
        "org.jetbrains.qodana.core.model.YamlCpp",
        "org.jetbrains.qodana.core.model.YamlCustomDependency",
        "org.jetbrains.qodana.core.model.YamlDependencyIgnore",
        "org.jetbrains.qodana.core.model.YamlDependencyOverride",
        "org.jetbrains.qodana.core.model.YamlDotNet",
        "org.jetbrains.qodana.core.model.YamlFailureConditions",
        "org.jetbrains.qodana.core.model.YamlHardcodedPasswords",
        "org.jetbrains.qodana.core.model.YamlLicenseOverride",
        "org.jetbrains.qodana.core.model.YamlLicenseRule",
        "org.jetbrains.qodana.core.model.YamlModuleToAnalyze",
        "org.jetbrains.qodana.core.model.YamlPhp",
        "org.jetbrains.qodana.core.model.YamlPlugin",
        "org.jetbrains.qodana.core.model.YamlProfile",
        "org.jetbrains.qodana.core.model.YamlScript",
        "org.jetbrains.qodana.core.model.YamlSeverityThresholds",
    )

// A no-arg ctor is invocable if allDeclaredConstructors is registered, or a methods entry declares
// `<init>` with an explicitly-empty parameterTypes list (qodana-cli's agent-captured shape).
private fun registersNoArgConstructor(entry: Map<String, Any?>): Boolean =
    entry["allDeclaredConstructors"] == true ||
        (entry["methods"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().any {
            it["name"] == "<init>" && (it["parameterTypes"] as? List<*>)?.isEmpty() == true
        }

// The com.jetbrains.qodana.sarif.* Gson models reachable from the shared QodanaSarifService ->
// SarifUtil read/write path (qodana-clang/Main.kt wires QodanaSarifService into ClangLinter).
// Mirrored verbatim from qodana-cli's agent-captured reflect-config — the proven-complete set for
// that path. Regenerate by re-extracting the com.jetbrains.qodana.sarif.* names from qodana-cli's
// reflect-config.json.
private val requiredClangSarifModelClasses: List<String> =
    listOf(
        "com.jetbrains.qodana.sarif.model.Address",
        "com.jetbrains.qodana.sarif.model.Artifact",
        "com.jetbrains.qodana.sarif.model.ArtifactChange",
        "com.jetbrains.qodana.sarif.model.ArtifactContent",
        "com.jetbrains.qodana.sarif.model.ArtifactLocation",
        "com.jetbrains.qodana.sarif.model.Attachment",
        "com.jetbrains.qodana.sarif.model.CodeFlow",
        "com.jetbrains.qodana.sarif.model.ConfigurationOverride",
        "com.jetbrains.qodana.sarif.model.Content",
        "com.jetbrains.qodana.sarif.model.Conversion",
        "com.jetbrains.qodana.sarif.model.Edge",
        "com.jetbrains.qodana.sarif.model.EdgeTraversal",
        "com.jetbrains.qodana.sarif.model.EnvironmentVariables",
        "com.jetbrains.qodana.sarif.model.Exception",
        "com.jetbrains.qodana.sarif.model.ExternalProperties",
        "com.jetbrains.qodana.sarif.model.ExternalProperties\$Version",
        "com.jetbrains.qodana.sarif.model.ExternalPropertyFileReference",
        "com.jetbrains.qodana.sarif.model.ExternalPropertyFileReferences",
        "com.jetbrains.qodana.sarif.model.FinalState",
        "com.jetbrains.qodana.sarif.model.Fix",
        "com.jetbrains.qodana.sarif.model.GlobalMessageStrings",
        "com.jetbrains.qodana.sarif.model.Graph",
        "com.jetbrains.qodana.sarif.model.GraphTraversal",
        "com.jetbrains.qodana.sarif.model.Hashes",
        "com.jetbrains.qodana.sarif.model.Headers",
        "com.jetbrains.qodana.sarif.model.Headers__1",
        "com.jetbrains.qodana.sarif.model.ImmutableState",
        "com.jetbrains.qodana.sarif.model.ImmutableState__1",
        "com.jetbrains.qodana.sarif.model.InitialState",
        "com.jetbrains.qodana.sarif.model.InitialState__1",
        "com.jetbrains.qodana.sarif.model.Invocation",
        "com.jetbrains.qodana.sarif.model.Level",
        "com.jetbrains.qodana.sarif.model.Location",
        "com.jetbrains.qodana.sarif.model.LocationRelationship",
        "com.jetbrains.qodana.sarif.model.LogicalLocation",
        "com.jetbrains.qodana.sarif.model.Message",
        "com.jetbrains.qodana.sarif.model.MessageStrings",
        "com.jetbrains.qodana.sarif.model.MultiformatMessageString",
        "com.jetbrains.qodana.sarif.model.Node",
        "com.jetbrains.qodana.sarif.model.Notification",
        "com.jetbrains.qodana.sarif.model.Notification\$Level",
        "com.jetbrains.qodana.sarif.model.OriginalUriBaseIds",
        "com.jetbrains.qodana.sarif.model.Parameters",
        "com.jetbrains.qodana.sarif.model.PhysicalLocation",
        "com.jetbrains.qodana.sarif.model.Rectangle",
        "com.jetbrains.qodana.sarif.model.Region",
        "com.jetbrains.qodana.sarif.model.Replacement",
        "com.jetbrains.qodana.sarif.model.ReportingConfiguration",
        "com.jetbrains.qodana.sarif.model.ReportingDescriptor",
        "com.jetbrains.qodana.sarif.model.ReportingDescriptorReference",
        "com.jetbrains.qodana.sarif.model.ReportingDescriptorRelationship",
        "com.jetbrains.qodana.sarif.model.Result",
        "com.jetbrains.qodana.sarif.model.Result\$BaselineState",
        "com.jetbrains.qodana.sarif.model.Result\$Kind",
        "com.jetbrains.qodana.sarif.model.ResultProvenance",
        "com.jetbrains.qodana.sarif.model.Role",
        "com.jetbrains.qodana.sarif.model.Run",
        "com.jetbrains.qodana.sarif.model.Run\$ColumnKind",
        "com.jetbrains.qodana.sarif.model.RunAutomationDetails",
        "com.jetbrains.qodana.sarif.model.SarifReport",
        "com.jetbrains.qodana.sarif.model.SarifReport\$Version",
        "com.jetbrains.qodana.sarif.model.SpecialLocations",
        "com.jetbrains.qodana.sarif.model.Stack",
        "com.jetbrains.qodana.sarif.model.StackFrame",
        "com.jetbrains.qodana.sarif.model.State",
        "com.jetbrains.qodana.sarif.model.Suppression",
        "com.jetbrains.qodana.sarif.model.Suppression\$Kind",
        "com.jetbrains.qodana.sarif.model.Suppression\$Status",
        "com.jetbrains.qodana.sarif.model.ThreadFlow",
        "com.jetbrains.qodana.sarif.model.ThreadFlowLocation",
        "com.jetbrains.qodana.sarif.model.ThreadFlowLocation\$Importance",
        "com.jetbrains.qodana.sarif.model.Tool",
        "com.jetbrains.qodana.sarif.model.ToolComponent",
        "com.jetbrains.qodana.sarif.model.ToolComponentReference",
        "com.jetbrains.qodana.sarif.model.TranslationMetadata",
        "com.jetbrains.qodana.sarif.model.VersionControlDetails",
        "com.jetbrains.qodana.sarif.model.VersionedMap\$VersionedMapTypeAdapter",
        "com.jetbrains.qodana.sarif.model.WebRequest",
        "com.jetbrains.qodana.sarif.model.WebResponse",
    )
