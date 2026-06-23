package org.jetbrains.qodana.clang

import org.jetbrains.qodana.core.model.InspectScope
import org.jetbrains.qodana.core.model.QodanaYaml
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClangConfigTest {
    private val defaults = ClangConfig.defaultChecks

    private fun projectUnder(root: Path): Path = Files.createDirectories(root.resolve("g").resolve("p").resolve("project"))

    private fun inc(vararg names: String) = names.map { InspectScope(name = it) }

    private fun process(
        dir: Path,
        root: Path,
        yaml: QodanaYaml? = null,
    ) = ClangConfig.processConfig(yaml, dir, searchRoot = root)

    private fun writeConfig(
        dir: Path,
        name: String = ".clang-tidy",
    ) {
        Files.writeString(dir.resolve(name), "Checks: '-*,bugprone-*'\n")
    }

    // No config: curated defaults are the base; includes/excludes layer on top.

    @Test
    fun `no config returns curated defaults`(
        @TempDir root: Path,
    ) {
        val r = process(projectUnder(root), root)
        assertEquals("--checks=$defaults", r.checks)
        assertNull(r.configFile)
    }

    @Test
    fun `default checks never enable llvmlibc fuchsia altera`(
        @TempDir root: Path,
    ) {
        val checks = process(projectUnder(root), root).checks
        assertTrue(checks.startsWith("--checks=-*,"))
        assertTrue("llvmlibc" !in checks && "fuchsia" !in checks && "altera" !in checks)
    }

    @Test
    fun `no config only includes layer on defaults`(
        @TempDir root: Path,
    ) {
        assertEquals(
            "--checks=$defaults,bugprone-*",
            process(projectUnder(root), root, QodanaYaml(include = inc("bugprone-*"))).checks,
        )
    }

    @Test
    fun `no config multiple includes layer on defaults`(
        @TempDir root: Path,
    ) {
        assertEquals(
            "--checks=$defaults,bugprone-*,performance-*",
            process(projectUnder(root), root, QodanaYaml(include = inc("bugprone-*", "performance-*"))).checks,
        )
    }

    @Test
    fun `no config only excludes layer on defaults`(
        @TempDir root: Path,
    ) {
        assertEquals(
            "--checks=$defaults,-clang-analyzer-cplusplus.NewDeleteLeaks",
            process(projectUnder(root), root, QodanaYaml(exclude = inc("clang-analyzer-cplusplus.NewDeleteLeaks"))).checks,
        )
    }

    @Test
    fun `no config multiple excludes layer on defaults`(
        @TempDir root: Path,
    ) {
        val r =
            process(
                projectUnder(root),
                root,
                QodanaYaml(exclude = inc("clang-analyzer-cplusplus.NewDeleteLeaks", "clang-analyzer-core.NullDereference")),
            )
        assertEquals(
            "--checks=$defaults,-clang-analyzer-cplusplus.NewDeleteLeaks,-clang-analyzer-core.NullDereference",
            r.checks,
        )
    }

    @Test
    fun `no config both includes and excludes layer on defaults`(
        @TempDir root: Path,
    ) {
        val r =
            process(
                projectUnder(root),
                root,
                QodanaYaml(include = inc("bugprone-*"), exclude = inc("bugprone-argument-comment")),
            )
        assertEquals("--checks=$defaults,bugprone-*,-bugprone-argument-comment", r.checks)
    }

    // Filtering without config: clion- and quoted names are dropped from both include and exclude loops.

    @Test
    fun `clion includes filtered then defaults`(
        @TempDir root: Path,
    ) {
        assertEquals(
            "--checks=$defaults",
            process(projectUnder(root), root, QodanaYaml(include = inc("clion-misra-cpp2008-0-1-1"))).checks,
        )
    }

    @Test
    fun `clion include with valid exclude layers exclude only`(
        @TempDir root: Path,
    ) {
        val r =
            process(
                projectUnder(root),
                root,
                QodanaYaml(include = inc("clion-misra-cpp2008-0-1-1"), exclude = inc("clang-analyzer-cplusplus.NewDeleteLeaks")),
            )
        assertEquals("--checks=$defaults,-clang-analyzer-cplusplus.NewDeleteLeaks", r.checks)
    }

    @Test
    fun `mixed clion and valid includes keeps the valid one`(
        @TempDir root: Path,
    ) {
        val r = process(projectUnder(root), root, QodanaYaml(include = inc("clion-misra-cpp2008-0-1-1", "bugprone-*")))
        assertEquals("--checks=$defaults,bugprone-*", r.checks)
    }

    @Test
    fun `quoted include filtered then defaults`(
        @TempDir root: Path,
    ) {
        assertEquals(
            "--checks=$defaults",
            process(projectUnder(root), root, QodanaYaml(include = inc("bugprone-\"x\""))).checks,
        )
    }

    @Test
    fun `quoted exclude filtered then defaults`(
        @TempDir root: Path,
    ) {
        assertEquals(
            "--checks=$defaults",
            process(projectUnder(root), root, QodanaYaml(exclude = inc("clang-analyzer-\"x\""))).checks,
        )
    }

    // .clang-tidy present: the config is the base; defaults are dropped.

    @Test
    fun `dot-clang-tidy in project defers to config`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir)
        val r = process(dir, root)
        assertEquals("", r.checks)
        assertNull(r.configFile)
    }

    @Test
    fun `dot-clang-tidy only includes drops defaults`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir)
        assertEquals("--checks=bugprone-*", process(dir, root, QodanaYaml(include = inc("bugprone-*"))).checks)
    }

    @Test
    fun `dot-clang-tidy only excludes drops defaults`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir)
        assertEquals(
            "--checks=-clang-analyzer-cplusplus.NewDeleteLeaks",
            process(dir, root, QodanaYaml(exclude = inc("clang-analyzer-cplusplus.NewDeleteLeaks"))).checks,
        )
    }

    @Test
    fun `dot-clang-tidy both includes and excludes drops defaults`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir)
        val r = process(dir, root, QodanaYaml(include = inc("bugprone-*"), exclude = inc("bugprone-argument-comment")))
        assertEquals("--checks=bugprone-*,-bugprone-argument-comment", r.checks)
    }

    @Test
    fun `dot-clang-tidy with includes leaves configFile null`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir)
        assertNull(process(dir, root, QodanaYaml(include = inc("bugprone-*"))).configFile)
    }

    @Test
    fun `all includes filtered with config still defers`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir)
        assertEquals("", process(dir, root, QodanaYaml(include = inc("clion-misra-cpp2008-0-1-1"))).checks)
    }

    // .clang-tidy in ancestors: still defers via clang-tidy's native walk (configFile null).

    @Test
    fun `dot-clang-tidy in parent defers and configFile null`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir.parent)
        val r = process(dir, root)
        assertEquals("", r.checks)
        assertNull(r.configFile)
    }

    @Test
    fun `dot-clang-tidy in grandparent defers`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir.parent.parent)
        assertEquals("", process(dir, root).checks)
    }

    @Test
    fun `dot-clang-tidy in parent only includes`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir.parent)
        assertEquals("--checks=bugprone-*", process(dir, root, QodanaYaml(include = inc("bugprone-*"))).checks)
    }

    @Test
    fun `dot-clang-tidy in parent includes and excludes`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir.parent)
        val r = process(dir, root, QodanaYaml(include = inc("bugprone-*"), exclude = inc("bugprone-argument-comment")))
        assertEquals("--checks=bugprone-*,-bugprone-argument-comment", r.checks)
    }

    // _clang-tidy must be forwarded via --config-file=<resolved path>.

    @Test
    fun `underscore clang-tidy in project defers and sets resolved configFile`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir, "_clang-tidy")
        val r = process(dir, root)
        assertEquals("", r.checks)
        val configFile = Path.of(r.configFile!!)
        assertEquals("_clang-tidy", configFile.fileName.toString())
        // Resolved path, mirroring Go config_test.go:337-339 (macOS /var -> /private/var).
        assertEquals(dir.toRealPath(), configFile.parent)
    }

    @Test
    fun `underscore clang-tidy in parent sets configFile`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir.parent, "_clang-tidy")
        assertTrue(process(dir, root).configFile!!.endsWith("_clang-tidy"))
    }

    @Test
    fun `underscore clang-tidy with includes keeps configFile and layers checks`(
        @TempDir root: Path,
    ) {
        val dir = projectUnder(root)
        writeConfig(dir, "_clang-tidy")
        val r = process(dir, root, QodanaYaml(include = inc("bugprone-*")))
        assertEquals("--checks=bugprone-*", r.checks)
        assertTrue(r.configFile!!.endsWith("_clang-tidy"))
    }
}
