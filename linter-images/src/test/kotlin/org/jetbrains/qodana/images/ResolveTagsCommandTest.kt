package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** Expected tags are written out by hand from the grammar `<mm>-<channel>[.<id>][-<runtime>]`; they pin
 * the exact output string (a self-consistent grammar bug like '-' instead of '.' before the id reddens). */
class ResolveTagsCommandTest {
    private val reg = "registry.jetbrains.team/p/sa/qodana-kcli-images"

    private fun cmd(gp: Path) =
        ResolveTagsCommand(
            gradleProperties = gp,
            runtime =
                RuntimeResolver(
                    Path.of("docker/images"),
                    Path.of("docker/clang-versions.txt"),
                    Path.of("docker/ruby-versions.txt"),
                ),
        )

    private fun gp(
        tmp: Path,
        v: String,
    ): Path = tmp.resolve("gradle.properties").also { it.writeText("version=$v\n") }

    @Test fun `non-versioned snapshot exact`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf("$reg/qodana-jvm:2026.2-snapshot.a1b2c3d"),
        cmd(gp(tmp, "2026.2")).resolve("qodana-jvm", "", reg, "snapshot", "a1b2c3d"),
    )

    @Test fun `versioned default dual-tags`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf("$reg/qodana-cpp:2026.2-snapshot.a1b2c3d", "$reg/qodana-cpp:2026.2-snapshot.a1b2c3d-clang20"),
        cmd(gp(tmp, "2026.2")).resolve("qodana-cpp", "", reg, "snapshot", "a1b2c3d"),
    )

    @Test fun `versioned non-default single tag`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf("$reg/qodana-clang:2026.2-snapshot.a1b2c3d-clang18"),
        cmd(gp(tmp, "2026.2")).resolve("qodana-clang", "18", reg, "snapshot", "a1b2c3d"),
    )

    @Test fun `ruby non-default snapshot`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf("$reg/qodana-ruby:2026.2-snapshot.a1b2c3d-ruby3.4"),
        cmd(gp(tmp, "2026.2")).resolve("qodana-ruby", "3.4", reg, "snapshot", "a1b2c3d"),
    )

    @Test fun `nightly dated emits the dated and the moving tag`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf("$reg/qodana-jvm:2026.2-nightly.20260605", "$reg/qodana-jvm:2026.2-nightly"),
        cmd(gp(tmp, "2026.2")).resolve("qodana-jvm", "", reg, "nightly", "20260605"),
    )

    @Test fun `nightly dated default emits dated and moving, each dual-tagged`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf(
            "$reg/qodana-cpp:2026.2-nightly.20260605",
            "$reg/qodana-cpp:2026.2-nightly.20260605-clang20",
            "$reg/qodana-cpp:2026.2-nightly",
            "$reg/qodana-cpp:2026.2-nightly-clang20",
        ),
        cmd(gp(tmp, "2026.2")).resolve("qodana-cpp", "", reg, "nightly", "20260605"),
    )

    @Test fun `nightly moving-only (empty id) dual-tags a default`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf("$reg/qodana-cpp:2026.2-nightly", "$reg/qodana-cpp:2026.2-nightly-clang20"),
        cmd(gp(tmp, "2026.2")).resolve("qodana-cpp", "", reg, "nightly", ""),
    )

    @Test fun `three-segment version yields major-minor`(
        @TempDir tmp: Path,
    ) = assertEquals(
        listOf("$reg/qodana-jvm:2026.2-snapshot.a1b2c3d"),
        cmd(gp(tmp, "2026.2.1")).resolve("qodana-jvm", "", reg, "snapshot", "a1b2c3d"),
    )

    @Test
    fun `dev version fails loud`(
        @TempDir tmp: Path,
    ) {
        val ex = runCatching { cmd(gp(tmp, "dev")).resolve("qodana-jvm", "", reg, "snapshot", "x") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("dev"), "want ISE naming dev, was $ex")
    }

    @Test
    fun `unknown channel fails loud`(
        @TempDir tmp: Path,
    ) {
        val ex = runCatching { cmd(gp(tmp, "2026.2")).resolve("qodana-jvm", "", reg, "beta", "x") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("beta"), "want ISE naming beta, was $ex")
    }
}
