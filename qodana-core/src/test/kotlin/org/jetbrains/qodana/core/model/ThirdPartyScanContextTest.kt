package org.jetbrains.qodana.core.model

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThirdPartyScanContextTest {
    private fun testPaths() =
        ScanPaths(
            projectDir = Path.of("/project"),
            resultsDir = Path.of("/results"),
            cacheDir = Path.of("/cache"),
            reportDir = Path.of("/report"),
        )

    @Test
    fun `context stores all fields`() {
        val ctx =
            ThirdPartyScanContext(
                paths = testPaths(),
                yaml = null,
                linterDir = Path.of("/linter"),
                logDir = Path.of("/logs"),
                noBuild = true,
                noStatistics = true,
                configurationName = "Release",
                platformName = "x64",
                solutionPath = "solution.sln",
                projectPath = "project.csproj",
                compileCommands = "compile_commands.json",
                clangArgs = "-Wall",
                properties = listOf("prop=val"),
                customTools = mapOf("clt" to Path.of("/usr/bin/clt")),
            )

        assertEquals(Path.of("/project"), ctx.paths.projectDir)
        assertEquals(Path.of("/results"), ctx.paths.resultsDir)
        assertEquals(Path.of("/cache"), ctx.paths.cacheDir)
        assertEquals(Path.of("/report"), ctx.paths.reportDir)
        assertEquals(Path.of("/logs"), ctx.logDir)
        assertEquals(Path.of("/linter"), ctx.linterDir)
        assertTrue(ctx.noBuild)
        assertTrue(ctx.noStatistics)
        assertEquals("Release", ctx.configurationName)
        assertEquals("x64", ctx.platformName)
        assertEquals("solution.sln", ctx.solutionPath)
        assertEquals("project.csproj", ctx.projectPath)
        assertEquals("compile_commands.json", ctx.compileCommands)
        assertEquals("-Wall", ctx.clangArgs)
        assertEquals(listOf("prop=val"), ctx.properties)
        assertEquals(Path.of("/usr/bin/clt"), ctx.customTools["clt"])
    }

    @Test
    fun `defaults are sensible`() {
        val ctx =
            ThirdPartyScanContext(
                paths = testPaths(),
                yaml = null,
                linterDir = Path.of("/linter"),
                logDir = Path.of("/logs"),
            )

        assertFalse(ctx.noBuild)
        assertFalse(ctx.noStatistics)
        assertNull(ctx.configurationName)
        assertNull(ctx.platformName)
        assertNull(ctx.solutionPath)
        assertNull(ctx.projectPath)
        assertNull(ctx.compileCommands)
        assertEquals("", ctx.clangArgs)
        assertEquals(emptyList(), ctx.properties)
        assertEquals(emptyMap(), ctx.customTools)
    }

    @Test
    fun `custom tools lookup`() {
        val ctx =
            ThirdPartyScanContext(
                paths = testPaths(),
                yaml = null,
                linterDir = Path.of("/linter"),
                logDir = Path.of("/logs"),
                customTools =
                    mapOf(
                        "clang" to Path.of("/usr/bin/clang-15"),
                        "clt" to Path.of("/tools/inspectcode.dll"),
                    ),
            )

        assertEquals(Path.of("/usr/bin/clang-15"), ctx.customTools["clang"])
        assertEquals(Path.of("/tools/inspectcode.dll"), ctx.customTools["clt"])
        assertNull(ctx.customTools["nonexistent"])
    }

    @Test
    fun `properties reflect source list updates`() {
        val props = mutableListOf("a=1", "b=2")
        val ctx =
            ThirdPartyScanContext(
                paths = testPaths(),
                yaml = null,
                linterDir = Path.of("/linter"),
                logDir = Path.of("/logs"),
                properties = props,
            )

        assertEquals(listOf("a=1", "b=2"), ctx.properties)

        // ThirdPartyScanContext keeps the same list instance reference.
        props.add("c=3")
        assertEquals(listOf("a=1", "b=2", "c=3"), ctx.properties)
    }

    @Test
    fun `data class copy works`() {
        val ctx =
            ThirdPartyScanContext(
                paths = testPaths(),
                yaml = null,
                linterDir = Path.of("/linter"),
                logDir = Path.of("/logs"),
            )

        val updated = ctx.copy(noBuild = true, solutionPath = "app.sln")
        assertTrue(updated.noBuild)
        assertEquals("app.sln", updated.solutionPath)
        assertFalse(ctx.noBuild) // original unchanged
    }
}
