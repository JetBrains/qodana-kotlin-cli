package org.jetbrains.qodana.images.dist

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/** Thrown when a provisioned IDE distribution does not satisfy the required layout. */
class DistLayoutException(message: String) : RuntimeException(message)

/**
 * Asserts a provisioned IDE dist is the one we expect AND carries a complete JBR.
 *
 *  - `<dist>/product-info.json` exists and its `productCode` EXACTLY equals
 *    `expectedProductCode` (an IDE code such as `IU`, not the QD code; never a
 *    substring match — `IU-EAP` must not satisfy `IU`).
 *  - The bundled JBR is a complete JDK (QD-14924): `<dist>/jbr/bin/jar` exists
 *    and the `jdk.jartool` module is present in `<dist>/jbr/release`. A trimmed
 *    "JRE" JBR breaks the rootless Qodana linter's Gradle daemon at scan time, so
 *    the build must fail here rather than ship the broken image.
 */
class DistLayoutVerifier(private val mapper: ObjectMapper = ObjectMapper()) {
    fun verify(
        dist: Path,
        expectedProductCode: String,
    ) {
        verifyProductCode(dist, expectedProductCode)
        verifyCompleteJbr(dist)
    }

    private fun verifyProductCode(
        dist: Path,
        expectedProductCode: String,
    ) {
        val productInfo = dist.resolve("product-info.json")
        if (!Files.isRegularFile(productInfo)) {
            throw DistLayoutException("Missing product-info.json under $dist")
        }
        val node: JsonNode = mapper.readTree(Files.readString(productInfo))
        val actual = node.path("productCode").asText(null)
            ?: throw DistLayoutException("product-info.json has no productCode field: $productInfo")
        if (actual != expectedProductCode) {
            throw DistLayoutException(
                "product-info.json productCode mismatch: expected '$expectedProductCode', got '$actual'",
            )
        }
    }

    private fun verifyCompleteJbr(dist: Path) {
        val jarTool = dist.resolve("jbr/bin/jar")
        if (!Files.isRegularFile(jarTool)) {
            throw DistLayoutException("Incomplete JBR: missing jar tool at $jarTool (QD-14924)")
        }
        val releaseFile = dist.resolve("jbr/release")
        if (!Files.isRegularFile(releaseFile)) {
            throw DistLayoutException("Incomplete JBR: missing module manifest at $releaseFile (QD-14924)")
        }
        val release = Files.readString(releaseFile)
        if (!jdkJartoolPresent(release)) {
            throw DistLayoutException("Incomplete JBR: jdk.jartool module not present in $releaseFile (QD-14924)")
        }
    }

    /** Parses the `MODULES="a b c"` line of a JDK `release` file and checks for jdk.jartool. */
    private fun jdkJartoolPresent(releaseFileContent: String): Boolean {
        val modulesLine =
            releaseFileContent.lineSequence().firstOrNull { it.startsWith("MODULES=") } ?: return false
        val modules = modulesLine.substringAfter('=').trim().trim('"').split(' ')
        return "jdk.jartool" in modules
    }
}
