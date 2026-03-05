package org.jetbrains.qodana.engine.startup

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Product(
    @JsonProperty("Code") val code: String = "",
    @JsonProperty("Releases") val releases: List<ReleaseInfo> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReleaseInfo(
    @JsonProperty("Date") val date: String = "",
    @JsonProperty("Type") val type: String = "",
    @JsonProperty("Downloads") val downloads: Map<String, ReleaseDownloadInfo>? = null,
    @JsonProperty("Version") val version: String? = null,
    @JsonProperty("MajorVersion") val majorVersion: String? = null,
    @JsonProperty("Build") val build: String? = null,
    @JsonProperty("PrintableReleaseType") val printableReleaseType: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ReleaseDownloadInfo(
    @JsonProperty("Link") val link: String = "",
    @JsonProperty("Size") val size: Long = 0,
    @JsonProperty("ChecksumLink") val checksumLink: String = "",
)
