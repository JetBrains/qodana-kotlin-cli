package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

class ResolveImageMetaCommandTest {
    private val cmd =
        ResolveImageMetaCommand(
            imagesDir = Path.of("docker/images"),
            runtime =
                RuntimeResolver(
                    Path.of("docker/images"),
                    Path.of("docker/clang-versions.txt"),
                    Path.of("docker/ruby-versions.txt"),
                ),
        )

    private val allImages =
        listOf(
            "qodana-jvm",
            "qodana-jvm-community",
            "qodana-android",
            "qodana-android-community",
            "qodana-python",
            "qodana-python-community",
            "qodana-js",
            "qodana-go",
            "qodana-php",
            "qodana-rust",
            "qodana-ruby",
            "qodana-dotnet",
            "qodana-cpp",
            "qodana-clang",
            "qodana-cdnet",
        )

    @Test
    fun `token-gated is exactly clang and cdnet across all 15 images`() {
        allImages.forEach { img ->
            val expected = img in setOf("qodana-clang", "qodana-cdnet")
            assertEquals(expected, cmd.resolve(img, "").tokenGated, "$img token_gated")
        }
    }

    @Test fun `jvm is feed-required`() = assertEquals(true, cmd.resolve("qodana-jvm", "").feedRequired)

    @Test fun `clang is feed-less`() = assertEquals(false, cmd.resolve("qodana-clang", "").feedRequired)

    @Test fun `cdnet is feed-less`() = assertEquals(false, cmd.resolve("qodana-cdnet", "").feedRequired)

    @Test fun `compose files are the standard three`() =
        assertEquals(
            "-f linter-images/compose.yaml -f linter-images/compose.ci.yaml -f linter-images/compose.private.yaml",
            cmd.resolve("qodana-jvm", "").composeFiles,
        )

    @Test fun `effective version normalizes a versioned default`() {
        assertEquals("20", cmd.resolve("qodana-cpp", "").effectiveVersion)
        assertEquals("3.3", cmd.resolve("qodana-ruby", "").effectiveVersion)
    }

    @Test fun `effective version echoes an explicit non-default`() {
        assertEquals("17", cmd.resolve("qodana-cpp", "17").effectiveVersion)
    }

    @Test fun `effective version is empty for a non-versioned image`() {
        assertEquals("", cmd.resolve("qodana-jvm", "").effectiveVersion)
    }

    @Test
    fun `unknown image throws`() {
        val ex = runCatching { cmd.resolve("qodana-nope", "") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("qodana-nope"), "was $ex")
    }

    @Test
    fun `stray version on a non-versioned image throws`() {
        val ex = runCatching { cmd.resolve("qodana-jvm", "17") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("17"), "was $ex")
    }

    @Test
    fun `unknown version throws`() {
        val ex = runCatching { cmd.resolve("qodana-cpp", "99") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("99"), "was $ex")
    }
}
