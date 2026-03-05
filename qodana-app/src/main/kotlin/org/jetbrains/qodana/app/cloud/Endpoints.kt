package org.jetbrains.qodana.app.cloud

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class CloudEndpoints(
    @JsonProperty("LintersApiUrl")
    val lintersApiUrl: String,
    @JsonProperty("CloudApiUrl")
    val cloudApiUrl: String,
)
