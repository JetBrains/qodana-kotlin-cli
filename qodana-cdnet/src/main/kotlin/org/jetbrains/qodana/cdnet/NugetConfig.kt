package org.jetbrains.qodana.cdnet

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object NugetConfig {
    private val logger = LoggerFactory.getLogger(NugetConfig::class.java)

    private const val QODANA_NUGET_URL = "QODANA_NUGET_URL"
    private const val QODANA_NUGET_USER = "QODANA_NUGET_USER"
    private const val QODANA_NUGET_PASSWORD = "QODANA_NUGET_PASSWORD"
    private const val QODANA_NUGET_NAME = "QODANA_NUGET_NAME"

    fun isNeeded(): Boolean {
        return System.getenv(QODANA_NUGET_URL)?.isNotBlank() == true
            && System.getenv(QODANA_NUGET_USER)?.isNotBlank() == true
            && System.getenv(QODANA_NUGET_PASSWORD)?.isNotBlank() == true
    }

    fun prepare(homeDir: Path) {
        val nugetDir = homeDir.resolve(".nuget").resolve("NuGet")
        Files.createDirectories(nugetDir)

        val configPath = nugetDir.resolve("NuGet.Config")
        val sourceName = System.getenv(QODANA_NUGET_NAME)?.takeIf { it.isNotBlank() } ?: "qodana"
        val url = System.getenv(QODANA_NUGET_URL)
        val user = System.getenv(QODANA_NUGET_USER)
        val password = System.getenv(QODANA_NUGET_PASSWORD)

        val config = """
            |<?xml version="1.0" encoding="utf-8"?>
            |<configuration>
            |  <packageSources>
            |    <clear />
            |    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" />
            |    <add key="$sourceName" value="$url" />
            |  </packageSources>
            |  <packageSourceCredentials>
            |    <$sourceName>
            |      <add key="Username" value="$user" />
            |      <add key="ClearTextPassword" value="$password" />
            |    </$sourceName>
            |  </packageSourceCredentials>
            |</configuration>
        """.trimMargin()

        Files.writeString(configPath, config)
    }

    fun unsetVariables() {
        // Note: System.getenv() returns an unmodifiable map in Java
        // We can't actually unset env vars in the JVM, but we can clear them
        // for child processes by not passing them in ProcessSpec.env
        logger.debug("NuGet variables should not be passed to child processes")
    }
}
