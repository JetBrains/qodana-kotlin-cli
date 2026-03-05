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

    fun build(context: ScanContext, product: IdeProduct? = null): List<String> = buildList {
        // --config (effective configuration dir)
        context.runtime.effectiveConfigDir?.let {
            add("--config")
            add(it.toString())
        }

        // --save-report (container only)
        if (context.docker.image != null && context.report.saveReport) {
            add("--save-report")
        }

        // --only-directory
        context.runtime.onlyDirectory?.let {
            add("--only-directory")
            add(it)
        }

        // --profile-name / --profile-path
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

        // --run-promo
        context.yaml?.runPromoInspections?.let {
            add("--run-promo")
            add(it)
        }

        // --script (only if non-default)
        context.runtime.script.let {
            if (it.isNotEmpty() && it != "default") {
                add("--script")
                add(it)
            }
        }

        // --baseline / --baseline-include-absent / --fail-threshold
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

        // Fixes — matches Go: only for native mode on 233+ or container mode
        val isNative = !context.nativeMode.not()
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

        // Container-specific args
        if (!context.nativeMode) {
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
                add(it.toString())
            }
            if (context.report.outputFormats.any { it == ReportOptions.OutputFormat.CODE_CLIMATE }) {
                add("--code-climate")
            }
            context.runtime.properties.forEach { (key, value) ->
                add("--property=$key=$value")
            }
        } else if (product != null && product.is251orNewer()) {
            // Native mode on 251+: --config-dir
            context.runtime.effectiveConfigDir?.let {
                add("--config-dir")
                add(it.toString())
            }
        }
    }
}
