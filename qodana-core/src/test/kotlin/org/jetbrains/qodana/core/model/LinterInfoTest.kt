package org.jetbrains.qodana.core.model

import org.jetbrains.qodana.core.product.Linters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinterInfoTest {

    @Test
    fun `getMajorVersion simple version`() {
        assertEquals("2024.1", LinterInfo("QDJVM", "2024.1").getMajorVersion())
    }

    @Test
    fun `getMajorVersion with patch`() {
        assertEquals("2024.1", LinterInfo("QDJVM", "2024.1.5").getMajorVersion())
    }

    @Test
    fun `getMajorVersion with eap suffix`() {
        assertEquals("2024.1", LinterInfo("QDJVM", "2024.1-eap").getMajorVersion())
    }

    @Test
    fun `getMajorVersion invalid falls back`() {
        assertEquals(Linters.RELEASE_VERSION, LinterInfo("QDJVM", "invalid").getMajorVersion())
    }

    @Test
    fun `getMajorVersion empty falls back`() {
        assertEquals(Linters.RELEASE_VERSION, LinterInfo("QDJVM", "").getMajorVersion())
    }

    @Test
    fun `isCommunity true`() {
        assertTrue(LinterInfo("QDJVMC", licensePlan = "COMMUNITY").isCommunity())
    }

    @Test
    fun `isCommunity case insensitive`() {
        assertTrue(LinterInfo("QDJVMC", licensePlan = "community").isCommunity())
    }

    @Test
    fun `isCommunity false for ultimate`() {
        assertFalse(LinterInfo("QDJVM", licensePlan = "ULTIMATE").isCommunity())
    }

    @Test
    fun `isCommunity false for empty`() {
        assertFalse(LinterInfo("QDJVM", licensePlan = "").isCommunity())
    }
}
