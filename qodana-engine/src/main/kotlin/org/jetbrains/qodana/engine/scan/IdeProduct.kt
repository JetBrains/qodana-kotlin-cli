package org.jetbrains.qodana.engine.scan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.product.IntellijLinterProperties
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Represents a discovered IDE installation.
 * Mirrors Go's `Product` struct in `product_info.go`.
 */
data class IdeProduct(
    val name: String,
    val ideCode: String,
    val code: String,
    val version: String,
    val baseScriptName: String,
    val ideScript: String,
    val build: String,
    val home: String,
    val isEap: Boolean,
) {
    /** e.g. "2025.3" → "253", "2024.2" → "242" */
    fun getVersionBranch(): String {
        val versions = version.split(".")
        if (versions.size < 2) return "master"
        return "${versions[0].drop(2)}${versions[1]}"
    }

    fun isNotOlderThan(ver: Int): Boolean {
        val number = getVersionBranch().toIntOrNull() ?: return false
        return number >= ver
    }

    fun is242orNewer() = isNotOlderThan(242)

    fun is251orNewer() = isNotOlderThan(251)

    fun is233orNewer() = isNotOlderThan(233)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class IdeProductInfoJson(
    @JsonProperty("version") val version: String = "",
    @JsonProperty("buildNumber") val buildNumber: String = "",
    @JsonProperty("productCode") val productCode: String = "",
    @JsonProperty("versionSuffix") val versionSuffix: String = "",
)

/**
 * Discovers an IDE installation at [ideDir], matching Go's `GuessProduct()`.
 *
 * On macOS, [ideDir] is typically `…/GoLand.app/Contents`.
 * Reads `product-info.json`, finds the native IDE binary, returns [IdeProduct].
 */
object IdeProductDiscovery {
    private val log = LoggerFactory.getLogger(IdeProductDiscovery::class.java)
    private val mapper = ObjectMapper().registerModule(kotlinModule())

    private val SUPPORTED_IDES =
        listOf(
            "idea",
            "phpstorm",
            "webstorm",
            "rider",
            "pycharm",
            "rubymine",
            "goland",
            "rustrover",
            "clion",
        )

    fun guessProduct(
        ideDir: Path,
        fileSystem: FileSystem,
    ): IdeProduct {
        val homePath = ideDir.toString()
        val os = System.getProperty("os.name").lowercase()
        val isMac = "mac" in os || "darwin" in os
        val isWindows = "win" in os

        // Find IDE binary
        val searchDir =
            if (isMac) {
                ideDir.resolve("MacOS")
            } else {
                ideDir.resolve("bin")
            }
        val suffix = if (isWindows) "64.exe" else ""

        val baseScriptName =
            findIde(searchDir, suffix, fileSystem)
                ?: error("Supported IDE not found in $searchDir")

        val ideScript =
            if (isMac) {
                ideDir.resolve("MacOS").resolve(baseScriptName).toString()
            } else {
                ideDir.resolve("bin").resolve("$baseScriptName$suffix").toString()
            }

        // Read product-info.json
        val productInfoDir = if (isMac) ideDir.resolve("Resources") else ideDir
        val productInfoPath = productInfoDir.resolve("product-info.json")
        val productInfoJson = fileSystem.read(productInfoPath)
        val productInfo: IdeProductInfoJson = mapper.readValue(productInfoJson)

        // Map product code to Qodana code
        val qodanaCode = toQodanaCode(productInfo.productCode)
        val properties = IntellijLinterProperties.findByProductInfoCode(productInfo.productCode)
        val name = properties?.presentableName ?: "Unknown IDE"
        val isEap = productInfo.versionSuffix.contains("EAP", ignoreCase = true)

        val product =
            IdeProduct(
                name = name,
                ideCode = productInfo.productCode,
                code = qodanaCode,
                version = productInfo.version,
                baseScriptName = baseScriptName,
                ideScript = ideScript,
                build = productInfo.buildNumber,
                home = homePath,
                isEap = isEap,
            )

        log.debug("Discovered IDE product: {}", product)
        return product
    }

    private fun findIde(
        dir: Path,
        suffix: String,
        fileSystem: FileSystem,
    ): String? {
        for (ide in SUPPORTED_IDES) {
            if (fileSystem.exists(dir.resolve("$ide$suffix"))) {
                return ide
            }
        }
        return null
    }

    /** Maps IDE product code (from product-info.json) to Qodana product code. */
    private fun toQodanaCode(baseProduct: String): String =
        when (baseProduct) {
            "IC" -> "QDJVMC"
            "PC" -> "QDPYC"
            "IU" -> "QDJVM"
            "PS" -> "QDPHP"
            "WS" -> "QDJS"
            "RD" -> "QDNET"
            "PY" -> "QDPY"
            "GO" -> "QDGO"
            "RM" -> "QDRUBY"
            "CL" -> "QDCPP"
            "RR" -> "QDRUST"
            else -> baseProduct
        }
}
