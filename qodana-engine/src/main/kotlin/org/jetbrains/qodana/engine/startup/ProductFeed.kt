package org.jetbrains.qodana.engine.startup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Product(
    @JsonProperty("code") val code: String,
    @JsonProperty("releases") val releases: List<ReleaseInfo> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReleaseInfo(
    @JsonProperty("date") val date: String = "",
    @JsonProperty("type") val type: String = "",
    @JsonProperty("downloads") val downloads: Map<String, ReleaseDownloadInfo>? = null,
    @JsonProperty("version") val version: String? = null,
    @JsonProperty("majorVersion") val majorVersion: String? = null,
    @JsonProperty("build") val build: String? = null,
    @JsonProperty("printableReleaseType") val printableReleaseType: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReleaseDownloadInfo(
    @JsonProperty("link") val link: String = "",
    @JsonProperty("size") val size: Long = 0,
    @JsonProperty("checksumLink") val checksumLink: String = "",
)
