package org.jetbrains.qodana.engine.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScanContextUtilsTest {

    // --- determineRunScenario ---

    @Test
    fun `scenario fullHistory takes priority`() {
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = true, script = "default", startHash = "abc", forceLocalChanges = false,
            isContainer = false, reversePrAnalysis = false,
        )
        assertIs<RunScenario.FullHistory>(scenario)
    }

    @Test
    fun `scenario no startHash returns Default`() {
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = false, script = "default", startHash = null, forceLocalChanges = false,
            isContainer = false, reversePrAnalysis = false,
        )
        assertIs<RunScenario.Default>(scenario)
    }

    @Test
    fun `scenario empty startHash returns Default`() {
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = false, script = "default", startHash = "", forceLocalChanges = false,
            isContainer = false, reversePrAnalysis = false,
        )
        assertIs<RunScenario.Default>(scenario)
    }

    @Test
    fun `scenario forceLocalChanges returns LocalChanges`() {
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = false, script = "default", startHash = "abc123", forceLocalChanges = true,
            isContainer = false, reversePrAnalysis = false,
        )
        assertIs<RunScenario.LocalChanges>(scenario)
        assertEquals("abc123", scenario.diffStart)
    }

    @Test
    fun `scenario container overrides to Default`() {
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = false, script = "default", startHash = "abc", forceLocalChanges = false,
            isContainer = true, reversePrAnalysis = false,
        )
        assertIs<RunScenario.Default>(scenario)
    }

    @Test
    fun `scenario reversePrAnalysis returns ReverseScoped`() {
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = false, script = "default", startHash = "main", forceLocalChanges = false,
            isContainer = false, reversePrAnalysis = true,
        )
        assertIs<RunScenario.ReverseScoped>(scenario)
        assertEquals("main", scenario.targetBranch)
    }

    @Test
    fun `scenario default with startHash returns Scoped`() {
        val scenario = ScanContextUtils.determineRunScenario(
            fullHistory = false, script = "default", startHash = "develop", forceLocalChanges = false,
            isContainer = false, reversePrAnalysis = false,
        )
        assertIs<RunScenario.Scoped>(scenario)
        assertEquals("develop", scenario.targetBranch)
    }

    // --- getAnalysisTimeout ---

    @Test
    fun `timeout positive millis`() {
        assertEquals(Duration.ofMillis(60000), ScanContextUtils.getAnalysisTimeout(60000))
    }

    @Test
    fun `timeout zero returns max`() {
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), ScanContextUtils.getAnalysisTimeout(0))
    }

    @Test
    fun `timeout negative returns max`() {
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), ScanContextUtils.getAnalysisTimeout(-1))
    }

    // --- vmOptionsPath ---

    @Test
    fun `vmOptionsPath resolves correctly`() {
        assertEquals(Path.of("/config/ide.vmoptions"), ScanContextUtils.vmOptionsPath(Path.of("/config")))
    }

    @Test
    fun `installPluginsVmOptionsPath resolves correctly`() {
        assertEquals(
            Path.of("/config/install_plugins.vmoptions"),
            ScanContextUtils.installPluginsVmOptionsPath(Path.of("/config")),
        )
    }

    // --- localQodanaYamlExists ---

    @Test
    fun `localQodanaYamlExists true when yaml exists`(@TempDir dir: Path) {
        dir.resolve("qodana.yaml").createFile()
        assertTrue(ScanContextUtils.localQodanaYamlExists(dir))
    }

    @Test
    fun `localQodanaYamlExists true when yml exists`(@TempDir dir: Path) {
        dir.resolve("qodana.yml").createFile()
        assertTrue(ScanContextUtils.localQodanaYamlExists(dir))
    }

    @Test
    fun `localQodanaYamlExists false when missing`(@TempDir dir: Path) {
        assertFalse(ScanContextUtils.localQodanaYamlExists(dir))
    }

    // --- projectDirRelativeToRepoRoot ---

    @Test
    fun `relative path subdirectory`() {
        assertEquals("sub1/sub2", ScanContextUtils.projectDirRelativeToRepoRoot(
            Path.of("/repo/sub1/sub2"), Path.of("/repo"),
        ))
    }

    @Test
    fun `relative path same directory returns dot`() {
        assertEquals(".", ScanContextUtils.projectDirRelativeToRepoRoot(
            Path.of("/repo"), Path.of("/repo"),
        ))
    }

    @Test
    fun `relative path single level`() {
        assertEquals("project", ScanContextUtils.projectDirRelativeToRepoRoot(
            Path.of("/repo/project"), Path.of("/repo"),
        ))
    }

    // --- parsePropertiesAndFlags ---

    @Test
    fun `parse properties and flags`() {
        val input = listOf("key1=val1", "key2=val2", "-flag1", "-flag2", "key3=val=with=equals")
        val (props, flags) = ScanContextUtils.parsePropertiesAndFlags(input)
        assertEquals(mapOf("key1" to "val1", "key2" to "val2", "key3" to "val=with=equals"), props)
        assertEquals(listOf("-flag1", "-flag2"), flags)
    }

    @Test
    fun `parse empty list`() {
        val (props, flags) = ScanContextUtils.parsePropertiesAndFlags(emptyList())
        assertTrue(props.isEmpty())
        assertTrue(flags.isEmpty())
    }

    @Test
    fun `parse only flags`() {
        val (props, flags) = ScanContextUtils.parsePropertiesAndFlags(listOf("-a", "-b"))
        assertTrue(props.isEmpty())
        assertEquals(listOf("-a", "-b"), flags)
    }

    @Test
    fun `parse only properties`() {
        val (props, flags) = ScanContextUtils.parsePropertiesAndFlags(listOf("x=1", "y=2"))
        assertEquals(mapOf("x" to "1", "y" to "2"), props)
        assertTrue(flags.isEmpty())
    }

    // --- isScopedScenario ---

    @Test
    fun `isScopedScenario true for Scoped`() {
        assertTrue(ScanContextUtils.isScopedScenario(RunScenario.Scoped("main")))
    }

    @Test
    fun `isScopedScenario true for ReverseScoped`() {
        assertTrue(ScanContextUtils.isScopedScenario(RunScenario.ReverseScoped("main")))
    }

    @Test
    fun `isScopedScenario false for Default`() {
        assertFalse(ScanContextUtils.isScopedScenario(RunScenario.Default))
    }

    @Test
    fun `isScopedScenario false for FullHistory`() {
        assertFalse(ScanContextUtils.isScopedScenario(RunScenario.FullHistory))
    }

    @Test
    fun `isScopedScenario false for LocalChanges`() {
        assertFalse(ScanContextUtils.isScopedScenario(RunScenario.LocalChanges()))
    }
}
