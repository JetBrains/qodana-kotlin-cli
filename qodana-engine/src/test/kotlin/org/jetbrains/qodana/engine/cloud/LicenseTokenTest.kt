package org.jetbrains.qodana.engine.cloud

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseTokenTest {
    @Test
    fun `no token`() {
        val token = LicenseToken.resolve(null, null)
        assertEquals("", token.token)
        assertFalse(token.licenseOnly)
        assertFalse(token.isAllowedToSendReports)
        assertTrue(token.isAllowedToSendFus)
    }

    @Test
    fun `regular token`() {
        val token = LicenseToken.resolve("my-token", null)
        assertEquals("my-token", token.token)
        assertFalse(token.licenseOnly)
        assertTrue(token.isAllowedToSendReports)
        assertTrue(token.isAllowedToSendFus)
    }

    @Test
    fun `license-only token`() {
        val token = LicenseToken.resolve(null, "license-token")
        assertEquals("license-token", token.token)
        assertTrue(token.licenseOnly)
        assertFalse(token.isAllowedToSendReports)
        assertFalse(token.isAllowedToSendFus)
    }

    @Test
    fun `both tokens prefers regular`() {
        val token = LicenseToken.resolve("regular", "license-only")
        assertEquals("regular", token.token)
        assertFalse(token.licenseOnly)
        assertTrue(token.isAllowedToSendReports)
        assertTrue(token.isAllowedToSendFus)
    }

    @Test
    fun `empty string treated as no token`() {
        val token = LicenseToken.resolve("", "license-token")
        assertEquals("license-token", token.token)
        assertTrue(token.licenseOnly)
    }

    @Test
    fun `empty companion value`() {
        assertEquals("", LicenseToken.EMPTY.token)
        assertFalse(LicenseToken.EMPTY.licenseOnly)
        assertFalse(LicenseToken.EMPTY.isAllowedToSendReports)
        assertTrue(LicenseToken.EMPTY.isAllowedToSendFus)
    }

    // --- isCloudTokenRequired ---

    @Test
    fun `isCloudTokenRequired true when token provided`() {
        assertTrue(LicenseToken.isCloudTokenRequired("token", null, null, isPaid = false, isEap = false))
    }

    @Test
    fun `isCloudTokenRequired true when license-only token set`() {
        assertTrue(LicenseToken.isCloudTokenRequired(null, "lic-token", null, isPaid = false, isEap = false))
    }

    @Test
    fun `isCloudTokenRequired false for free analyzer`() {
        assertFalse(LicenseToken.isCloudTokenRequired(null, null, null, isPaid = false, isEap = false))
    }

    @Test
    fun `isCloudTokenRequired false for eap analyzer`() {
        assertFalse(LicenseToken.isCloudTokenRequired(null, null, null, isPaid = true, isEap = true))
    }

    @Test
    fun `isCloudTokenRequired true for paid non-eap without license`() {
        assertTrue(LicenseToken.isCloudTokenRequired(null, null, null, isPaid = true, isEap = false))
    }

    @Test
    fun `isCloudTokenRequired false when license env set`() {
        assertFalse(LicenseToken.isCloudTokenRequired(null, null, "license-data", isPaid = true, isEap = false))
    }
}
