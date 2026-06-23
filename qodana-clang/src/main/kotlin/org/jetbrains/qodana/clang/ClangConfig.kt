package org.jetbrains.qodana.clang

import org.jetbrains.qodana.core.model.QodanaYaml
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Result of [ClangConfig.processConfig]: the `--checks=` argument (may be empty) and the
 * `_clang-tidy` path to forward via `--config-file=` (null for `.clang-tidy` or no config).
 */
data class ClangChecksConfig(
    val checks: String,
    val configFile: String?,
)

/**
 * Builds clang-tidy's `--checks` argument from qodana.yaml includes/excludes and any discovered
 * `.clang-tidy`/`_clang-tidy` config. Ported from qodana-cli `clang/config.go` `processConfig`.
 */
object ClangConfig {
    private val logger = LoggerFactory.getLogger(ClangConfig::class.java)

    /**
     * Curated default checks: disable everything, enable useful categories, drop noisy individuals.
     * Excludes platform/codebase-specific families (llvmlibc/fuchsia/altera) by never enabling them.
     */
    val defaultChecks: String =
        listOf(
            "-*",
            "bugprone-*",
            "cert-*",
            "clang-analyzer-*",
            "clang-diagnostic-*",
            "concurrency-*",
            "misc-*",
            "modernize-*",
            "performance-*",
            "portability-*",
            "readability-*",
            "-misc-confusable-identifiers",
            "-misc-include-cleaner",
            "-misc-no-recursion",
            "-misc-non-private-member-variables-in-classes",
            "-modernize-use-trailing-return-type",
            "-readability-identifier-length",
            "-readability-magic-numbers",
        ).joinToString(",")

    fun processConfig(
        yaml: QodanaYaml?,
        projectDir: Path,
        searchRoot: Path? = ClangTidyConfig.envSearchRoot(),
    ): ClangChecksConfig {
        val includeRules = mutableListOf<String>()
        val excludeRules = mutableListOf<String>()
        // qodana-cli gates this parse behind a non-empty version/includes/excludes check; iterating
        // unconditionally is equivalent (empty lists yield empty overrides) and avoids a dead gate.
        if (yaml != null) {
            for (include in yaml.include) {
                val name = include.name.trim()
                if (name.startsWith("clion-")) continue
                if (name.contains("\"")) {
                    logger.warn("Skipping include rule with invalid characters: {}", name)
                    continue
                }
                includeRules.add(name)
            }
            for (exclude in yaml.exclude) {
                val name = exclude.name
                if (name.contains("\"")) {
                    logger.warn("Skipping exclude rule with invalid characters: {}", name)
                    continue
                }
                excludeRules.add("-$name")
            }
        }

        val configPath = ClangTidyConfig.find(projectDir, searchRoot)
        val hasConfig = configPath != null
        // clang-tidy's native walk only recognizes ".clang-tidy"; "_clang-tidy" must be forwarded.
        val configFile = configPath?.takeIf { it.fileName.toString() != ".clang-tidy" }?.toString()

        val overrides = (includeRules + excludeRules).joinToString(",")

        val checks =
            when {
                hasConfig && overrides.isNotEmpty() -> "--checks=$overrides"
                hasConfig -> "" // defer to the config file's own checks
                overrides.isNotEmpty() -> "--checks=$defaultChecks,$overrides"
                else -> "--checks=$defaultChecks"
            }
        return ClangChecksConfig(checks, configFile)
    }
}
