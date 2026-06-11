package org.jetbrains.qodana.images.dist

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith

class DistLayoutVerifierTest {
    private val verifier = DistLayoutVerifier()

    /**
     * Builds a dist tree. `withJbrJar`/`modules` default to the REAL qodana-jvm JBR shape: a
     * JetBrains Runtime that has `java`/`javac` but NO `jar` and no `jdk.jartool` module (verified
     * against build 253.31821 — IMPLEMENTOR_VERSION "JBR-21.0.10"). The IDE dist's JBR is a runtime
     * by design; the complete JDK the rootless Gradle daemon needs is provisioned separately at
     * scan-time (QD-14924's bootstrap.sh), NOT required of the dist.
     */
    private fun dist(
        root: Path,
        productCode: String = "IU",
        withJbrJar: Boolean = false,
        modules: List<String>? = listOf("java.base", "jdk.compiler", "jdk.javadoc"),
    ): Path {
        Files.createDirectories(root)
        root.resolve("product-info.json").writeText("""{"productCode":"$productCode","version":"2025.3"}""")
        val jbrBin = root.resolve("jbr/bin")
        Files.createDirectories(jbrBin)
        jbrBin.resolve("java").writeText("#!/bin/sh\n")
        if (withJbrJar) jbrBin.resolve("jar").writeText("#!/bin/sh\n")
        if (modules != null) {
            root.resolve("jbr/release").writeText("""MODULES="${modules.joinToString(" ")}"""" + "\n")
        }
        return root
    }

    @Test
    fun `accepts the real qodana-jvm dist layout (matching product code, JBR runtime without jar)`(
        @TempDir tmp: Path,
    ) {
        // The shipped JBR has no jar/jdk.jartool; verify-dist-layout must NOT reject it (QD-14924's
        // complete JDK is a scan-time bootstrap concern, not a dist-layout requirement).
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
    fun `rejects a dist with no bundled JBR runtime (missing jbr-bin-java)`(
        @TempDir tmp: Path,
    ) {
        // productCode alone is too weak: a dist that lost its runtime must still fail the build.
        val d = tmp.resolve("d")
        Files.createDirectories(d)
        d.resolve("product-info.json").writeText("""{"productCode":"IU","version":"2025.3"}""")
        Files.createDirectories(d.resolve("jbr/bin"))
        // jbr/bin exists but has no `java`, and no jbr/release.
        assertFailsWith<DistLayoutException> { verifier.verify(d, expectedProductCode = "IU") }
    }

    @Test
    fun `rejects a dist whose JBR has java but no release manifest`(
        @TempDir tmp: Path,
    ) {
        val d = dist(tmp.resolve("d"), modules = null) // modules=null omits jbr/release
        assertFailsWith<DistLayoutException> { verifier.verify(d, expectedProductCode = "IU") }
    }
}
