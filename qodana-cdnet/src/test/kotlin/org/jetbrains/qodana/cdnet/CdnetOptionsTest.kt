package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.model.QodanaYaml
import org.jetbrains.qodana.core.model.ScanPaths
import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import org.jetbrains.qodana.core.model.YamlDotNet
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdnetOptionsTest {
    private val defaultPaths =
        ScanPaths(
            projectDir = Path.of("/project"),
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        )

    private fun context(
        yaml: QodanaYaml? = null,
        noBuild: Boolean = false,
        noStatistics: Boolean = false,
        configurationName: String? = null,
        platformName: String? = null,
        solutionPath: String? = null,
        projectPath: String? = null,
        properties: List<String> = emptyList(),
        customTools: Map<String, Path> = emptyMap(),
    ) = ThirdPartyScanContext(
        paths = defaultPaths,
        yaml = yaml,
        linterDir = Path.of("/linter"),
        logDir = Path.of("/log"),
        noBuild = noBuild,
        noStatistics = noStatistics,
        configurationName = configurationName,
        platformName = platformName,
        solutionPath = solutionPath,
        projectPath = projectPath,
        properties = properties,
        customTools = customTools,
    )

    // --- getSolutionOrProject tests ---

    @Test
    fun `CLI solution wins over yaml solution`() {
        val ctx =
            context(
                solutionPath = "cli.sln",
                yaml = QodanaYaml(dotnet = YamlDotNet(solution = "yaml.sln")),
            )
        assertEquals("cli.sln", CdnetOptions.getSolutionOrProject(ctx))
    }

    @Test
    fun `CLI project wins over yaml project`() {
        val ctx =
            context(
                projectPath = "cli.csproj",
                yaml = QodanaYaml(dotnet = YamlDotNet(project = "yaml.csproj")),
            )
        assertEquals("cli.csproj", CdnetOptions.getSolutionOrProject(ctx))
    }

    @Test
    fun `falls back to yaml solution when CLI not specified`() {
        val ctx =
            context(
                yaml = QodanaYaml(dotnet = YamlDotNet(solution = "yaml.sln")),
            )
        assertEquals("yaml.sln", CdnetOptions.getSolutionOrProject(ctx))
    }

    @Test
    fun `falls back to yaml project when CLI not specified`() {
        val ctx =
            context(
                yaml = QodanaYaml(dotnet = YamlDotNet(project = "yaml.csproj")),
            )
        assertEquals("yaml.csproj", CdnetOptions.getSolutionOrProject(ctx))
    }

    @Test
    fun `returns null when nothing specified`() {
        val ctx = context()
        assertNull(CdnetOptions.getSolutionOrProject(ctx))
    }

    // --- computeArgs tests ---

    @Test
    fun `basic args invoke the clt launcher with the solution path`() {
        val cltPath = Path.of("/tools/jb/inspectcode.dll")
        val ctx =
            context(
                solutionPath = "My.sln",
                customTools = mapOf("clt" to cltPath),
            )
        val args = CdnetOptions.computeArgs(ctx)

        assertEquals(ctx.customTools["clt"].toString(), args[0])
        assertEquals("My.sln", args[1])
        assertTrue("dotnet" !in args)
        assertTrue("inspectcode" !in args)
    }

    @Test
    fun `framework properties are filtered out`() {
        val cltPath = Path.of("/tools/clt")
        val ctx =
            context(
                solutionPath = "App.sln",
                customTools = mapOf("clt" to cltPath),
                properties =
                    listOf(
                        "log.level=debug",
                        "idea.home=/home",
                        "qodana.token=abc",
                        "jetbrains.build=123",
                        "MyProp=value",
                    ),
            )
        val args = CdnetOptions.computeArgs(ctx)
        val propsArg = args.find { it.startsWith("--properties:") }!!

        assertTrue(propsArg.contains("MyProp=value"), "User property should be included")
        assertTrue(!propsArg.contains("log.level"), "log.* property should be filtered")
        assertTrue(!propsArg.contains("idea.home"), "idea.* property should be filtered")
        assertTrue(!propsArg.contains("qodana.token"), "qodana.* property should be filtered")
        assertTrue(!propsArg.contains("jetbrains.build"), "jetbrains.* property should be filtered")
    }

    @Test
    fun `CLI configuration overrides yaml configuration`() {
        val cltPath = Path.of("/tools/clt")
        val ctx =
            context(
                solutionPath = "App.sln",
                customTools = mapOf("clt" to cltPath),
                configurationName = "Release",
                yaml = QodanaYaml(dotnet = YamlDotNet(configuration = "Debug")),
            )
        val args = CdnetOptions.computeArgs(ctx)
        val propsArg = args.find { it.startsWith("--properties:") }!!

        assertTrue(propsArg.contains("Configuration=Release"))
        assertTrue(!propsArg.contains("Debug"))
    }

    @Test
    fun `CLI platform overrides yaml platform`() {
        val cltPath = Path.of("/tools/clt")
        val ctx =
            context(
                solutionPath = "App.sln",
                customTools = mapOf("clt" to cltPath),
                platformName = "x64",
                yaml = QodanaYaml(dotnet = YamlDotNet(platform = "AnyCPU")),
            )
        val args = CdnetOptions.computeArgs(ctx)
        val propsArg = args.find { it.startsWith("--properties:") }!!

        assertTrue(propsArg.contains("Platform=x64"))
        assertTrue(!propsArg.contains("AnyCPU"))
    }

    @Test
    fun `noStatistics adds telemetry-optout flag`() {
        val cltPath = Path.of("/tools/clt")
        val ctx =
            context(
                solutionPath = "App.sln",
                customTools = mapOf("clt" to cltPath),
                noStatistics = true,
            )
        val args = CdnetOptions.computeArgs(ctx)
        assertTrue(args.contains("--telemetry-optout"))
    }

    @Test
    fun `noBuild adds no-build flag`() {
        val cltPath = Path.of("/tools/clt")
        val ctx =
            context(
                solutionPath = "App.sln",
                customTools = mapOf("clt" to cltPath),
                noBuild = true,
            )
        val args = CdnetOptions.computeArgs(ctx)
        assertTrue(args.contains("--no-build"))
    }

    @Test
    fun `error when no solution or project specified`() {
        val cltPath = Path.of("/tools/clt")
        val ctx =
            context(
                customTools = mapOf("clt" to cltPath),
            )
        val ex =
            assertFailsWith<IllegalStateException> {
                CdnetOptions.computeArgs(ctx)
            }
        assertTrue(ex.message!!.contains("Solution/project"))
    }
}
