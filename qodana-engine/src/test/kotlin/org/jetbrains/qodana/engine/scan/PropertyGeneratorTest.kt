package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.model.*
import org.jetbrains.qodana.engine.model.*
import org.jetbrains.qodana.engine.startup.DeviceId
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropertyGeneratorTest {

    private fun testContext(
        runtimeProperties: Map<String, String> = emptyMap(),
        yamlProperties: Map<String, String> = emptyMap(),
        jvmDebugPort: Int? = null,
        analysisId: String? = null,
        coverageDir: Path? = null,
        repositoryRoot: Path = Path.of("/project"),
        ciRemoteUrl: String? = null,
    ) = ScanContext(
        paths = ScanPaths(
            projectDir = Path.of("/project"),
            repositoryRoot = repositoryRoot,
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        ),
        auth = AuthContext(token = null, endpoint = "https://qodana.cloud"),
        runtime = RuntimeContext(
            properties = runtimeProperties,
            jvmDebugPort = jvmDebugPort,
            analysisId = analysisId,
            coverageDir = coverageDir,
        ),
        ci = CiContext(remoteUrl = ciRemoteUrl),
        report = ReportOptions(),
        docker = DockerOptions(),
        yaml = if (yamlProperties.isNotEmpty()) QodanaYaml(properties = yamlProperties) else null,
    )

    @Test
    fun `generates system path properties`() {
        val props = PropertyGenerator.generateIdeaProperties(testContext())
        assertContains(props, "idea.system.path=")
        assertContains(props, "idea.config.path=")
        assertContains(props, "idea.plugins.path=")
        assertContains(props, "idea.log.path=")
    }

    @Test
    fun `generates headless properties`() {
        val props = PropertyGenerator.generateIdeaProperties(testContext())
        assertContains(props, "idea.is.internal=false")
        assertContains(props, "idea.headless.enable.statistics=false")
    }

    @Test
    fun `merges yaml and runtime properties`() {
        val props = PropertyGenerator.generateIdeaProperties(testContext(
            yamlProperties = mapOf("yaml.prop" to "yaml-val"),
            runtimeProperties = mapOf("runtime.prop" to "runtime-val"),
        ))
        assertContains(props, "yaml.prop=yaml-val")
        assertContains(props, "runtime.prop=runtime-val")
    }

    @Test
    fun `runtime properties override yaml on conflict`() {
        val props = PropertyGenerator.generateIdeaProperties(testContext(
            yamlProperties = mapOf("shared.key" to "yaml-val"),
            runtimeProperties = mapOf("shared.key" to "runtime-wins"),
        ))
        assertContains(props, "shared.key=runtime-wins")
    }

    @Test
    fun `device id and salt are generated from remote url`() {
        val props = PropertyGenerator.generateIdeaProperties(testContext(ciRemoteUrl = "https://github.com/acme/repo.git"))
        val expected = DeviceId.getDeviceIdSalt(remoteUrl = "https://github.com/acme/repo.git")
        assertContains(props, "idea.headless.statistics.device.id=${expected.deviceId}")
        assertContains(props, "idea.headless.statistics.salt=${expected.salt}")
    }

    @Test
    fun `analysis and coverage properties are included when configured`() {
        val props = PropertyGenerator.generateIdeaProperties(
            testContext(
                analysisId = "analysis-guid-123",
                coverageDir = Path.of("/project/.qodana/code-coverage"),
            )
        )
        assertContains(props, "qodana.automation.guid=analysis-guid-123")
        assertContains(props, "qodana.coverage.input=/project/.qodana/code-coverage")
    }

    @Test
    fun `project path relative to repository root is included`() {
        val props = PropertyGenerator.generateIdeaProperties(
            testContext(repositoryRoot = Path.of("/"))
        )
        assertContains(props, "qodana.path.to.project.dir.from.project.root=project")
    }

    @Test
    fun `vm options contain memory defaults`() {
        val vm = PropertyGenerator.generateVmOptions(testContext())
        assertContains(vm, "-Xmx2048m")
        assertContains(vm, "-Xms256m")
        assertContains(vm, "-XX:+UseG1GC")
        assertContains(vm, "-XX:+HeapDumpOnOutOfMemoryError")
    }

    @Test
    fun `vm options contain headless AWT`() {
        val vm = PropertyGenerator.generateVmOptions(testContext())
        assertContains(vm, "-Djava.awt.headless=true")
    }

    @Test
    fun `vm options include debug port when set`() {
        val vm = PropertyGenerator.generateVmOptions(testContext(jvmDebugPort = 5005))
        assertContains(vm, "address=*:5005")
        assertContains(vm, "jdwp")
    }

    @Test
    fun `vm options no debug when port is null`() {
        val vm = PropertyGenerator.generateVmOptions(testContext())
        assertFalse(vm.contains("jdwp"))
    }

    @Test
    fun `writeTo writes both files`() {
        val files = mutableMapOf<String, String>()
        PropertyGenerator.writeTo(testContext(), Path.of("/config")) { path, content ->
            files[path.fileName.toString()] = content
        }
        assertTrue("idea.properties" in files)
        assertTrue("idea64.vmoptions" in files)
    }
}
