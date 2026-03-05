package org.jetbrains.qodana.app.scan

import org.jetbrains.qodana.core.model.*
import java.nio.file.Path
import kotlin.test.*

class IdeArgBuilderTest {

    @Test
    fun `minimal context produces inspect qodana projectDir resultsDir`() {
        val args = IdeArgBuilder.build(minimalContext())
        assertEquals("inspect", args.first())
        assertEquals("qodana", args[1])
        assertEquals("/project", args[args.size - 2])
        assertEquals("/results", args.last())
    }

    @Test
    fun `profile name is included`() {
        val context = minimalContext().copy(
            profile = ProfileSpec(name = "myProfile")
        )
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--profile-name")
        assertTrue(idx >= 0, "Expected --profile-name in args")
        assertEquals("myProfile", args[idx + 1])
    }

    @Test
    fun `baseline path is included`() {
        val context = minimalContext().copy(
            report = ReportOptions(baselinePath = Path.of("/baselines/baseline.sarif.json"))
        )
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--baseline")
        assertTrue(idx >= 0, "Expected --baseline in args")
        assertEquals("/baselines/baseline.sarif.json", args[idx + 1])
    }

    @Test
    fun `apply fixes flag is included`() {
        val context = minimalContext().copy(
            runtime = RuntimeContext(applyFixes = true)
        )
        val args = IdeArgBuilder.build(context)
        assertTrue(args.contains("--apply-fixes"), "Expected --apply-fixes in args")
    }

    @Test
    fun `dotnet solution is included`() {
        val yaml = QodanaYaml(dotnet = YamlDotNet(solution = "MySolution.sln"))
        val context = minimalContext().copy(yaml = yaml)
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--solution")
        assertTrue(idx >= 0, "Expected --solution in args")
        assertEquals("MySolution.sln", args[idx + 1])
    }

    @Test
    fun `properties are included`() {
        val context = minimalContext().copy(
            runtime = RuntimeContext(properties = mapOf("key" to "value"))
        )
        val args = IdeArgBuilder.build(context)
        assertTrue(args.contains("--property=key=value"), "Expected --property=key=value in args")
    }

    @Test
    fun `diff start and end are included`() {
        val context = minimalContext().copy(
            runtime = RuntimeContext(diffStart = "abc123", diffEnd = "def456")
        )
        val args = IdeArgBuilder.build(context)
        val startIdx = args.indexOf("--diff-start")
        assertTrue(startIdx >= 0, "Expected --diff-start in args")
        assertEquals("abc123", args[startIdx + 1])
        val endIdx = args.indexOf("--diff-end")
        assertTrue(endIdx >= 0, "Expected --diff-end in args")
        assertEquals("def456", args[endIdx + 1])
    }

    @Test
    fun `fail threshold is included`() {
        val context = minimalContext().copy(
            runtime = RuntimeContext(failThreshold = 42)
        )
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--fail-threshold")
        assertTrue(idx >= 0, "Expected --fail-threshold in args")
        assertEquals("42", args[idx + 1])
    }

    @Test
    fun `code climate output format is included`() {
        val context = minimalContext().copy(
            report = ReportOptions(
                outputFormats = setOf(ReportOptions.OutputFormat.CODE_CLIMATE)
            )
        )
        val args = IdeArgBuilder.build(context)
        assertTrue(args.contains("--code-climate"), "Expected --code-climate in args")
    }

    @Test
    fun `multiple options combined correctly`() {
        val yaml = QodanaYaml(dotnet = YamlDotNet(solution = "App.sln"))
        val context = minimalContext().copy(
            profile = ProfileSpec(name = "Default"),
            runtime = RuntimeContext(
                applyFixes = true,
                failThreshold = 5,
                diffStart = "aaa",
                properties = mapOf("p1" to "v1"),
            ),
            report = ReportOptions(
                baselinePath = Path.of("/baseline.sarif.json"),
                outputFormats = setOf(
                    ReportOptions.OutputFormat.SARIF,
                    ReportOptions.OutputFormat.CODE_CLIMATE,
                ),
            ),
            yaml = yaml,
        )
        val args = IdeArgBuilder.build(context)

        // Positional bookends
        assertEquals("inspect", args.first())
        assertEquals("qodana", args[1])
        assertEquals("/project", args[args.size - 2])
        assertEquals("/results", args.last())

        // Individual flags
        assertTrue(args.contains("--profile-name"))
        assertTrue(args.contains("--apply-fixes"))
        assertTrue(args.contains("--code-climate"))
        assertTrue(args.contains("--property=p1=v1"))
        assertTrue(args.contains("--solution"))
        assertTrue(args.contains("--baseline"))
        assertTrue(args.contains("--fail-threshold"))
        assertTrue(args.contains("--diff-start"))
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
