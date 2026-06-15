package org.jetbrains.qodana.images.dist

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith

class VerifyDistLayoutCommandTest {
    /** A real qodana-jvm dist shape: product-info.json + a JBR runtime (java + release, no jar). */
    private fun validDist(
        root: Path,
        productCode: String,
    ): Path {
        Files.createDirectories(root)
        root.resolve("product-info.json").writeText("""{"productCode":"$productCode"}""")
        val jbrBin = root.resolve("jbr/bin")
        Files.createDirectories(jbrBin)
        jbrBin.resolve("java").writeText("#!/bin/sh\n")
        root.resolve("jbr/release").writeText("""MODULES="java.base jdk.compiler"""" + "\n")
        return root
    }

    @Test
    fun `succeeds for a matching dist with a bundled JBR runtime`(
        @TempDir tmp: Path,
    ) {
        val dist = validDist(tmp.resolve("idea"), productCode = "IU")
        VerifyDistLayoutCommand().parse(
            listOf("--dist", dist.toString(), "--expected-product-code", "IU"),
        )
    }

    @Test
    fun `propagates a DistLayoutException on a mismatch`(
        @TempDir tmp: Path,
    ) {
        val dist = validDist(tmp.resolve("idea"), productCode = "IC")
        assertFailsWith<DistLayoutException> {
            VerifyDistLayoutCommand().parse(
                listOf("--dist", dist.toString(), "--expected-product-code", "IU"),
            )
        }
    }
}
