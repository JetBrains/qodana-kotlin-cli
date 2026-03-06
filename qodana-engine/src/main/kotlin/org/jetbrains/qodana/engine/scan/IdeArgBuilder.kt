package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.engine.model.ReportOptions
import org.jetbrains.qodana.engine.model.ScanContext

/**
 * Constructs IDE command-line arguments from [ScanContext].
 * Mirrors Go's `GetIdeArgs()` in `ide.go`.
 *
 * Does NOT include the IDE binary, subcommand (inspect/qodana),
 * or positional args (projectDir/resultsDir) — those are added by
 * [NativeScan.getIdeRunCommand].
 */
object IdeArgBuilder {
    private const val CONTAINER_REPOSITORY_ROOT = "/data/project"
    private const val CONTAINER_GLOBAL_CONFIG_DIR = "/data/config"

    fun build(context: ScanContext, product: IdeProduct? = null): List<String> = buildList {
        context.runtime.customConfigName?.takeIf { it.isNotBlank() }?.let {
            add("--config")
            add(it)
        }

        if (context.analysisMode == org.jetbrains.qodana.engine.model.AnalysisMode.CONTAINER && context.report.saveReport) {
            add("--save-report")
        }

        context.runtime.onlyDirectory?.let {
            add("--only-directory")
            add(it)
        }

        if (context.runtime.disableSanity) {
            add("--disable-sanity")
        }

        context.profile?.let { profile ->
            profile.name?.let {
                add("--profile-name")
                add(it)
            }
            profile.path?.let {
                add("--profile-path")
                add(it)
            }
        }

        (context.runtime.runPromo ?: context.yaml?.runPromoInspections)?.let {
            add("--run-promo")
            add(it)
        }

        context.runtime.script.let {
            if (it.isNotEmpty() && it != "default") {
                add("--script")
                add(it)
            }
        }

        context.report.baselinePath?.let {
            add("--baseline")
            add(it.toString())
        }
        if (context.report.baselineIncludeAbsent) {
            add("--baseline-include-absent")
        }
        context.runtime.failThreshold?.let {
            add("--fail-threshold")
            add(it.toString())
        }

        val isNative = context.analysisMode == org.jetbrains.qodana.engine.model.AnalysisMode.NATIVE
        if (product != null && product.is233orNewer() && isNative) {
            if (context.runtime.applyFixes) {
                add("--apply-fixes")
            } else if (context.runtime.cleanup) {
                add("--cleanup")
            }
        } else {
            // Pre-233 or container: use --fixes-strategy
            val applyFixes = context.runtime.applyFixes ||
                context.runtime.fixesStrategy?.lowercase() == "apply"
            val cleanup = context.runtime.cleanup ||
                context.runtime.fixesStrategy?.lowercase() == "cleanup"
            if (applyFixes) {
                add("--fixes-strategy")
                add("apply")
            } else if (cleanup) {
                add("--fixes-strategy")
                add("cleanup")
            }
        }

        if (context.analysisMode == org.jetbrains.qodana.engine.model.AnalysisMode.CONTAINER) {
            val relativeProjectDir = runCatching {
                context.paths.repositoryRoot.relativize(context.paths.projectDir).toString()
            }.getOrDefault("")
            if (relativeProjectDir.isNotBlank() && relativeProjectDir != ".") {
                add("--project-dir")
                add("$CONTAINER_REPOSITORY_ROOT/$relativeProjectDir")
                add("--repository-root")
                add(CONTAINER_REPOSITORY_ROOT)
            }

            context.runtime.diffStart?.let {
                if (context.runtime.script == "default") {
                    add("--diff-start")
                    add(it)
                }
            }
            context.runtime.diffEnd?.let {
                if (context.runtime.script == "default") {
                    add("--diff-end")
                    add(it)
                }
            }

            if (context.runtime.forceLocalChangesScript && context.runtime.script == "default") {
                add("--force-local-changes-script")
            }

            context.runtime.analysisId?.let {
                add("--analysis-id")
                add(it)
            }
            context.runtime.coverageDir?.let {
                add("--coverage-dir")
                add(it.toString())
            }
            context.runtime.jvmDebugPort?.let {
                if (it > 0) {
                    add("--jvm-debug-port")
                    add(it.toString())
                }
            }
            context.runtime.globalConfigDir?.let {
                add("--global-config-dir")
                add(CONTAINER_GLOBAL_CONFIG_DIR)
            }
            context.runtime.globalConfigId?.let {
                add("--global-config-id")
                add(it)
            }
            if (context.report.outputFormats.any { it == ReportOptions.OutputFormat.CODE_CLIMATE }) {
                add("--code-climate")
            }
            context.runtime.properties.forEach { (key, value) ->
                add("--property=$key=$value")
            }
            context.runtime.propertyFlags.forEach { flag ->
                add("--property=$flag")
            }

            if (context.runtime.noStatistics) {
                add("--no-statistics")
            }

            when (context.linter) {
                "qodana-cdnet", "qodana-dotnet" -> {
                    context.runtime.cdnetSolution?.let { addAll(listOf("--solution", it)) }
                    context.runtime.cdnetProject?.let { addAll(listOf("--project", it)) }
                    context.runtime.cdnetConfiguration?.let { addAll(listOf("--configuration", it)) }
                    context.runtime.cdnetPlatform?.let { addAll(listOf("--platform", it)) }
                    if (context.runtime.cdnetNoBuild) add("--no-build")
                }

                "qodana-clang", "qodana-cpp" -> {
                    context.runtime.clangCompileCommands?.let { addAll(listOf("--compile-commands", it)) }
                    context.runtime.clangArgs?.let { addAll(listOf("--clang-args", it)) }
                }
            }
        }
    }
}
