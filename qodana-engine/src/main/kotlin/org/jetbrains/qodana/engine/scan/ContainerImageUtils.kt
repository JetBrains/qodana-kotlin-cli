package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.product.Linters

object ContainerImageUtils {

    private val officialPrefixes = listOf("jetbrains/qodana", "registry.jetbrains.team/")
    private val privilegedSuffix = "-privileged"
    private val tokenEnvVars = setOf(
        "QODANA_TOKEN", "QODANA_LICENSE_ONLY_TOKEN",
        "QODANA_ENDPOINT_CLOUD_TOKEN", "QODANA_CLOUD_TOKEN",
    )
    private val unauthorizedPatterns = listOf(
        "unauthorized", "access denied", "denied", "forbidden",
    )

    fun isUnofficialLinter(image: String): Boolean {
        return officialPrefixes.none { image.startsWith(it) }
    }

    fun hasExactVersionTag(image: String): Boolean {
        val tag = image.substringAfter(":", "")
        if (tag.isEmpty() || tag == "latest") return false
        // A version tag looks like a year.minor pattern
        return tag.matches(Regex("\\d{4}\\.\\d.*"))
    }

    fun isCompatibleLinter(image: String): Boolean {
        if (!hasExactVersionTag(image)) return false
        val tag = image.substringAfter(":")
        return tag.startsWith(Linters.RELEASE_VERSION)
    }

    fun selectUser(image: String, requestedUser: String): String {
        if (requestedUser != "auto") return requestedUser

        val isPrivileged = officialPrefixes.any { prefix ->
            image.startsWith(prefix) && image.contains(privilegedSuffix)
        }
        return if (isPrivileged) "" else getDefaultUser()
    }

    fun getDefaultUser(): String {
        val uid = System.getenv("UID") ?: ""
        val gid = System.getenv("GID") ?: ""
        return if (uid.isNotEmpty() && gid.isNotEmpty()) "$uid:$gid" else ""
    }

    fun isDockerUnauthorizedError(errorMessage: String): Boolean {
        if (errorMessage.isEmpty()) return false
        val lower = errorMessage.lowercase()
        return unauthorizedPatterns.any { lower.contains(it) }
    }

    fun extractDockerVolumes(volume: String): Pair<String, String> {
        if (volume.isEmpty()) return "" to ""
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val parts = volume.split(":")
        return when {
            isWindows && parts.size >= 3 -> {
                // Windows: C:\path:/container/path
                "${parts[0]}:${parts[1]}" to parts[2]
            }
            !isWindows && parts.size >= 2 -> {
                parts[0] to parts[1]
            }
            else -> "" to ""
        }
    }

    fun generateDebugDockerRunCommand(
        image: String,
        user: String = "",
        env: List<String> = emptyList(),
        mounts: List<Pair<String, String>> = emptyList(),
        capAdd: List<String> = emptyList(),
        securityOpt: List<String> = emptyList(),
        autoRemove: Boolean = false,
        attachStdout: Boolean = false,
        attachStderr: Boolean = false,
        tty: Boolean = false,
        cmd: List<String> = emptyList(),
    ): String = buildString {
        append("docker run")
        if (autoRemove) append(" --rm")
        if (tty) append(" -it")
        if (attachStdout) append(" -a stdout")
        if (attachStderr) append(" -a stderr")
        if (user.isNotEmpty()) append(" -u $user")
        for (e in env) {
            if (!isTokenEnvVar(e)) append(" -e $e")
        }
        for ((source, target) in mounts) {
            append(" -v $source:$target")
        }
        for (cap in capAdd) {
            append(" --cap-add $cap")
        }
        for (opt in securityOpt) {
            append(" --security-opt $opt")
        }
        append(" $image")
        for (c in cmd) {
            append(" $c")
        }
    }

    private fun isTokenEnvVar(envEntry: String): Boolean {
        val name = envEntry.substringBefore("=")
        return tokenEnvVars.any { name.equals(it, ignoreCase = true) }
    }
}
