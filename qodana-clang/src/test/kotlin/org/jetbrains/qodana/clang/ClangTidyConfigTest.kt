package org.jetbrains.qodana.clang

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClangTidyConfigTest {
    private fun writeConfig(
        dir: Path,
        name: String,
    ) {
        Files.writeString(dir.resolve(name), "Checks: '-*,bugprone-*'\n")
    }

    @Test
    fun `finds dot-clang-tidy in start dir`(
        @TempDir root: Path,
    ) {
        writeConfig(root, ".clang-tidy")
        assertEquals(".clang-tidy", ClangTidyConfig.find(root, searchRoot = root)?.fileName?.toString())
    }

    @Test
    fun `finds underscore clang-tidy in start dir`(
        @TempDir root: Path,
    ) {
        writeConfig(root, "_clang-tidy")
        assertEquals("_clang-tidy", ClangTidyConfig.find(root, searchRoot = root)?.fileName?.toString())
    }

    @Test
    fun `finds dot-clang-tidy in parent`(
        @TempDir root: Path,
    ) {
        writeConfig(root, ".clang-tidy")
        val start = Files.createDirectories(root.resolve("project"))
        assertEquals(".clang-tidy", ClangTidyConfig.find(start, searchRoot = root)?.fileName?.toString())
    }

    @Test
    fun `finds dot-clang-tidy in grandparent`(
        @TempDir root: Path,
    ) {
        writeConfig(root, ".clang-tidy")
        val start = Files.createDirectories(root.resolve("parent").resolve("project"))
        assertEquals(".clang-tidy", ClangTidyConfig.find(start, searchRoot = root)?.fileName?.toString())
    }

    @Test
    fun `prefers dot-clang-tidy over underscore in same dir`(
        @TempDir root: Path,
    ) {
        writeConfig(root, ".clang-tidy")
        writeConfig(root, "_clang-tidy")
        assertEquals(".clang-tidy", ClangTidyConfig.find(root, searchRoot = root)?.fileName?.toString())
    }

    @Test
    fun `returns null when no config anywhere`(
        @TempDir root: Path,
    ) {
        val start = Files.createDirectories(root.resolve("project"))
        assertNull(ClangTidyConfig.find(start, searchRoot = root))
    }

    @Test
    fun `finds config AT the searchRoot and returns a resolved path`(
        @TempDir root: Path,
    ) {
        writeConfig(root, ".clang-tidy")
        val start = Files.createDirectories(root.resolve("inside").resolve("deeper"))
        val found = ClangTidyConfig.find(start, searchRoot = root)
        assertEquals(".clang-tidy", found?.fileName?.toString())
        // Path is symlink-resolved, mirroring Go's EvalSymlinks (macOS /var -> /private/var).
        assertEquals(root.toRealPath(), found?.parent)
    }

    @Test
    fun `does not find config ABOVE the searchRoot`(
        @TempDir tmp: Path,
    ) {
        val root = Files.createDirectories(tmp.resolve("root"))
        val start = Files.createDirectories(root.resolve("inside").resolve("deeper"))
        writeConfig(tmp, ".clang-tidy") // above the search root
        assertNull(ClangTidyConfig.find(start, searchRoot = root))
    }

    @Test
    fun `errors when searchRoot is not an ancestor (sibling)`(
        @TempDir tmp: Path,
    ) {
        val a = Files.createDirectories(tmp.resolve("a"))
        val b = Files.createDirectories(tmp.resolve("b"))
        val ex = assertFailsWith<IllegalArgumentException> { ClangTidyConfig.find(b, searchRoot = a) }
        assertTrue(ex.message!!.contains("is not an ancestor"))
    }

    @Test
    fun `errors when searchRoot is a descendant of startDir`(
        @TempDir tmp: Path,
    ) {
        val parent = Files.createDirectories(tmp.resolve("parent"))
        val child = Files.createDirectories(parent.resolve("child"))
        val ex = assertFailsWith<IllegalArgumentException> { ClangTidyConfig.find(parent, searchRoot = child) }
        assertTrue(ex.message!!.contains("is not an ancestor"))
    }

    @Test
    fun `envSearchRoot is null when unset or blank, reads the var otherwise`() {
        assertNull(ClangTidyConfig.envSearchRoot { null })
        assertNull(ClangTidyConfig.envSearchRoot { "   " })
        assertEquals(Path.of("/x/y"), ClangTidyConfig.envSearchRoot { "/x/y" })
    }
}
