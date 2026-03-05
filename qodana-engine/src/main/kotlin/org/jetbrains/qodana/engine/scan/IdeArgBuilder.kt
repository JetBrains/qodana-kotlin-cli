package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.engine.model.ReportOptions
import org.jetbrains.qodana.engine.model.ScanContext

/**
 * Constructs IDE command-line arguments from [ScanContext].
 *
 * Argument categories (in order):
 * 1. Configuration  – profile, config, promo inspections
 * 2. Analysis Scope – directory constraints, diff range
 * 3. Baseline       – baseline path, include-absent, fail-threshold
 * 4. Fixes          – apply-fixes, cleanup, fixes-strategy
 * 5. DotNet         – solution, project, configuration, platform
 * 6. Container      – save-report, analysis-id, coverage, code-climate
 * 7. Properties     – arbitrary -D property pairs
 */
object IdeArgBuilder {

    fun build(context: ScanContext): List<String> = buildList {
        // Positional: sub-command
        add("inspect")
        add("qodana")

        // --- 1. Configuration ---
        context.yaml?.let { yaml ->
            yaml.runPromoInspections?.let { value ->
                add("--run-promo")
                add(value)
            }
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

        // --- 2. Analysis Scope ---
        context.runtime.onlyDirectory?.let {
            add("--only-directory")
            add(it)
        }
        context.runtime.diffStart?.let {
            add("--diff-start")
            add(it)
        }
        context.runtime.diffEnd?.let {
            add("--diff-end")
            add(it)
        }

        // --- 3. Baseline ---
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

        // --- 4. Fixes ---
        if (context.runtime.applyFixes) {
            add("--apply-fixes")
        }
        if (context.runtime.cleanup) {
            add("--cleanup")
        }
        context.runtime.fixesStrategy?.let {
            add("--fixes-strategy")
            add(it)
        }

        // --- 5. DotNet ---
        context.yaml?.dotnet?.let { dotnet ->
            dotnet.solution?.let {
                add("--solution")
                add(it)
            }
            dotnet.project?.let {
                add("--project")
                add(it)
            }
            dotnet.configuration?.let {
                add("--configuration")
                add(it)
            }
            dotnet.platform?.let {
                add("--platform")
                add(it)
            }
        }

        // --- 6. Container-specific ---
        if (context.report.saveReport) {
            add("--save-report")
        }
        context.runtime.analysisId?.let {
            add("--analysis-id")
            add(it)
        }
        context.runtime.coverageDir?.let {
            add("--coverage-dir")
            add(it.toString())
        }
        context.runtime.globalConfigDir?.let {
            add("--global-config-dir")
            add(it.toString())
        }
        if (context.report.outputFormats.any {
                it == ReportOptions.OutputFormat.CODE_CLIMATE
            }) {
            add("--code-climate")
        }

        // --- 7. Properties ---
        context.runtime.properties.forEach { (key, value) ->
            add("--property=$key=$value")
        }

        // Positional: project and results directories
        add(context.paths.projectDir.toString())
        add(context.paths.resultsDir.toString())
    }
}
