package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** Expected tags are written out by hand from the grammar `<mm>-<channel>[.<id>][-<runtime>]`; they pin
 * the exact output string (a self-consistent grammar bug like '-' instead of '.' before the id reddens). */
class ResolveTagsCommandTest {
    private fun gp(mm: String): Path {
        val path = Files.createTempFile("gradle", ".properties")
        path.writeText("version=$mm\n")
        return path
    }

    private fun clangRuntime() =
        RuntimeResolver(
            Path.of("docker/images"),
            Path.of("docker/clang-versions.txt"),
            Path.of("docker/ruby-versions.txt"),
        )

    @Test fun `non-versioned snapshot exact`() =
        assertEquals(
            listOf("2026.2-snapshot.a1b2c3d"),
            ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-jvm", "", "snapshot", "a1b2c3d"),
        )

    @Test fun `versioned default dual-tags`() =
        assertEquals(
            listOf("2026.2-snapshot.a1b2c3d", "2026.2-snapshot.a1b2c3d-clang20"),
            ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-cpp", "", "snapshot", "a1b2c3d"),
        )

    @Test fun `versioned non-default single tag`() =
        assertEquals(
            listOf("2026.2-snapshot.a1b2c3d-clang18"),
            ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-clang", "18", "snapshot", "a1b2c3d"),
        )

    @Test fun `ruby non-default snapshot`() =
        assertEquals(
            listOf("2026.2-snapshot.a1b2c3d-ruby3.4"),
            ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-ruby", "3.4", "snapshot", "a1b2c3d"),
        )

    @Test fun `nightly dated emits the dated and the moving tag`() =
        assertEquals(
            listOf("2026.2-nightly.20260605", "2026.2-nightly"),
            ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-jvm", "", "nightly", "20260605"),
        )

    @Test fun `nightly dated default emits dated and moving, each dual-tagged`() =
        assertEquals(
            listOf(
                "2026.2-nightly.20260605",
                "2026.2-nightly.20260605-clang20",
                "2026.2-nightly",
                "2026.2-nightly-clang20",
            ),
            ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-cpp", "", "nightly", "20260605"),
        )

    @Test fun `nightly moving-only (empty id) dual-tags a default`() =
        assertEquals(
            listOf("2026.2-nightly", "2026.2-nightly-clang20"),
            ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-cpp", "", "nightly", ""),
        )

    @Test fun `three-segment version yields major-minor`() =
        assertEquals(
            listOf("2026.2-snapshot.a1b2c3d"),
            ResolveTagsCommand(gp("2026.2.1"), clangRuntime()).resolve("qodana-jvm", "", "snapshot", "a1b2c3d"),
        )

    @Test
    fun `dev version fails loud`() {
        val ex =
            runCatching {
                ResolveTagsCommand(gp("dev"), clangRuntime()).resolve("qodana-jvm", "", "snapshot", "x")
            }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("dev"), "want ISE naming dev, was $ex")
    }

    @Test
    fun `unknown channel fails loud`() {
        val ex =
            runCatching {
                ResolveTagsCommand(gp("2026.2"), clangRuntime()).resolve("qodana-jvm", "", "beta", "x")
            }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("beta"), "want ISE naming beta, was $ex")
    }

    @Test
    fun `nightly default cell dual-tags bare + suffixed, dated + moving, no registry prefix`() {
        val cmd = ResolveTagsCommand(gradleProperties = gp("2026.2"), runtime = clangRuntime())
        assertEquals(
            listOf(
                "2026.2-nightly.20260716",
                "2026.2-nightly.20260716-clang19",
                "2026.2-nightly",
                "2026.2-nightly-clang19",
            ),
            cmd.resolve(image = "qodana-clang", version = "", channel = "nightly", id = "20260716"),
        )
    }

    @Test
    fun `nightly non-default cell is suffix-only`() {
        val cmd = ResolveTagsCommand(gradleProperties = gp("2026.2"), runtime = clangRuntime())
        assertEquals(
            listOf("2026.2-nightly.20260716-clang16", "2026.2-nightly-clang16"),
            cmd.resolve("qodana-clang", "16", "nightly", "20260716"),
        )
    }

    @Test
    fun `snapshot cell emits only the id'd tag (no moving pointer)`() {
        val cmd = ResolveTagsCommand(gradleProperties = gp("2026.2"), runtime = clangRuntime())
        val tags = cmd.resolve("qodana-clang", "", "snapshot", "abc1234")
        assertTrue(tags.all { !it.contains(Regex("""(?<!\S)2026\.2-snapshot$""")) }, "snapshot has no bare moving tag")
        assertTrue(tags.contains("2026.2-snapshot.abc1234"), "snapshot emits the id'd tag")
    }

    @Test
    fun `every emitted tag is whitespace-free and registry-safe`() {
        val cmd = ResolveTagsCommand(gradleProperties = gp("2026.2"), runtime = clangRuntime())
        cmd.resolve("qodana-clang", "", "nightly", "20260716").forEach {
            assertTrue(it.matches(Regex("^[A-Za-z0-9._-]+$")), "tag '$it' must be whitespace-free / registry-safe")
        }
    }
}
