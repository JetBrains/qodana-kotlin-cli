package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.product.Linters
import java.util.Base64

object ContainerUtils {
    private val PRIVILEGED_PATTERN = Regex("^(jetbrains|registry\\.jetbrains\\.team)/.+-privileged.*$")

    /**
     * Selects the container user based on the image and user context.
     * "auto" resolves to default user for non-privileged images, empty for privileged.
     */
    fun selectUser(
        image: String,
        userFromContext: String,
        defaultUser: String = getDefaultUser(),
    ): String {
        if (userFromContext != "auto") return userFromContext
        return if (PRIVILEGED_PATTERN.matches(image)) "" else defaultUser
    }

    /**
     * Returns default user string (uid:gid) for running containers.
     */
    fun getDefaultUser(): String {
        val uid =
            try {
                ProcessBuilder("id", "-u")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            } catch (_: Exception) {
                "1000"
            }
        val gid =
            try {
                ProcessBuilder("id", "-g")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            } catch (_: Exception) {
                "1000"
            }
        return "$uid:$gid"
    }

    /**
     * Encodes Docker auth config to base64 JSON string.
     */
    fun encodeAuthToBase64(
        username: String,
        password: String,
        serverAddress: String = "",
    ): String {
        val json =
            buildString {
                append("{")
                append("\"username\":\"$username\",")
                append("\"password\":\"$password\"")
                if (serverAddress.isNotEmpty()) {
                    append(",\"serveraddress\":\"$serverAddress\"")
                }
                append("}")
            }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    /**
     * Extracts source and target from a Docker volume mount string.
     * Format: source:target[:options]
     */
    fun extractDockerVolumes(volume: String): Pair<String, String> {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        if (isWindows) {
            // Windows: C:\path:container or /path:container
            val parts = volume.split(":")
            return when {
                parts.size >= 3 && parts[0].length == 1 -> "${parts[0]}:${parts[1]}" to parts[2]
                parts.size >= 2 -> parts[0] to parts[1]
                else -> "" to ""
            }
        }
        // Unix
        val parts = volume.split(":")
        return when {
            parts.size >= 2 -> parts[0] to parts[1]
            else -> "" to ""
        }
    }

    /**
     * Checks if a Docker error message indicates an authorization failure.
     */
    fun isDockerUnauthorizedError(message: String): Boolean {
        val lower = message.lowercase()
        return "unauthorized" in lower || "denied" in lower || "forbidden" in lower
    }

    /**
     * Checks if a linter image is unofficial (not from JetBrains).
     */
    fun isUnofficialLinter(image: String): Boolean = !image.startsWith("jetbrains/qodana")

    /**
     * Checks if a linter image has an exact version tag (not `:latest` and has `:`).
     */
    fun hasExactVersionTag(image: String): Boolean = ":" in image && ":latest" !in image

    /**
     * Checks if a linter image is compatible with the current release version.
     */
    fun isCompatibleLinter(image: String): Boolean = Linters.RELEASE_VERSION in image

    data class ImageCheckResult(
        val isUnofficial: Boolean,
        val hasExactVersion: Boolean,
        val isCompatible: Boolean,
    )

    /**
     * Performs all image checks. Skips for nightly/dev versions.
     */
    fun checkImage(
        image: String,
        currentVersion: String = Linters.RELEASE_VERSION,
    ): ImageCheckResult? {
        if (currentVersion == "dev" || currentVersion.contains("nightly")) return null
        return ImageCheckResult(
            isUnofficial = isUnofficialLinter(image),
            hasExactVersion = hasExactVersionTag(image),
            isCompatible = isCompatibleLinter(image),
        )
    }

    /**
     * Generates a debug docker run command string, filtering out token env vars.
     */
    fun generateDebugDockerRunCommand(
        image: String,
        envVars: List<String> = emptyList(),
        volumes: List<String> = emptyList(),
        user: String = "",
        capabilities: List<String> = emptyList(),
        securityOpts: List<String> = emptyList(),
        args: List<String> = emptyList(),
        tty: Boolean = false,
    ): String =
        buildString {
            append("docker run --rm -a stdout -a stderr")
            if (tty) append(" -it")
            if (user.isNotEmpty()) append(" -u $user")
            for (env in envVars) {
                // Filter out QODANA_TOKEN unless it's a license-related var
                if ("QODANA_TOKEN" in env && "QodanaLicense" !in env && "QodanaLicenseOnlyToken" !in env) continue
                append(" -e $env")
            }
            for (vol in volumes) {
                append(" -v $vol")
            }
            for (cap in capabilities) {
                append(" --cap-add $cap")
            }
            for (sec in securityOpts) {
                append(" --security-opt $sec")
            }
            append(" $image")
            for (arg in args) {
                append(" $arg")
            }
        }
}
