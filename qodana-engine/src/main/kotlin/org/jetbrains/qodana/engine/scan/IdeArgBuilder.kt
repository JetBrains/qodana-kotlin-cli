package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.engine.model.ExecutionProfile
import org.jetbrains.qodana.engine.model.ReportOptions
import org.jetbrains.qodana.engine.model.ScanContext

private const val CONTAINER_REPOSITORY_ROOT = "/data/project"
private const val CONTAINER_GLOBAL_CONFIG_DIR = "/data/config"

internal interface ExecutionIdeArgBuilder {
    fun build(
        context: ScanContext,
        product: IdeProduct? = null,
    ): List<String>
}

private abstract class BaseExecutionIdeArgBuilder : ExecutionIdeArgBuilder {
    final override fun build(
        context: ScanContext,
        product: IdeProduct?,
    ): List<String> =
        buildList {
            addCommonArgs(context)
            addFixStrategy(context, product)
            addModeSpecificArgs(context)
        }

    protected abstract fun MutableList<String>.addModeSpecificArgs(context: ScanContext)

    protected open fun MutableList<String>.addCommonArgs(context: ScanContext) {
        context.runtime.customConfigName?.takeIf { it.isNotBlank() }?.let {
            add("--config")
            add(it)
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
    }

    protected open fun MutableList<String>.addFixStrategy(
        context: ScanContext,
        product: IdeProduct?,
    ) {
        val applyFixes =
            context.runtime.applyFixes ||
                context.runtime.fixesStrategy?.lowercase() == "apply"
        val cleanup =
            context.runtime.cleanup ||
                context.runtime.fixesStrategy?.lowercase() == "cleanup"
        if (applyFixes) {
            add("--fixes-strategy")
            add("apply")
        } else if (cleanup) {
            add("--fixes-strategy")
            add("cleanup")
        }
    }
}

private class NativeExecutionIdeArgBuilder : BaseExecutionIdeArgBuilder() {
    override fun MutableList<String>.addFixStrategy(
        context: ScanContext,
        product: IdeProduct?,
    ) {
        if (product != null && product.is233orNewer()) {
            if (context.runtime.applyFixes) {
                add("--apply-fixes")
                return
            }
            if (context.runtime.cleanup) {
                add("--cleanup")
                return
            }
        }
        val applyFixes =
            context.runtime.applyFixes ||
                context.runtime.fixesStrategy?.lowercase() == "apply"
        val cleanup =
            context.runtime.cleanup ||
                context.runtime.fixesStrategy?.lowercase() == "cleanup"
        if (applyFixes) {
            add("--fixes-strategy")
            add("apply")
        } else if (cleanup) {
            add("--fixes-strategy")
            add("cleanup")
        }
    }

    override fun MutableList<String>.addModeSpecificArgs(context: ScanContext) = Unit
}

private abstract class BaseContainerExecutionIdeArgBuilder : BaseExecutionIdeArgBuilder() {
    override fun MutableList<String>.addModeSpecificArgs(context: ScanContext) {
        if (context.report.saveReport) {
            add("--save-report")
        }

        val relativeProjectDir =
            runCatching {
                context.paths.repositoryRoot
                    .relativize(context.paths.projectDir)
                    .toString()
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

private class InDockerExecutionIdeArgBuilder : BaseContainerExecutionIdeArgBuilder()

private class DockerLauncherExecutionIdeArgBuilder : BaseContainerExecutionIdeArgBuilder()

/**
 * Builds IDE command arguments by dispatching to profile-specific builders.
 * This object only routes to execution-profile classes and does not contain mode branching logic itself.
 */
object IdeArgBuilder {
    private val builders: Map<ExecutionProfile.Kind, ExecutionIdeArgBuilder> =
        mapOf(
            ExecutionProfile.Kind.NATIVE to NativeExecutionIdeArgBuilder(),
            ExecutionProfile.Kind.IN_DOCKER to InDockerExecutionIdeArgBuilder(),
            ExecutionProfile.Kind.DOCKER_LAUNCHER to DockerLauncherExecutionIdeArgBuilder(),
        )

    fun build(
        context: ScanContext,
        product: IdeProduct? = null,
    ): List<String> {
        val builder = builders.getValue(context.executionProfile.kind)
        return builder.build(context, product)
    }
}
