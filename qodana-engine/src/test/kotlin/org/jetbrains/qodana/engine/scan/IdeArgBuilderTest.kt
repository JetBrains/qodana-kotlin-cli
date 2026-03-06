package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for [IdeArgBuilder], matching Go's `GetIdeArgs()`.
 * Note: IdeArgBuilder returns only the qodana sub-command args.
 * It does NOT include the IDE binary, "inspect"/"qodana" subcommands,
 * or projectDir/resultsDir — those are added by NativeScan.getIdeRunCommand().
 */
class IdeArgBuilderTest {

    private val product253 = IdeProduct(
        name = "GoLand", ideCode = "GO", code = "QDGO",
        version = "2025.3", baseScriptName = "goland",
        ideScript = "/opt/ide/bin/goland", build = "253.12345",
        home = "/opt/ide", isEap = false,
    )

    @Test
    fun `minimal context produces empty args`() {
        val args = IdeArgBuilder.build(nativeContext())
        assertTrue(args.isEmpty(), "No flags expected for minimal context: $args")
    }

    @Test
    fun `profile name is included`() {
        val context = nativeContext().copy(profile = ProfileSpec(name = "myProfile"))
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--profile-name")
        assertTrue(idx >= 0, "Expected --profile-name in args")
        assertEquals("myProfile", args[idx + 1])
    }

    @Test
    fun `profile path is included`() {
        val context = nativeContext().copy(profile = ProfileSpec(path = "/profiles/custom.xml"))
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--profile-path")
        assertTrue(idx >= 0, "Expected --profile-path in args")
        assertEquals("/profiles/custom.xml", args[idx + 1])
    }

    @Test
    fun `baseline path is included`() {
        val context = nativeContext().copy(
            report = ReportOptions(baselinePath = Path.of("/baselines/baseline.sarif.json"))
        )
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--baseline")
        assertTrue(idx >= 0, "Expected --baseline in args")
        assertEquals("/baselines/baseline.sarif.json", args[idx + 1])
    }

    @Test
    fun `baseline-include-absent flag is included`() {
        val context = nativeContext().copy(
            report = ReportOptions(baselinePath = Path.of("/b.sarif"), baselineIncludeAbsent = true)
        )
        val args = IdeArgBuilder.build(context)
        assertTrue(args.contains("--baseline-include-absent"))
    }

    @Test
    fun `no baseline-include-absent when false`() {
        val args = IdeArgBuilder.build(nativeContext())
        assertFalse(args.contains("--baseline-include-absent"))
    }

    @Test
    fun `fail threshold is included`() {
        val context = nativeContext().copy(runtime = RuntimeContext(failThreshold = 42))
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--fail-threshold")
        assertTrue(idx >= 0, "Expected --fail-threshold in args")
        assertEquals("42", args[idx + 1])
    }

    @Test
    fun `apply fixes on 233+ native uses --apply-fixes`() {
        val context = nativeContext().copy(runtime = RuntimeContext(applyFixes = true))
        val args = IdeArgBuilder.build(context, product253)
        assertTrue(args.contains("--apply-fixes"), "Expected --apply-fixes in args: $args")
    }

    @Test
    fun `cleanup on 233+ native uses --cleanup`() {
        val context = nativeContext().copy(runtime = RuntimeContext(cleanup = true))
        val args = IdeArgBuilder.build(context, product253)
        assertTrue(args.contains("--cleanup"), "Expected --cleanup in args: $args")
    }

    @Test
    fun `only-directory is included`() {
        val context = nativeContext().copy(runtime = RuntimeContext(onlyDirectory = "src/main"))
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--only-directory")
        assertTrue(idx >= 0)
        assertEquals("src/main", args[idx + 1])
    }

    @Test
    fun `script non-default is included`() {
        val context = nativeContext().copy(runtime = RuntimeContext(script = "local-changes"))
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--script")
        assertTrue(idx >= 0)
        assertEquals("local-changes", args[idx + 1])
    }

    @Test
    fun `script default is not included`() {
        val args = IdeArgBuilder.build(nativeContext())
        assertFalse(args.contains("--script"))
    }

    @Test
    fun `blank custom config name is ignored`() {
        val context = nativeContext().copy(runtime = RuntimeContext(customConfigName = ""))
        val args = IdeArgBuilder.build(context)
        assertFalse(args.contains("--config"))
    }

    // --- Container-specific args ---

    @Test
    fun `container mode includes properties`() {
        val context = containerContext().copy(
            runtime = RuntimeContext(properties = mapOf("key" to "value"))
        )
        val args = IdeArgBuilder.build(context)
        assertTrue(args.contains("--property=key=value"), "Expected --property=key=value in args")
    }

    @Test
    fun `container mode includes diff-start and diff-end`() {
        val context = containerContext().copy(
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
    fun `container mode includes analysis-id`() {
        val context = containerContext().copy(
            runtime = RuntimeContext(analysisId = "analysis-123")
        )
        val args = IdeArgBuilder.build(context)
        val idx = args.indexOf("--analysis-id")
        assertTrue(idx >= 0)
        assertEquals("analysis-123", args[idx + 1])
    }

    @Test
    fun `container mode includes code-climate`() {
        val context = containerContext().copy(
            report = ReportOptions(outputFormats = setOf(ReportOptions.OutputFormat.CODE_CLIMATE))
        )
        val args = IdeArgBuilder.build(context)
        assertTrue(args.contains("--code-climate"), "Expected --code-climate in args")
    }

    @Test
    fun `container mode includes save-report`() {
        val context = containerContext().copy(
            docker = DockerOptions(image = "jetbrains/qodana-jvm:latest"),
            report = ReportOptions(saveReport = true),
        )
        val args = IdeArgBuilder.build(context)
        assertTrue(args.contains("--save-report"))
    }

    @Test
    fun `native mode on 251+ includes config-dir`() {
        val context = nativeContext().copy(
            runtime = RuntimeContext(effectiveConfigDir = Path.of("/tmp/config"))
        )
        val args = IdeArgBuilder.build(context, product253)
        val idx = args.indexOf("--config-dir")
        assertTrue(idx >= 0, "Expected --config-dir for 251+ native: $args")
        assertEquals("/tmp/config", args[idx + 1])
    }

    // --- helpers ---

    private fun nativeContext() = ScanContext(
        paths = ScanPaths(
            projectDir = Path.of("/project"),
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        ),
        auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(),
        ci = CiContext(),
        report = ReportOptions(),
        docker = DockerOptions(),
        nativeMode = true,
    )

    private fun containerContext() = ScanContext(
        paths = ScanPaths(
            projectDir = Path.of("/project"),
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        ),
        auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(),
        ci = CiContext(),
        report = ReportOptions(),
        docker = DockerOptions(image = "jetbrains/qodana-jvm:latest"),
        nativeMode = false,
    )
}
