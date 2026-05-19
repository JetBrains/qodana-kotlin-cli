package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.env.QodanaEnv
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

object NugetConfig {
    private val logger = LoggerFactory.getLogger(NugetConfig::class.java)

    private const val QODANA_NUGET_URL = "QODANA_NUGET_URL"
    private const val QODANA_NUGET_USER = "QODANA_NUGET_USER"
    private const val QODANA_NUGET_PASSWORD = "QODANA_NUGET_PASSWORD"
    private const val QODANA_NUGET_NAME = "QODANA_NUGET_NAME"

    fun isNeeded(getEnv: (String) -> String? = System::getenv): Boolean =
        getEnv(QodanaEnv.DOCKER)?.isNotBlank() == true &&
            getEnv(QODANA_NUGET_URL)?.isNotBlank() == true &&
            getEnv(QODANA_NUGET_USER)?.isNotBlank() == true &&
            getEnv(QODANA_NUGET_PASSWORD)?.isNotBlank() == true

    fun prepare(
        homeDir: Path,
        getEnv: (String) -> String? = System::getenv,
    ) {
        val nugetDir = homeDir.resolve(".nuget").resolve("NuGet")
        Files.createDirectories(nugetDir)

        val configPath = nugetDir.resolve("NuGet.Config")
        val sourceName = getEnv(QODANA_NUGET_NAME)?.takeIf { it.isNotBlank() } ?: "qodana"
        val url = getEnv(QODANA_NUGET_URL)
        val user = getEnv(QODANA_NUGET_USER)
        val password = getEnv(QODANA_NUGET_PASSWORD)

        val config =
            """
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

    fun unsetVariables(): Map<String, String> {
        // The JVM cannot mutate process-level environment variables in place.
        // Returning explicit empty overrides lets child processes run without
        // inherited private NuGet credentials.
        logger.debug("NuGet variables should not be passed to child processes")
        return mapOf(
            QODANA_NUGET_URL to "",
            QODANA_NUGET_USER to "",
            QODANA_NUGET_PASSWORD to "",
            QODANA_NUGET_NAME to "",
        )
    }
}
