package org.jetbrains.qodana.app.config

import org.jetbrains.qodana.core.model.*
import java.nio.file.Path
import kotlin.test.*

class EffectiveConfigTest {

    // --- parse() tests ---

    @Test
    fun `parse minimal yaml with version`() {
        val yaml = EffectiveConfig.parse("version: \"1.0\"")
        assertEquals("1.0", yaml.version)
    }

    @Test
    fun `parse yaml with profile name`() {
        val yaml = EffectiveConfig.parse(
            """
            version: "1.0"
            profile:
              name: "Default"
            """.trimIndent()
        )
        assertEquals("Default", yaml.profile.name)
    }

    @Test
    fun `parse yaml with dotnet config`() {
        val yaml = EffectiveConfig.parse(
            """
            version: "1.0"
            dotnet:
              solution: "MySolution.sln"
              project: "MyProject.csproj"
              configuration: "Release"
              platform: "x64"
            """.trimIndent()
        )
        assertNotNull(yaml.dotnet)
        assertEquals("MySolution.sln", yaml.dotnet!!.solution)
        assertEquals("MyProject.csproj", yaml.dotnet!!.project)
        assertEquals("Release", yaml.dotnet!!.configuration)
        assertEquals("x64", yaml.dotnet!!.platform)
    }

    @Test
    fun `parse yaml with properties map`() {
        val yaml = EffectiveConfig.parse(
            """
            version: "1.0"
            properties:
              idea.suppressed.plugins.id: "com.intellij.java"
              qodana.recommended.profile.resource: "profile.xml"
            """.trimIndent()
        )
        assertEquals(2, yaml.properties.size)
        assertEquals("com.intellij.java", yaml.properties["idea.suppressed.plugins.id"])
        assertEquals("profile.xml", yaml.properties["qodana.recommended.profile.resource"])
    }

    @Test
    fun `parse yaml with include and exclude`() {
        val yaml = EffectiveConfig.parse(
            """
            version: "1.0"
            include:
              - name: "MyInspection"
                paths:
                  - "src/main"
            exclude:
              - name: "UnusedImport"
                paths:
                  - "src/test"
            """.trimIndent()
        )
        assertEquals(1, yaml.include.size)
        assertEquals("MyInspection", yaml.include[0].name)
        assertEquals(listOf("src/main"), yaml.include[0].paths)
        assertEquals(1, yaml.exclude.size)
        assertEquals("UnusedImport", yaml.exclude[0].name)
        assertEquals(listOf("src/test"), yaml.exclude[0].paths)
    }

    @Test
    fun `parse ignores unknown properties`() {
        val yaml = EffectiveConfig.parse(
            """
            version: "1.0"
            someUnknownField: "value"
            anotherUnknown:
              nested: true
            """.trimIndent()
        )
        assertEquals("1.0", yaml.version)
    }

    // --- merge() tests ---

    @Test
    fun `merge with null yaml returns context unchanged`() {
        val context = minimalContext()
        val result = EffectiveConfig.merge(null, context)
        assertSame(context, result)
    }

    @Test
    fun `merge yaml profile when CLI profile is null`() {
        val yaml = QodanaYaml(profile = YamlProfile(name = "Server-side"))
        val context = minimalContext()
        val result = EffectiveConfig.merge(yaml, context)
        assertNotNull(result.profile)
        assertEquals("Server-side", result.profile!!.name)
    }

    @Test
    fun `CLI profile takes precedence over yaml profile`() {
        val yaml = QodanaYaml(profile = YamlProfile(name = "Server-side"))
        val context = minimalContext().copy(
            profile = ProfileSpec(name = "CLI-profile")
        )
        val result = EffectiveConfig.merge(yaml, context)
        assertNotNull(result.profile)
        assertEquals("CLI-profile", result.profile!!.name)
    }

    @Test
    fun `properties merge with runtime winning on conflicts`() {
        val yaml = QodanaYaml(
            properties = mapOf("shared" to "from-yaml", "yaml-only" to "y")
        )
        val context = minimalContext().copy(
            runtime = RuntimeContext(
                properties = mapOf("shared" to "from-runtime", "runtime-only" to "r")
            )
        )
        val result = EffectiveConfig.merge(yaml, context)
        assertEquals("from-runtime", result.runtime.properties["shared"])
        assertEquals("y", result.runtime.properties["yaml-only"])
        assertEquals("r", result.runtime.properties["runtime-only"])
    }

    @Test
    fun `failThreshold from yaml used when runtime has none`() {
        val yaml = QodanaYaml(failThreshold = 10)
        val context = minimalContext()
        val result = EffectiveConfig.merge(yaml, context)
        assertEquals(10, result.runtime.failThreshold)
    }

    @Test
    fun `linter from CLI takes precedence over yaml`() {
        val yaml = QodanaYaml(linter = "jetbrains/qodana-jvm:latest")
        val context = minimalContext().copy(linter = "jetbrains/qodana-python:latest")
        val result = EffectiveConfig.merge(yaml, context)
        assertEquals("jetbrains/qodana-python:latest", result.linter)
    }

    // --- helper ---

    private fun minimalContext() = ScanContext(
        paths = ScanPaths(Path.of("/project"), Path.of("/results"), Path.of("/cache"), Path.of("/report")),
        auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(),
        ci = CiContext(),
        report = ReportOptions(),
        docker = DockerOptions(),
    )
}
