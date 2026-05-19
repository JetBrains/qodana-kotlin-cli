package org.jetbrains.qodana.cli.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertTrue

class ModeChecksArchitectureTest {
    @Test
    fun `legacy mode checks are not used in cli implementation`() {
        val sourceRoot = locateSourceRoot("qodana-cli")
        val forbiddenPatterns =
            listOf(
                "nativeMode",
                "analysisMode",
                "AnalysisMode",
                "CiDetector.isContainer(",
                "QodanaEnv.DOCKER",
            )

        val violations = mutableListOf<String>()
        Files.walk(sourceRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .forEach { file ->
                    val relative = sourceRoot.relativize(file).invariantSeparatorsPathString
                    val content = Files.readString(file)
                    forbiddenPatterns.forEach { pattern ->
                        if (content.contains(pattern)) {
                            violations += "$relative contains '$pattern'"
                        }
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Found forbidden legacy mode checks in cli sources:\n${violations.joinToString("\n")}",
        )
    }

    private fun locateSourceRoot(moduleName: String): Path {
        val candidates =
            listOf(
                Path.of("src/main/kotlin"),
                Path.of(moduleName, "src/main/kotlin"),
            )
        return candidates
            .firstOrNull { Files.exists(it) }
            ?.toAbsolutePath()
            ?.normalize()
            ?: error("Cannot locate source root for $moduleName")
    }
}
