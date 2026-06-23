package org.jetbrains.qodana.clang

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Discovers the nearest clang-tidy config (`.clang-tidy`, else `_clang-tidy`) by walking parent
 * directories, mirroring clang-tidy's own `FileOptionsBaseProvider` walk. Ported from qodana-cli
 * `clang/config.go` `findClangTidyConfig`.
 */
object ClangTidyConfig {
    private val logger = LoggerFactory.getLogger(ClangTidyConfig::class.java)

    const val SEARCH_ROOT_ENV = "QODANA_CLANG_TIDY_SEARCH_ROOT"

    // `.clang-tidy` is preferred over `_clang-tidy` within a directory (iteration order).
    private val configNames = listOf(".clang-tidy", "_clang-tidy")

    /**
     * Nearest config path reachable from [startDir] toward the filesystem root, or null. The result
     * is symlink-resolved. [searchRoot] caps the walk (inclusive); intended for tests, production
     * leaves it unset.
     *
     * @throws IllegalArgumentException when [searchRoot] is set and not an ancestor of [startDir].
     */
    fun find(
        startDir: Path,
        searchRoot: Path? = envSearchRoot(),
    ): Path? {
        val start = resolvePath(startDir)
        val root = searchRoot?.let { resolvePath(it) }
        if (root != null) {
            logger.warn(
                "{} is set — intended for tests only; restricting .clang-tidy discovery to {}",
                SEARCH_ROOT_ENV,
                root,
            )
            require(start.startsWith(root)) { "$SEARCH_ROOT_ENV=$root is not an ancestor of start dir $start" }
        }

        // Walk parents until a config is found (return candidate) or we pass the search root /
        // filesystem root (loop exits, return null) — two returns keeps detekt's ReturnCount happy.
        var dir: Path? = start
        while (dir != null) {
            val current = dir
            for (name in configNames) {
                val candidate = current.resolve(name)
                if (Files.exists(candidate)) return candidate
            }
            dir = if (root != null && current == root) null else current.parent
        }
        return null
    }

    internal fun envSearchRoot(getenv: (String) -> String? = System::getenv): Path? =
        getenv(SEARCH_ROOT_ENV)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    // EvalSymlinks-else-Abs: toRealPath needs an existing path; fall back for non-existent ones.
    internal fun resolvePath(p: Path): Path =
        try {
            p.toRealPath()
        } catch (ignored: IOException) {
            p.toAbsolutePath().normalize()
        }
}
