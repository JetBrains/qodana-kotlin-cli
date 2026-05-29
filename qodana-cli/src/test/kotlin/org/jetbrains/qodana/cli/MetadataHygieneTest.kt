package org.jetbrains.qodana.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Catches test-infrastructure leaks (JUnit, kotlin.test, test FQCNs,
 * `scan-smoke-fixture`, etc.) in the committed GraalVM metadata. The banned
 * patterns live in `src/test/resources/banned-metadata-patterns.txt` so the
 * `stripTestEntriesFromMetadata` Gradle task and this test stay in sync.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MetadataHygieneTest {
    companion object {
        private val PATTERNS =
            BannedMetadataPatterns.load(
                Path.of("src/test/resources/banned-metadata-patterns.txt"),
            )

        private val METADATA_DIR =
            Paths.get(
                "src/main/resources/META-INF/native-image/org.jetbrains.qodana/qodana-cli",
            )

        @JvmStatic
        fun arrayConfigFiles(): List<Path> =
            listOf(
                METADATA_DIR.resolve("reflect-config.json"),
                METADATA_DIR.resolve("jni-config.json"),
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("arrayConfigFiles")
    fun `array config file contains no banned class names`(configFile: Path) {
        assertTrue(
            configFile.exists(),
            "expected native-image metadata file $configFile to exist; if it's been removed, " +
                "either delete this test parameter or regenerate the metadata via " +
                "`./gradlew :qodana-cli:metadataCopy`",
        )

        val json = configFile.readText()
        val violations = mutableListOf<String>()
        for (prefix in PATTERNS.classPrefixes) {
            // Substring pre-check avoids regex-parsing every config file for
            // every prefix when nothing matches.
            if (("\"$prefix" in json) || ("\\\"$prefix" in json)) {
                val pattern = Regex(""""name"\s*:\s*"([^"]+)"""")
                pattern
                    .findAll(json)
                    .map { it.groupValues[1] }
                    .filter { it.startsWith(prefix) }
                    .forEach { violations.add("$configFile: banned entry \"$it\" (matches prefix \"$prefix\")") }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Test-infrastructure entries found in GraalVM metadata.")
                    appendLine("Run './gradlew :qodana-cli:metadataCopy' to regenerate and auto-strip,")
                    appendLine("or './gradlew :qodana-cli:stripTestEntriesFromMetadata' to strip existing files.")
                    appendLine()
                    violations.forEach { appendLine("  $it") }
                },
            )
        }
    }

    @Test
    fun `resource-config contains no banned patterns`() {
        val configFile = METADATA_DIR.resolve("resource-config.json")
        assertTrue(
            configFile.exists(),
            "expected native-image metadata file $configFile to exist; if it's been removed, " +
                "either delete this test or regenerate the metadata via " +
                "`./gradlew :qodana-cli:metadataCopy`",
        )

        val json = configFile.readText()
        val violations = mutableListOf<String>()

        val pattern = Regex(""""pattern"\s*:\s*"([^"]+)"""")
        for (match in pattern.findAll(json)) {
            val patternValue = match.groupValues[1]
            for (banned in PATTERNS.resourceSubstrings) {
                if (banned in patternValue) {
                    violations.add(
                        "resource-config.json: banned pattern \"$patternValue\" (contains \"$banned\")",
                    )
                    break
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Test-infrastructure resource patterns found in GraalVM metadata.")
                    appendLine("Run './gradlew :qodana-cli:metadataCopy' to regenerate and auto-strip,")
                    appendLine("or './gradlew :qodana-cli:stripTestEntriesFromMetadata' to strip existing files.")
                    appendLine()
                    violations.forEach { appendLine("  $it") }
                },
            )
        }
    }
}
