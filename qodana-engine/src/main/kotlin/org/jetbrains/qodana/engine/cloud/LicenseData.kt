package org.jetbrains.qodana.engine.cloud

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class LicenseData(
    @JsonProperty("licenseId")
    val licenseId: String,
    @JsonProperty("licenseKey")
    val licenseKey: String,
    @JsonProperty("expirationDate")
    val expirationDate: String,
    @JsonProperty("projectIdHash")
    val projectIdHash: String,
    @JsonProperty("organisationIdHash")
    val organisationIdHash: String,
    @JsonProperty("licensePlan")
    val licensePlan: String,
)
