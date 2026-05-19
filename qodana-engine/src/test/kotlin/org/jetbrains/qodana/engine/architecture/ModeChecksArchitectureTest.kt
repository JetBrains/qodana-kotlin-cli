package org.jetbrains.qodana.engine.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertTrue

class ModeChecksArchitectureTest {
    @Test
    fun `legacy mode checks are not used in engine implementation`() {
        val sourceRoot = locateSourceRoot("qodana-engine")
        val allowedSuffixes =
            setOf(
                "org/jetbrains/qodana/engine/env/CiDetector.kt",
                "org/jetbrains/qodana/engine/env/RuntimeEnvironmentDetector.kt",
            )
        val forbiddenPatterns =
            listOf(
                "nativeMode",
                "analysisMode",
                "AnalysisMode",
                "CiDetector.isContainer(",
            )

        val violations = mutableListOf<String>()
        Files.walk(sourceRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "kt" }
                .forEach { file ->
                    val relative = sourceRoot.relativize(file).invariantSeparatorsPathString
                    if (allowedSuffixes.contains(relative)) return@forEach
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
            "Found forbidden legacy mode checks in engine sources:\n${violations.joinToString("\n")}",
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
