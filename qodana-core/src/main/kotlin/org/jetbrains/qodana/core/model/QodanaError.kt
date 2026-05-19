package org.jetbrains.qodana.core.model

sealed interface QodanaError {
    val message: String

    data class Network(
        val url: String,
        val cause: String,
    ) : QodanaError {
        override val message: String get() = "Network error accessing $url: $cause"
    }

    data class Auth(
        val reason: String,
    ) : QodanaError {
        override val message: String get() = "Authentication error: $reason"
    }

    data class Docker(
        val reason: String,
    ) : QodanaError {
        override val message: String get() = "Docker error: $reason"
    }

    data class ToolMissing(
        val tool: String,
        val platform: String,
    ) : QodanaError {
        override val message: String get() = "$tool is not available for $platform"
    }

    data class InvalidConfig(
        val path: String,
        val reason: String,
    ) : QodanaError {
        override val message: String get() = "Invalid configuration in $path: $reason"
    }

    data class ReportProcessing(
        val reason: String,
    ) : QodanaError {
        override val message: String get() = "Report processing error: $reason"
    }

    data class ProcessFailed(
        val command: String,
        val exitCode: Int,
        val stderr: String,
    ) : QodanaError {
        override val message: String get() = "Process '$command' failed with exit code $exitCode: $stderr"
    }

    data class LinterError(
        val linter: String,
        val reason: String,
    ) : QodanaError {
        override val message: String get() = "Linter '$linter' error: $reason"
    }
}
