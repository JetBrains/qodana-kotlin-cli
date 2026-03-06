package org.jetbrains.qodana.engine.cloud

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

data class CloudEndpoints(
    val lintersApiUrl: String,
    val cloudApiUrl: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiVersionDescription(
    @JsonProperty("version")
    val version: String = "",
    @JsonProperty("url")
    val url: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiEndpointDescription(
    @JsonProperty("versions")
    val versions: List<ApiVersionDescription> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiDescriptions(
    @JsonProperty("api")
    @JsonAlias("API")
    val api: ApiEndpointDescription = ApiEndpointDescription(),
    @JsonProperty("linters")
    @JsonAlias("Linters")
    val linters: ApiEndpointDescription = ApiEndpointDescription(),
)
