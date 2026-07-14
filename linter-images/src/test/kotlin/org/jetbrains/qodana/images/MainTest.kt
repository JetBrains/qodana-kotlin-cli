package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.images.dist.DistLayoutException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun `image-tool registers all subcommands`() {
        val names = buildImageTool().registeredSubcommands().map { it.commandName }.toSet()
        assertEquals(
            setOf(
                "provision-dist",
                "install-cli",
                "verify-dist-layout",
                "resolve-build-args",
                "resolve-tags",
                "resolve-image-meta",
            ),
            names,
        )
    }

    @Test
    fun `dispatches verify-dist-layout and surfaces its failure`(
        @TempDir tmp: Path,
    ) {
        val dist = tmp.resolve("idea")
        Files.createDirectories(dist)
        dist.resolve("product-info.json").writeText("""{"productCode":"IC"}""")
        val jbrBin = dist.resolve("jbr/bin")
        Files.createDirectories(jbrBin)
        jbrBin.resolve("jar").writeText("#!/bin/sh\n")
        dist.resolve("jbr/release").writeText("""MODULES="java.base jdk.jartool"""" + "\n")

        assertFailsWith<DistLayoutException> {
            buildImageTool().parse(
                listOf("verify-dist-layout", "--dist", dist.toString(), "--expected-product-code", "IU"),
            )
        }
    }
}
