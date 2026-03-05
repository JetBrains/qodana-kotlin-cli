package org.jetbrains.qodana.core.model

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QodanaYamlTest {

    private val mapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    @Test
    fun `parse minimal yaml`() {
        val yaml = """
            version: "1.0"
        """.trimIndent()
        val config = mapper.readValue<QodanaYaml>(yaml)
        assertEquals("1.0", config.version)
        assertEquals("", config.profile.name)
        assertNull(config.linter)
    }

    @Test
    fun `parse yaml with profile name`() {
        val yaml = """
            version: "1.0"
            profile:
              name: "qodana.starter"
        """.trimIndent()
        val config = mapper.readValue<QodanaYaml>(yaml)
        assertEquals("qodana.starter", config.profile.name)
    }

    @Test
    fun `parse yaml with linter and exclude`() {
        val yaml = """
            version: "1.0"
            linter: jetbrains/qodana-jvm:latest
            exclude:
              - name: All
                paths:
                  - vendor
                  - testdata
              - name: CyclomaticComplexity
        """.trimIndent()
        val config = mapper.readValue<QodanaYaml>(yaml)
        assertEquals("jetbrains/qodana-jvm:latest", config.linter)
        assertEquals(2, config.exclude.size)
        assertEquals("All", config.exclude[0].name)
        assertEquals(listOf("vendor", "testdata"), config.exclude[0].paths)
        assertEquals("CyclomaticComplexity", config.exclude[1].name)
    }

    @Test
    fun `parse yaml with failure conditions`() {
        val yaml = """
            version: "1.0"
            failureConditions:
              severityThresholds:
                any: 100
                critical: 0
                high: 10
              testCoverageThresholds:
                total: 50
        """.trimIndent()
        val config = mapper.readValue<QodanaYaml>(yaml)
        assertEquals(100, config.failureConditions.severityThresholds.any)
        assertEquals(0, config.failureConditions.severityThresholds.critical)
        assertEquals(10, config.failureConditions.severityThresholds.high)
        assertEquals(50, config.failureConditions.testCoverageThresholds.total)
    }

    @Test
    fun `parse yaml with dotnet config`() {
        val yaml = """
            version: "1.0"
            dotnet:
              solution: MySolution.sln
              configuration: Release
              platform: x64
        """.trimIndent()
        val config = mapper.readValue<QodanaYaml>(yaml)
        assertNotNull(config.dotnet)
        assertEquals("MySolution.sln", config.dotnet!!.solution)
        assertEquals("Release", config.dotnet!!.configuration)
        assertEquals("x64", config.dotnet!!.platform)
    }

    @Test
    fun `parse yaml with properties`() {
        val yaml = """
            version: "1.0"
            properties:
              idea.suppress.statistics: "true"
              some.other: "value"
        """.trimIndent()
        val config = mapper.readValue<QodanaYaml>(yaml)
        assertEquals(2, config.properties.size)
        assertEquals("true", config.properties["idea.suppress.statistics"])
    }

    @Test
    fun `parse yaml with plugins`() {
        val yaml = """
            version: "1.0"
            plugins:
              - id: com.intellij.plugins.some-plugin
              - id: another-plugin
        """.trimIndent()
        val config = mapper.readValue<QodanaYaml>(yaml)
        assertEquals(2, config.plugins.size)
        assertEquals("com.intellij.plugins.some-plugin", config.plugins[0].id)
    }

    @Test
    fun `isDotNet with dotnet linter`() {
        assertTrue(QodanaYaml(linter = "jetbrains/qodana-dotnet").isDotNet())
    }

    @Test
    fun `isDotNet with cdnet linter`() {
        assertTrue(QodanaYaml(linter = "jetbrains/qodana-cdnet").isDotNet())
    }

    @Test
    fun `isDotNet with QDNET ide`() {
        assertTrue(QodanaYaml(ide = "QDNET").isDotNet())
    }

    @Test
    fun `isDotNet with jvm linter`() {
        assertFalse(QodanaYaml(linter = "jetbrains/qodana-jvm").isDotNet())
    }

    @Test
    fun `isDotNet with empty yaml`() {
        assertFalse(QodanaYaml().isDotNet())
    }

    @Test
    fun `dotnet isEmpty true when empty`() {
        assertTrue(YamlDotNet().isEmpty())
    }

    @Test
    fun `dotnet isEmpty false with solution`() {
        assertFalse(YamlDotNet(solution = "test.sln").isEmpty())
    }

    @Test
    fun `dotnet isEmpty false with project`() {
        assertFalse(YamlDotNet(project = "test.csproj").isEmpty())
    }

    @Test
    fun `sorted sorts includes and excludes`() {
        val yaml = QodanaYaml(
            include = listOf(
                InspectScope(name = "Zebra"),
                InspectScope(name = "Alpha"),
                InspectScope(name = "Beta"),
            ),
            exclude = listOf(
                InspectScope(name = "Zulu"),
                InspectScope(name = "Alpha"),
            ),
            licenseRules = listOf(
                YamlLicenseRule(
                    keys = listOf("zlib", "apache-2.0", "MIT"),
                    allowed = listOf("GPL-3.0", "BSD-3-Clause"),
                    prohibited = listOf("Proprietary", "Commercial"),
                ),
            ),
        )
        val sorted = yaml.sorted()
        assertEquals("Alpha", sorted.include[0].name)
        assertEquals("Beta", sorted.include[1].name)
        assertEquals("Zebra", sorted.include[2].name)
        assertEquals("Alpha", sorted.exclude[0].name)
        assertEquals("Zulu", sorted.exclude[1].name)
        assertEquals(listOf("MIT", "apache-2.0", "zlib"), sorted.licenseRules[0].keys)
        assertEquals(listOf("BSD-3-Clause", "GPL-3.0"), sorted.licenseRules[0].allowed)
        assertEquals(listOf("Commercial", "Proprietary"), sorted.licenseRules[0].prohibited)
    }

    @Test
    fun `default values are correct`() {
        val config = QodanaYaml()
        assertEquals("1.0", config.version)
        assertEquals("", config.profile.name)
        assertEquals("", config.profile.path)
        assertNull(config.linter)
        assertNull(config.failThreshold)
        assertEquals("default", config.script.name)
        assertEquals(100, config.maxRuntimeNotifications)
        assertEquals(false, config.failOnErrorNotification)
    }
}
