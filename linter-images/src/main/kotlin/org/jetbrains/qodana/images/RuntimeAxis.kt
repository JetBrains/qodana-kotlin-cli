package org.jetbrains.qodana.images

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The runtime version sub-axis of a cell: ruby X.Y, or clang N (for both cpp and clang images). */
data class Runtime(val tool: String, val version: String, val isDefault: Boolean)

/**
 * Rows of a version file, comments/blanks stripped, arity-validated before any positional index —
 * the single parser for every docker version file (`clang-versions.txt`/`ruby-versions.txt`/`debian-bases.txt`; shared by
 * [RuntimeResolver] and [ResolveBuildArgsCommand] so the malformed-input contract can't diverge).
 * Missing/empty/malformed input fails loud with an [IllegalStateException] naming the file.
 */
fun versionRows(
    file: Path,
    minCols: Int,
    maxCols: Int,
): List<List<String>> {
    val allLines =
        try {
            Files.readAllLines(file)
        } catch (e: NoSuchFileException) {
            error("version file ${file.fileName} not found: ${e.message}")
        }
    if (allLines.isEmpty()) error("${file.fileName} is empty")
    return allLines
        .mapIndexed { idx, line -> idx + 1 to line.substringBefore('#').trim() }
        .filter { (_, trimmed) -> trimmed.isNotEmpty() }
        .map { (lineNum, line) ->
            val parts = line.split(Regex("\\s+"))
            check(parts.size in minCols..maxCols) {
                "malformed row in ${file.fileName}:$lineNum (want $minCols-$maxCols columns): '$line'"
            }
            parts
        }
}

/**
 * Single source of "which runtime version does this (image, version) cell resolve to" — shared by
 * `resolve-build-args` (which then maps it to `--build-arg`s) and `resolve-tags` (which maps it to the
 * tag's runtime segment). Returns null for a non-versioned image; throws [IllegalStateException] on a
 * stray version (non-versioned image) or an unknown one (loud, never silent).
 */
class RuntimeResolver(
    private val imagesDir: Path,
    private val clangVersions: Path,
    private val rubyVersions: Path,
) {
    fun resolve(
        image: String,
        version: String,
    ): Runtime? =
        when (image) {
            "qodana-ruby" -> {
                val rows = versionRows(rubyVersions, 2, 3)
                val default =
                    rows.singleOrNull { it.getOrNull(2) == "default" }?.get(0)
                        ?: error("exactly one row in ${rubyVersions.fileName} must be marked 'default'")
                val effective = version.ifEmpty { default }
                check(rows.any { it[0] == effective }) { "unknown ruby version '$effective'" }
                Runtime("ruby", effective, effective == default)
            }
            "qodana-cpp", "qodana-clang" -> {
                val default = env(image)["CLANG"] ?: error("$image.env has no CLANG default")
                val effective = version.ifEmpty { default }
                check(versionRows(clangVersions, 2, 2).any { it[0] == effective }) {
                    "unknown clang major '$effective' (not in ${clangVersions.fileName})"
                }
                Runtime("clang", effective, effective == default)
            }
            else -> {
                check(version.isEmpty()) { "image '$image' has no runtime-version axis but version='$version' was set" }
                null
            }
        }

    private fun env(image: String): Map<String, String> =
        Files
            .readAllLines(imagesDir.resolve("$image.env"))
            .mapNotNull { line ->
                val t = line.substringBefore('#').trim()
                if ('=' in t) t.substringBefore('=').trim() to t.substringAfter('=').trim() else null
            }.toMap()
}
