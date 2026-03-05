package org.jetbrains.qodana.core.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = false)
data class QodanaYaml(
    val version: String = "1.0",
    val profile: YamlProfile = YamlProfile(),
    val failThreshold: Int? = null,
    val script: YamlScript = YamlScript(),
    val exclude: List<InspectScope> = emptyList(),
    val include: List<InspectScope> = emptyList(),
    val linter: String? = null,
    val image: String? = null,
    val withinDocker: String? = null,
    val ide: String? = null,
    val bootstrap: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val plugins: List<YamlPlugin> = emptyList(),
    val dotnet: YamlDotNet? = null,
    @JsonProperty("projectJDK")
    val projectJdk: String? = null,
    val php: YamlPhp? = null,
    val cpp: YamlCpp? = null,
    val disableSanityInspections: String? = null,
    val fixesStrategy: String? = null,
    val runPromoInspections: String? = null,
    val includeAbsent: String? = null,
    val maxRuntimeNotifications: Int = 100,
    val failOnErrorNotification: Boolean = false,
    val failureConditions: YamlFailureConditions = YamlFailureConditions(),
    val licenseRules: List<YamlLicenseRule> = emptyList(),
    val dependencyIgnores: List<YamlDependencyIgnore> = emptyList(),
    val dependencyOverrides: List<YamlDependencyOverride> = emptyList(),
    val projectLicenses: List<YamlLicenseOverride> = emptyList(),
    val customDependencies: List<YamlCustomDependency> = emptyList(),
    val dependencySbomExclude: List<YamlDependencyIgnore> = emptyList(),
    val modulesToAnalyze: List<YamlModuleToAnalyze> = emptyList(),
    val analyzeDevDependencies: Boolean = false,
    val enablePackageSearch: Boolean = false,
    val raiseLicenseProblems: Boolean = false,
    val coverage: YamlCoverage = YamlCoverage(),
    val hardcodedPasswords: YamlHardcodedPasswords = YamlHardcodedPasswords(),
)

data class YamlProfile(
    val name: String = "",
    val path: String = "",
)

data class YamlScript(
    val name: String = "default",
    val parameters: Map<String, String> = emptyMap(),
)

data class InspectScope(
    val name: String = "",
    val paths: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
)

data class YamlPlugin(
    val id: String = "",
)

data class YamlDotNet(
    val solution: String? = null,
    val project: String? = null,
    val configuration: String? = null,
    val platform: String? = null,
    val frameworks: String? = null,
)

data class YamlPhp(
    val version: String? = null,
)

data class YamlCpp(
    val buildSystem: String? = null,
    val cmakePreset: String? = null,
)

data class YamlFailureConditions(
    val severityThresholds: YamlSeverityThresholds = YamlSeverityThresholds(),
    val testCoverageThresholds: YamlCoverageThresholds = YamlCoverageThresholds(),
)

data class YamlSeverityThresholds(
    val any: Int? = null,
    val critical: Int? = null,
    val high: Int? = null,
    val moderate: Int? = null,
    val low: Int? = null,
    val info: Int? = null,
)

data class YamlCoverageThresholds(
    val total: Int? = null,
    val fresh: Int? = null,
)

data class YamlLicenseRule(
    val keys: List<String> = emptyList(),
    val allowed: List<String> = emptyList(),
    val prohibited: List<String> = emptyList(),
)

data class YamlDependencyIgnore(
    val name: String = "",
)

data class YamlDependencyOverride(
    val name: String = "",
    val version: String = "",
    val url: String? = null,
    val licenses: List<YamlLicenseOverride> = emptyList(),
)

data class YamlLicenseOverride(
    val key: String = "",
    val url: String? = null,
)

data class YamlCustomDependency(
    val name: String = "",
    val version: String = "",
    val url: String? = null,
    val licenses: List<YamlLicenseOverride> = emptyList(),
)

data class YamlModuleToAnalyze(
    val name: String? = null,
)

data class YamlCoverage(
    val reportProblems: Boolean = true,
)

data class YamlHardcodedPasswords(
    val maxLineLength: Int = 1024,
    val maxPasswordLength: Int = 128,
)
