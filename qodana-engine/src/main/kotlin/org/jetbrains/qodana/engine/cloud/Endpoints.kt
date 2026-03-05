package org.jetbrains.qodana.engine.cloud

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class CloudEndpoints(
    @JsonProperty("LintersApiUrl")
    val lintersApiUrl: String,
    @JsonProperty("CloudApiUrl")
    val cloudApiUrl: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiVersionDescription(
    val version: String = "",
    val url: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiEndpointDescription(
    val versions: List<ApiVersionDescription> = emptyList(),
)

