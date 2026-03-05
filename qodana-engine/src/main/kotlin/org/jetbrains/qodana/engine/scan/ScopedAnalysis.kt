package org.jetbrains.qodana.engine.scan

/**
 * Utilities for building scoped and reverse-scoped analysis script strings and stage configurations.
 */
object ScopedAnalysis {

    /**
     * Builds the script string for scoped analysis.
     */
    fun scopedScript(scopeFile: String): String = "scoped:$scopeFile"

    /**
     * Builds the script string for reverse-scoped analysis with a stage tag.
     */
    fun reverseScopedScript(stage: ReverseStage, scopeFile: String): String =
        "reverse-scoped:${stage.tag},$scopeFile"

    enum class ReverseStage(val tag: String) {
        NEW("NEW"),
        OLD("OLD"),
        FIXES("FIXES"),
    }

    enum class FinishStrategy {
        /** Finish after analysis. */
        ANY,
        /** Finish only if fixable issues found. */
        FIXABLE,
        /** Never finish early; always run all stages. */
        NEVER,
    }

    data class StageConfig(
        val script: String,
        val skipResult: Boolean = false,
        val skipCoverageComputation: Boolean = false,
        val baselineSarif: String? = null,
        val reducedScopePath: String? = null,
        val finishStrategy: FinishStrategy = FinishStrategy.ANY,
        val resultsSubDir: String? = null,
    )

    // --- Scoped Analysis Stages ---

    fun firstStageOfScoped(scopeFile: String): StageConfig = StageConfig(
        script = scopedScript(scopeFile),
        skipResult = true,
        skipCoverageComputation = true,
        resultsSubDir = "start",
    )

    fun secondStageOfScoped(scopeFile: String, startSarif: String): StageConfig = StageConfig(
        script = scopedScript(scopeFile),
        baselineSarif = startSarif,
        resultsSubDir = "end",
    )

    // --- Reverse Scoped Analysis Stages ---

    fun firstStageOfReverseScoped(scopeFile: String, reducedScopePath: String? = null): StageConfig = StageConfig(
        script = reverseScopedScript(ReverseStage.NEW, scopeFile),
        finishStrategy = FinishStrategy.ANY,
        reducedScopePath = reducedScopePath,
        resultsSubDir = "end",
    )

    fun secondStageOfReverseScoped(
        scopeFile: String,
        startSarif: String,
        applyFixes: Boolean = false,
        cleanup: Boolean = false,
    ): StageConfig = StageConfig(
        script = reverseScopedScript(ReverseStage.OLD, scopeFile),
        baselineSarif = startSarif,
        finishStrategy = if (applyFixes || cleanup) FinishStrategy.FIXABLE else FinishStrategy.NEVER,
        resultsSubDir = "start",
    )

    fun thirdStageOfReverseScoped(scopeFile: String, startSarif: String): StageConfig = StageConfig(
        script = reverseScopedScript(ReverseStage.FIXES, scopeFile),
        baselineSarif = startSarif,
        finishStrategy = FinishStrategy.NEVER,
        resultsSubDir = "fixes",
    )
}
