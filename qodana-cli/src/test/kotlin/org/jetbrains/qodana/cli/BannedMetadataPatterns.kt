package org.jetbrains.qodana.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * Parsed view of `qodana-cli/src/test/resources/banned-metadata-patterns.txt`,
 * the canonical list of class-name prefixes and resource substrings that must
 * never appear in committed GraalVM native-image metadata.
 *
 * Both [MetadataHygieneTest] and the `stripTestEntriesFromMetadata` Gradle
 * task in `qodana-cli/build.gradle.kts` consume this file so the strip task
 * and the test share a single source of truth. The build script parses the
 * file with the same logic (duplicated by necessity because Gradle's Groovy
 * script context cannot import test classes), so any edit to the parser
 * format must be mirrored in both places.
 */
data class BannedMetadataPatterns(
    val classPrefixes: List<String>,
    val resourceSubstrings: List<String>,
) {
    companion object {
        private const val SECTION_CLASS_PREFIXES = "[class-prefixes]"
        private const val SECTION_RESOURCE_SUBSTRINGS = "[resource-substrings]"

        /**
         * Reads the canonical banned-patterns file. Lines beginning with `#`
         * are treated as comments; blank lines are ignored; otherwise each
         * non-empty line in a section is added to that section's list.
         *
         * Fails loudly via [IllegalStateException] if either section is
         * missing or empty — the file is small enough that an accidental
         * deletion is far more likely than a deliberate one.
         */
        fun load(file: Path): BannedMetadataPatterns {
            check(Files.exists(file)) { "banned-metadata-patterns.txt is missing at $file" }
            val classPrefixes = mutableListOf<String>()
            val resourceSubstrings = mutableListOf<String>()
            var section: MutableList<String>? = null
            for (raw in Files.readAllLines(file)) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when (line) {
                    SECTION_CLASS_PREFIXES -> section = classPrefixes
                    SECTION_RESOURCE_SUBSTRINGS -> section = resourceSubstrings
                    else -> {
                        checkNotNull(section) {
                            "banned-metadata-patterns.txt: entry \"$line\" appears before any section marker"
                        }.add(line)
                    }
                }
            }
            check(classPrefixes.isNotEmpty()) {
                "banned-metadata-patterns.txt: [class-prefixes] section is empty or missing"
            }
            check(resourceSubstrings.isNotEmpty()) {
                "banned-metadata-patterns.txt: [resource-substrings] section is empty or missing"
            }
            return BannedMetadataPatterns(classPrefixes, resourceSubstrings)
        }
    }
}
