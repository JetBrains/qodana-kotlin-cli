package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertTrue

class MainWiringTest {
    @Test
    fun `root help lists provision-dist`() {
        val result = buildImageTool().test(listOf("--help"))
        assertTrue(result.output.contains("provision-dist"), result.output)
    }

    @Test
    fun `provision-dist help is reachable`() {
        val result = buildImageTool().test(listOf("provision-dist", "--help"))
        assertTrue(result.output.contains("--feed-url"), result.output)
        assertTrue(result.output.contains("--linter-slug"), result.output)
    }
}
