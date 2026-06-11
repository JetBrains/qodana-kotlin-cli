package org.jetbrains.qodana.images.dist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/** Thrown when a provisioned IDE distribution does not satisfy the required layout. */
class DistLayoutException(
    message: String,
) : RuntimeException(message)

/**
 * Asserts a provisioned IDE dist is the one we expect AND carries a usable bundled JBR runtime.
 *
 *  - `<dist>/product-info.json` exists and its `productCode` EXACTLY equals
 *    `expectedProductCode` (an IDE code such as `IU`, not the QD code; never a
 *    substring match — `IU-EAP` must not satisfy `IU`).
 *  - A bundled JBR runtime is present: `<dist>/jbr/bin/java` and `<dist>/jbr/release` exist (a dist
 *    that lost its runtime must fail the build, not ship).
 *
 * It does NOT require a COMPLETE JDK. The dist's bundled JBR is a JetBrains Runtime that ships
 * `java`/`javac` but no `jar` / `jdk.jartool` (verified against qodana-jvm 253.31821 —
 * IMPLEMENTOR_VERSION "JBR-21.0.10") BY DESIGN. QD-14924's complete JDK for the rootless Gradle
 * daemon is provisioned separately at scan-time by qodana.yaml's bootstrap.sh (whose own rationale
 * documents the bundled JBR's incompleteness); requiring `jar` here would reject every real dist.
 */
class DistLayoutVerifier(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    fun verify(
        dist: Path,
        expectedProductCode: String,
    ) {
        verifyProductCode(dist, expectedProductCode)
        verifyJbrRuntimePresent(dist)
    }

    /** A JBR runtime must be bundled: the `java` launcher and the `release` manifest both exist. */
    private fun verifyJbrRuntimePresent(dist: Path) {
        val javaLauncher = dist.resolve("jbr/bin/java")
        if (!Files.isRegularFile(javaLauncher)) {
            throw DistLayoutException("Missing bundled JBR runtime: no java launcher at $javaLauncher")
        }
        val releaseFile = dist.resolve("jbr/release")
        if (!Files.isRegularFile(releaseFile)) {
            throw DistLayoutException("Missing bundled JBR runtime manifest at $releaseFile")
        }
    }

    private fun verifyProductCode(
        dist: Path,
        expectedProductCode: String,
    ) {
        val actual = readProductCode(dist)
        if (actual != expectedProductCode) {
            throw DistLayoutException(
                "product-info.json productCode mismatch: expected '$expectedProductCode', got '$actual'",
            )
        }
    }

    private fun readProductCode(dist: Path): String {
        val productInfo = dist.resolve("product-info.json")
        if (!Files.isRegularFile(productInfo)) {
            throw DistLayoutException("Missing product-info.json under $dist")
        }
        val node: JsonNode = mapper.readTree(Files.readString(productInfo))
        return node.path("productCode").asText(null)
            ?: throw DistLayoutException("product-info.json has no productCode field: $productInfo")
    }
}
