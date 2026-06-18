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
