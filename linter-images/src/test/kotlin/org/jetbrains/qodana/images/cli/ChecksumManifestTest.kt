package org.jetbrains.qodana.images.cli

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class ChecksumManifestTest {
    @Test
    fun `finds the sha256 for a named entry ignoring other lines`() {
        val manifest =
            """
            aaaa  qodana_linux_arm64.tar.gz
            bbbb  qodana_linux_x86_64.tar.gz
            """.trimIndent()
        assertEquals("bbbb", ChecksumManifest.parse(manifest).sha256For("qodana_linux_x86_64.tar.gz"))
    }

    @Test
    fun `tolerates the binary-mode star prefix produced by sha256sum`() {
        val manifest = "cccc *qodana_linux_x86_64.tar.gz"
        assertEquals("cccc", ChecksumManifest.parse(manifest).sha256For("qodana_linux_x86_64.tar.gz"))
    }

    @Test
    fun `missing entry fails loudly rather than returning null`() {
        val manifest = "aaaa  qodana_linux_arm64.tar.gz"
        assertFailsWith<IllegalArgumentException> {
            ChecksumManifest.parse(manifest).sha256For("qodana_linux_x86_64.tar.gz")
        }
    }
}
