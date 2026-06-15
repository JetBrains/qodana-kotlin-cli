package org.jetbrains.qodana.images.dist

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The new per-linter feed is ONE JSON object per product (not the engine's
 * `List<Product>`). Field names mirror qodana-engine's ProductFeed.kt so the
 * two parsers stay congruent (engine `ReleaseInfo`/`ReleaseDownloadInfo` -> `Release`/`ReleaseDownload`).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductFeed(
    @JsonProperty("Code") val code: String = "",
    @JsonProperty("Releases") val releases: List<Release> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Release(
    @JsonProperty("Date") val date: String = "",
    @JsonProperty("Type") val type: String = "",
    @JsonProperty("Version") val version: String? = null,
    @JsonProperty("MajorVersion") val majorVersion: String? = null,
    @JsonProperty("Build") val build: String? = null,
    @JsonProperty("Downloads") val downloads: Map<String, ReleaseDownload>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReleaseDownload(
    @JsonProperty("Link") val link: String = "",
    @JsonProperty("ChecksumLink") val checksumLink: String = "",
    @JsonProperty("Size") val size: Long = 0,
)
