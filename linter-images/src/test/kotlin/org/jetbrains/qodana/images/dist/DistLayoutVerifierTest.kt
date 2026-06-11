package org.jetbrains.qodana.images.dist

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith

class DistLayoutVerifierTest {
    private val verifier = DistLayoutVerifier()

    /** Builds a dist tree; pass `modules` = null to omit the JBR `release` file entirely. */
    private fun dist(
        root: Path,
        productCode: String = "IU",
        withJar: Boolean = true,
        modules: List<String>? = listOf("java.base", "jdk.jartool", "jdk.compiler"),
    ): Path {
        Files.createDirectories(root)
        root.resolve("product-info.json").writeText("""{"productCode":"$productCode","version":"2025.3"}""")
        val jbrBin = root.resolve("jbr/bin")
        Files.createDirectories(jbrBin)
        if (withJar) jbrBin.resolve("jar").writeText("#!/bin/sh\n")
        if (modules != null) {
            root.resolve("jbr/release").writeText("""MODULES="${modules.joinToString(" ")}"""" + "\n")
        }
        return root
    }

    @Test
    fun `accepts a dist with matching product code and a complete JBR`(
        @TempDir tmp: Path,
    ) {
        verifier.verify(dist(tmp.resolve("d")), expectedProductCode = "IU")
    }

    @Test
    fun `rejects a product code that only substring-matches`(
        @TempDir tmp: Path,
    ) {
        val d = dist(tmp.resolve("d"), productCode = "IU-EAP")
        assertFailsWith<DistLayoutException> { verifier.verify(d, expectedProductCode = "IU") }
    }

    @Test
    fun `rejects a wrong product code`(
        @TempDir tmp: Path,
    ) {
        val d = dist(tmp.resolve("d"), productCode = "IC")
        assertFailsWith<DistLayoutException> { verifier.verify(d, expectedProductCode = "IU") }
    }

    @Test
    fun `rejects a missing product-info json`(
        @TempDir tmp: Path,
    ) {
        val d = tmp.resolve("empty")
        Files.createDirectories(d)
        assertFailsWith<DistLayoutException> { verifier.verify(d, expectedProductCode = "IU") }
    }

    @Test
    fun `rejects an incomplete JBR missing the jar tool binary (QD-14924)`(
        @TempDir tmp: Path,
    ) {
        val d = dist(tmp.resolve("d"), withJar = false)
        assertFailsWith<DistLayoutException> { verifier.verify(d, expectedProductCode = "IU") }
    }

    @Test
    fun `rejects an incomplete JBR missing the jdk-jartool module (QD-14924)`(
        @TempDir tmp: Path,
    ) {
        val d = dist(tmp.resolve("d"), modules = listOf("java.base", "jdk.compiler"))
        assertFailsWith<DistLayoutException> { verifier.verify(d, expectedProductCode = "IU") }
    }
}
