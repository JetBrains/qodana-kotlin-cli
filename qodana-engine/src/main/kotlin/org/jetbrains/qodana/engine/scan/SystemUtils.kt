package org.jetbrains.qodana.engine.scan

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

object SystemUtils {
    private const val GITHUB_RELEASES_URL =
        "https://api.github.com/repos/JetBrains/qodana-cli/releases/latest"

    fun isHomeDirectory(path: String): Boolean {
        val home = System.getProperty("user.home") ?: return false
        return try {
            val homePath = Path.of(home).toRealPath()
            val checkPath = Path.of(path).toRealPath()
            homePath == checkPath
        } catch (_: Exception) {
            false
        }
    }

    fun getScanStages(): List<String> {
        val stages =
            listOf(
                "Preparing Qodana Docker images",
                "Starting the analysis engine",
                "Opening the project",
                "Configuring the project",
                "Analyzing the project",
                "Preparing the report",
            )
        val total = stages.size + 1
        return stages.mapIndexed { index, stage -> "[${index + 1}/$total] $stage" }
    }

    fun checkForUpdates(
        currentVersion: String,
        getLatestVersion: () -> String = ::fetchLatestVersion,
    ): String? {
        if (currentVersion == "dev" || currentVersion.contains("nightly")) return null
        val latest = getLatestVersion()
        if (latest.isEmpty()) return null
        val latestClean = latest.removePrefix("v")
        return if (latestClean != currentVersion) latestClean else null
    }

    private fun fetchLatestVersion(): String {
        return try {
            val client =
                HttpClient
                    .newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(GITHUB_RELEASES_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return ""
            val tagPattern = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
            tagPattern.find(response.body())?.groupValues?.get(1) ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
