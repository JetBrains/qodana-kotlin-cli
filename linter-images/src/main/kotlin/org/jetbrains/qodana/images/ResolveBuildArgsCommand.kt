package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a CI matrix (image, version) cell into `--build-arg` tokens + the expected runtime tool/version
 * for the post-build guard, single-sourced from the version files ($GITHUB_ENV lines). An empty version —
 * or one equal to the family default — emits no build args (the `.env` default already builds it). ruby →
 * ruby-versions.txt QD_BASE_IMAGE; cpp/clang → clang-versions.txt (OS) + debian-bases.txt (base) → CLANG +
 * CLANG_OS + QD_BASE_IMAGE (cpp.dockerfile derives libicu from CLANG_OS); other images → "none".
 */
class ResolveBuildArgsCommand(
    private val imagesDir: Path,
    private val clangVersions: Path,
    private val rubyVersions: Path,
    private val debianBases: Path,
) : CliktCommand(name = "resolve-build-args") {
    private val image by option("--image").required()
    private val version by option("--version").default("")

    data class Resolution(
        val buildArgs: List<String>,
        val expectTool: String,
        val expectVersion: String,
    )

    override fun run() {
        val r = resolve(image, version)
        echo("BUILD_ARGS=${r.buildArgs.joinToString(" ")}")
        echo("EXPECT_TOOL=${r.expectTool}")
        echo("EXPECT_VERSION=${r.expectVersion}")
    }

    fun resolve(
        image: String,
        version: String,
    ): Resolution =
        when (image) {
            "qodana-ruby" -> resolveRuby(version)
            "qodana-cpp", "qodana-clang" -> resolveClang(image, version)
            // Every other image has no runtime-version axis → no build args, guard skipped. A stray
            // non-empty version here is a contract violation (a versioned cell miswired onto a
            // non-versioned family), so fail loudly instead of silently dropping it. A typo'd family name
            // also lands here; `docker compose build <name>` then reds on "no such service" (also pinned by
            // the `every matrix image name is a real compose service` contract test).
            else -> {
                check(version.isEmpty()) { "image '$image' has no runtime-version axis but version='$version' was set" }
                Resolution(emptyList(), "none", "")
            }
        }

    private fun resolveRuby(version: String): Resolution {
        val rows = rows(rubyVersions, 2, 3)
        val default =
            rows.singleOrNull { it.getOrNull(2) == "default" }
                ?: error("exactly one row in $rubyVersions must be marked 'default'")
        val effective = version.ifEmpty { default[0] }
        val row = rows.singleOrNull { it[0] == effective } ?: error("unknown ruby version '$effective'")
        val args = if (effective == default[0]) emptyList() else listOf("--build-arg", "QD_BASE_IMAGE=${row[1]}")
        return Resolution(args, "ruby", effective)
    }

    private fun resolveClang(
        image: String,
        version: String,
    ): Resolution {
        val defaultClang = parseEnv(image)["CLANG"] ?: error("$image.env has no CLANG default")
        val effective = version.ifEmpty { defaultClang }
        val os =
            rows(clangVersions, 2, 2).singleOrNull { it[0] == effective }?.get(1)
                ?: error("unknown clang major '$effective' (not in clang-versions.txt)")
        val args =
            if (effective == defaultClang) {
                emptyList()
            } else {
                val base =
                    rows(debianBases, 2, 2).singleOrNull { it[0] == os }?.get(1)
                        ?: error("no debian-bases row for '$os'")
                listOf(
                    "--build-arg",
                    "CLANG=$effective",
                    "--build-arg",
                    "CLANG_OS=$os",
                    "--build-arg",
                    "QD_BASE_IMAGE=$base",
                )
            }
        return Resolution(args, "clang", effective)
    }

    private fun parseEnv(image: String): Map<String, String> =
        Files
            .readAllLines(imagesDir.resolve("$image.env"))
            .mapNotNull { line ->
                val t = line.substringBefore('#').trim()
                if ('=' in t) t.substringBefore('=').trim() to t.substringAfter('=').trim() else null
            }.toMap()

    /** Rows of a version file, comments/blanks stripped, arity-validated before any positional index. */
    private fun rows(
        file: Path,
        minCols: Int,
        maxCols: Int,
    ): List<List<String>> {
        val allLines =
            try {
                Files.readAllLines(file)
            } catch (e: java.nio.file.NoSuchFileException) {
                error("version file ${file.fileName} not found: ${e.message}")
            }
        if (allLines.isEmpty()) {
            error("${file.fileName} is empty")
        }
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
}
