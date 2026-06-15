package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageToolCommandTest {
    @Test
    fun `root command prints help and exits cleanly`() {
        val result = ImageToolCommand().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue("image-tool" in result.output, "help should name the command")
    }
}
