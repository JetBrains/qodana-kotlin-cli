package org.jetbrains.qodana.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * Class-name prefixes and resource substrings that must never appear in
 * committed GraalVM metadata. Mirrored by the `stripTestEntriesFromMetadata`
 * Gradle task — any format change goes in both.
 */
data class BannedMetadataPatterns(
    val classPrefixes: List<String>,
    val resourceSubstrings: List<String>,
) {
    companion object {
        private const val SECTION_CLASS_PREFIXES = "[class-prefixes]"
        private const val SECTION_RESOURCE_SUBSTRINGS = "[resource-substrings]"

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
