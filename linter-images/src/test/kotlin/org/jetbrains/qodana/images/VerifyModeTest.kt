package org.jetbrains.qodana.images

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VerifyModeTest {
    @Test
    fun `fromArg parses the two case-insensitive modes`() {
        assertEquals(VerifyMode.GPG, VerifyMode.fromArg("gpg"))
        assertEquals(VerifyMode.SHA256, VerifyMode.fromArg("sha256"))
        assertEquals(VerifyMode.GPG, VerifyMode.fromArg("GPG"))
    }

    @Test
    fun `fromArg rejects an unknown mode loudly`() {
        val ex = assertFailsWith<IllegalArgumentException> { VerifyMode.fromArg("md5") }
        assertEquals("unknown QD_VERIFY_MODE 'md5' (expected: gpg, sha256)", ex.message)
    }

    @Test
    fun `the env-key const is QD_VERIFY_MODE`() {
        assertEquals("QD_VERIFY_MODE", QD_VERIFY_MODE)
    }
}
