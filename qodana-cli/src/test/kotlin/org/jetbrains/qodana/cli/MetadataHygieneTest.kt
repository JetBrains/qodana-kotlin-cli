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
 * Guards the GraalVM native-image metadata directory against test-infrastructure
 * entries and test-only resources bleeding in from tracing-agent runs.
 *
 * The tracing agent captures reflection / resource / JNI accesses while tests
 * run. Any test-only class (JUnit Platform, kotlin.test, our own NativeSmokeTest
 * / InitCommandTest / SendCommandTest, Gradle worker internals) or test-only
 * resource (scan-smoke-fixture, junit-platform.properties) that happens to be
 * accessed during the test run will be recorded and — without an explicit strip
 * step — would end up in the committed JSON files, causing the native binary to
 * bundle test infrastructure into production.
 *
 * The canonical banned list lives in
 * `qodana-cli/src/test/resources/banned-metadata-patterns.txt` and is loaded
 * via [BannedMetadataPatterns.load].  The `stripTestEntriesFromMetadata`
 * Gradle task in `qodana-cli/build.gradle.kts` reads the same file at build
 * time so the strip and the test share a single source of truth.
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

    /**
     * Checks reflect-config.json and jni-config.json: both are flat JSON arrays
     * of objects with a "name" key.
     */
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
            // Quick text scan first to avoid pulling in a JSON library at test time.
            // The "name" value in these files is always a simple quoted string.
            if (("\"$prefix" in json) || ("\\\"$prefix" in json)) {
                // Confirm by finding each occurrence.
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

    /**
     * Checks resource-config.json: a JSON object whose resources.includes array
     * contains objects with a "pattern" key.
     */
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
