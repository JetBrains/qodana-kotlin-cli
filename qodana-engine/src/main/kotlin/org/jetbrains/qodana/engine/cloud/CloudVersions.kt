package org.jetbrains.qodana.engine.cloud

import java.io.File

data class ApiVersion(val major: Int, val minor: Int)

class ApiVersionMismatchError(
    val apiKind: String,
    val supportedVersions: List<String>,
) : Exception(
    "failed to find supported API. Available $apiKind API: $supportedVersions. " +
        "Required major version: $REQUIRED_MAJOR_VERSION. " +
        "Minimum required minor version: $MINIMUM_REQUIRED_MINOR_VERSION"
)

const val REQUIRED_MAJOR_VERSION = 1
const val MINIMUM_REQUIRED_MINOR_VERSION = 0

fun toCloudVersion(version: String): Result<ApiVersion> {
    val parts = version.split(".")
    if (parts.size != 2) return Result.failure(IllegalArgumentException("invalid version format"))
    val major = parts[0].toIntOrNull() ?: return Result.failure(IllegalArgumentException("invalid major version"))
    val minor = parts[1].toIntOrNull() ?: return Result.failure(IllegalArgumentException("invalid minor version"))
    return Result.success(ApiVersion(major, minor))
}

fun selectSupportedVersion(descriptions: List<ApiVersionDescription>): String {
    for (desc in descriptions) {
        val version = toCloudVersion(desc.version).getOrNull() ?: continue
        if (version.major == REQUIRED_MAJOR_VERSION && version.minor >= MINIMUM_REQUIRED_MINOR_VERSION) {
            return desc.url
        }
    }
    return ""
}

fun extractVersions(descriptions: List<ApiVersionDescription>): List<String> =
    descriptions.map { it.version }

fun getCloudTeamsPageUrl(rootUrl: String, origin: String, path: String): String {
    val name = File(path).name
    return "${rootUrl.trimEnd('/')}/?origin=$origin&name=$name"
}

fun parseProjectName(json: String): Result<String> {
    return try {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val tree = mapper.readTree(json)
        val name = tree["name"]?.asText() ?: ""
        Result.success(name)
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("response '$json': ${e.message}"))
    }
}

fun getReportUrl(resultsDir: String): String {
    val file = File(resultsDir, "open-in-ide.json")
    if (!file.exists()) return ""
    return try {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val tree = mapper.readTree(file)
        tree["cloud"]?.get("url")?.asText() ?: ""
    } catch (_: Exception) {
        ""
    }
}

fun parseRawUrl(rawUrl: String): Result<String> {
    if (rawUrl.isBlank()) return Result.failure(IllegalArgumentException("empty URL"))
    val urlWithScheme = if (!rawUrl.contains("://")) "https://$rawUrl" else rawUrl
    return try {
        val uri = java.net.URI(urlWithScheme)
        val host = uri.host ?: return Result.failure(IllegalArgumentException("no host in URL: $rawUrl"))
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val scheme = uri.scheme ?: "https"
        Result.success("$scheme://$host$port")
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("invalid URL: $rawUrl"))
    }
}
