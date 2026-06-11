package org.jetbrains.qodana.images.docker

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Resolves each thin image's INCLUDE/INCLUDE_ARGS via dockerfile-x and hadolints the output,
 * driving the real `docker/resolve-and-lint.sh` against the real `docker/images` thin-image
 * compositions (no probe scaffolding).
 *
 * `@Tag("docker")` routes the class through the `parityTest` Gradle task; it is excluded from the
 * default `test` task. The harness shells out to `npx dockerfile-x@1.6.0` + `hadolint`, so this
 * exercises real external tools. Per CLAUDE.md "tests must never silently skip", it fails LOUDLY
 * (never assumes/skips) when `npx`/`hadolint` are unavailable; CI (Phase 5) provisions both.
 */
@Tag("docker")
class ResolveAndLintTest {
    private val dockerDir: Path = Path.of("docker")
    private val harness: Path = dockerDir.resolve("resolve-and-lint.sh")

    @Test
    fun `qodana-jvm composition resolves and lints clean`(
        @TempDir log: Path,
    ) = resolveAndLint("images/qodana-jvm.dockerfile", log)

    @Test
    fun `qodana-android composition resolves and lints clean`(
        @TempDir log: Path,
    ) = resolveAndLint("images/qodana-android.dockerfile", log)

    @Test
    fun `qodana-clang composition resolves and lints clean`(
        @TempDir log: Path,
    ) = resolveAndLint("images/qodana-clang.dockerfile", log)

    /**
     * Runs the harness against one thin image and asserts a clean (exit 0) resolve + lint.
     * Captures combined stdout/stderr to a temp file so the resolved Dockerfile and any hadolint
     * finding survive intact in the failure message.
     */
    private fun resolveAndLint(
        thin: String,
        log: Path,
    ) {
        requireTool("npx")
        requireTool("hadolint")
        assertTrue(Files.isExecutable(harness), "$harness must be executable")

        val out = log.resolve("harness.out")
        val exit =
            ProcessBuilder("./resolve-and-lint.sh", thin)
                .directory(dockerDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(out.toFile())
                .start()
                .waitFor()

        assertEquals(
            0,
            exit,
            "resolve-and-lint.sh $thin must resolve via dockerfile-x and lint clean.\n" +
                Files.readString(out),
        )
    }

    /** Fail loudly (no skip) if a required external tool is absent. */
    private fun requireTool(tool: String) {
        val present =
            try {
                ProcessBuilder("sh", "-c", "command -v $tool")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0
            } catch (e: Exception) {
                fail("@Tag(\"docker\") test could not probe for '$tool': ${e.message}")
            }
        if (!present) {
            fail("@Tag(\"docker\") test ran but '$tool' is not on PATH; CI must provision it")
        }
    }
}
