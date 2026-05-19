package org.jetbrains.qodana.engine.model

import kotlin.test.*

class AuthContextTest {
    @Test
    fun `hasToken is true when token is non-empty`() {
        val ctx = AuthContext(token = "abc123", endpoint = "https://qodana.cloud")
        assertTrue(ctx.hasToken)
    }

    @Test
    fun `hasToken is false when token is null`() {
        val ctx = AuthContext(token = null, endpoint = "https://qodana.cloud")
        assertFalse(ctx.hasToken)
    }

    @Test
    fun `hasToken is false when token is blank`() {
        val ctx = AuthContext(token = "   ", endpoint = "https://qodana.cloud")
        assertFalse(ctx.hasToken)
    }

    @Test
    fun `isLicenseOnly is true when only license token is set`() {
        val ctx =
            AuthContext(
                token = null,
                endpoint = "https://qodana.cloud",
                licenseOnlyToken = "license-token",
            )
        assertTrue(ctx.isLicenseOnly)
    }

    @Test
    fun `isLicenseOnly is false when both tokens are set`() {
        val ctx =
            AuthContext(
                token = "upload-token",
                endpoint = "https://qodana.cloud",
                licenseOnlyToken = "license-token",
            )
        assertFalse(ctx.isLicenseOnly)
    }

    @Test
    fun `isLicenseOnly is false when no tokens are set`() {
        val ctx =
            AuthContext(
                token = null,
                endpoint = "https://qodana.cloud",
                licenseOnlyToken = null,
            )
        assertFalse(ctx.isLicenseOnly)
    }
}
