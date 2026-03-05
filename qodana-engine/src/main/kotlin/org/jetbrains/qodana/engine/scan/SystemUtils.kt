package org.jetbrains.qodana.engine.scan

import java.nio.file.Path
import kotlin.io.path.pathString

object SystemUtils {

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

    fun getScanStages(): List<String> = listOf(
        "Preparing Qodana Docker images",
        "Starting the IDE",
        "Opening the project",
        "Running the analysis",
        "Generating the report",
        "Preparing the report",
    )

    fun checkForUpdates(currentVersion: String, getLatestVersion: () -> String = ::fetchLatestVersion): String? {
        if (currentVersion == "dev" || currentVersion.contains("nightly")) return null
        val latest = getLatestVersion()
        if (latest.isEmpty()) return null
        val latestClean = latest.removePrefix("v")
        return if (latestClean != currentVersion) latestClean else null
    }

    private fun fetchLatestVersion(): String {
        // In production, this fetches from GitHub releases API
        // For now, return empty to avoid network calls
        return ""
    }
}
