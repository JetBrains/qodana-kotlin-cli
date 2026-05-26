package org.jetbrains.qodana.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.fail

/**
 * Guards the GraalVM native-image metadata directory against test-infrastructure
 * entries bleeding in from tracing-agent runs.
 *
 * The tracing agent captures reflection / resource / JNI accesses while tests run.
 * Any test-only class (JUnit Platform, kotlin.test, our own NativeSmokeTest /
 * InitCommandTest / SendCommandTest, Gradle worker internals) that happens to be
 * accessed during the test run will be recorded and — without an explicit strip
 * step — would end up in the committed JSON files, causing the native binary to
 * bundle test infrastructure.
 *
 * This test ensures that the [stripTestEntriesFromMetadata] Gradle task (defined
 * in qodana-cli/build.gradle.kts) was applied before the metadata was committed.
 * It runs as part of the regular JVM test suite so that CI fails immediately if
 * a contributor regenerates metadata without stripping.
 */
class MetadataHygieneTest {
    companion object {
        /**
         * Class-name prefixes that must never appear in reflect-config.json or
         * jni-config.json.  Keep in sync with `testClassPrefixes` in
         * qodana-cli/build.gradle.kts.
         */
        private val BANNED_CLASS_PREFIXES =
            listOf(
                // Our own test classes
                "org.jetbrains.qodana.cli.NativeSmokeTest",
                "org.jetbrains.qodana.cli.MetadataHygieneTest",
                "org.jetbrains.qodana.cli.command.InitCommandTest",
                "org.jetbrains.qodana.cli.command.SendCommandTest",
                "org.jetbrains.qodana.cli.command.SendTestSupport",
                // JUnit Jupiter / Platform
                "org.junit.",
                // opentest4j (JUnit assertion infrastructure)
                "org.opentest4j.",
                // kotlin.test
                "kotlin.test.",
                // kotlinx-coroutines test support
                "kotlinx.coroutines.test.",
                // Gradle worker process (injected when agent runs inside a Gradle build)
                "worker.org.gradle.",
            )

        /**
         * Substrings that must never appear inside a resource-config.json pattern.
         * Keep in sync with `testResourceSubstrings` in qodana-cli/build.gradle.kts.
         */
        private val BANNED_RESOURCE_SUBSTRINGS =
            listOf(
                "junit-platform.properties",
                "META-INF/services/kotlin.test.",
                "META-INF/services/org.junit.platform.",
                "META-INF/services/org.junit.jupiter.",
            )

        private val METADATA_DIR =
            Paths.get(
                "src/main/resources/META-INF/native-image/org.jetbrains.qodana/qodana-cli",
            )

        @JvmStatic
        fun arrayConfigFiles() =
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
    fun `array config file contains no banned class names`(configFile: java.nio.file.Path) {
        if (!configFile.exists()) return // file absent → nothing to check

        val json = configFile.readText()
        val violations = mutableListOf<String>()
        for (prefix in BANNED_CLASS_PREFIXES) {
            // Quick text scan first to avoid pulling in a JSON library at test time.
            // The "name" value in these files is always a simple quoted string.
            if (("\"$prefix" in json) || ("\\\"$prefix" in json)) {
                // Confirm by finding each occurrence
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
        if (!configFile.exists()) return

        val json = configFile.readText()
        val violations = mutableListOf<String>()

        val pattern = Regex(""""pattern"\s*:\s*"([^"]+)"""")
        for (match in pattern.findAll(json)) {
            val patternValue = match.groupValues[1]
            for (banned in BANNED_RESOURCE_SUBSTRINGS) {
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
