package org.jetbrains.qodana.images.e2e

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class DockerRunPlannerTest {
    private val projectDir = Path.of("/tmp/case/project")
    private val resultsDir = Path.of("/tmp/case/results")
    private val cacheDir = Path.of("/tmp/case/cache")

    @Test
    fun `hermetic clang argv is exact`() {
        val run =
            RunSpec(
                network = "none",
                capAdd = listOf("SYS_ADMIN"),
                securityOpt = listOf("apparmor:unconfined"),
                extraArgs = listOf("--no-statistics"),
            )

        val argv = DockerRunPlanner.dockerArgs("qodana-clang:dev", projectDir, resultsDir, cacheDir, run)

        assertEquals(
            listOf(
                "docker",
                "run",
                "--rm",
                "--network",
                "none",
                "-v",
                "/tmp/case/project:/data/project",
                "-v",
                "/tmp/case/results:/data/results",
                "-v",
                "/tmp/case/cache:/data/cache",
                "--cap-add",
                "SYS_ADMIN",
                "--security-opt",
                "apparmor:unconfined",
                "qodana-clang:dev",
                "scan",
                "--results-dir",
                "/data/results",
                "--no-statistics",
            ),
            argv,
        )
    }

    @Test
    fun `android argv has bridge network and no caps`() {
        val run =
            RunSpec(
                network = "bridge",
                passEnv = listOf("QODANA_TOKEN"),
                failThreshold = 10,
            )

        val argv =
            DockerRunPlanner.dockerArgs(
                "qodana-android:dev",
                projectDir,
                resultsDir,
                cacheDir,
                run,
                hostEnv = mapOf("QODANA_TOKEN" to "tok"),
            )

        assertEquals(
            listOf(
                "docker",
                "run",
                "--rm",
                "--network",
                "bridge",
                "-v",
                "/tmp/case/project:/data/project",
                "-v",
                "/tmp/case/results:/data/results",
                "-v",
                "/tmp/case/cache:/data/cache",
                "-e",
                "QODANA_TOKEN",
                "qodana-android:dev",
                "scan",
                "--results-dir",
                "/data/results",
                "--fail-threshold",
                "10",
            ),
            argv,
        )
    }

    @Test
    fun `passEnv fails loudly when a requested host env is missing`() {
        val run = RunSpec(passEnv = listOf("QODANA_TOKEN"))

        val error =
            kotlin
                .runCatching {
                    DockerRunPlanner.dockerArgs(
                        "qodana-jvm:dev",
                        projectDir,
                        resultsDir,
                        cacheDir,
                        run,
                        hostEnv = emptyMap(),
                    )
                }.exceptionOrNull()

        assertEquals("required host env 'QODANA_TOKEN' is missing or blank", error?.message)
    }

    @Test
    fun `passEnv fails loudly when a requested host env is blank`() {
        val run = RunSpec(passEnv = listOf("QODANA_TOKEN"))

        val error =
            kotlin
                .runCatching {
                    DockerRunPlanner.dockerArgs(
                        "qodana-jvm:dev",
                        projectDir,
                        resultsDir,
                        cacheDir,
                        run,
                        hostEnv = mapOf("QODANA_TOKEN" to ""),
                    )
                }.exceptionOrNull()

        assertEquals("required host env 'QODANA_TOKEN' is missing or blank", error?.message)
    }
}
