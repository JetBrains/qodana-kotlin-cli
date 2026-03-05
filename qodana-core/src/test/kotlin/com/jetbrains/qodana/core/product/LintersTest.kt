package com.jetbrains.qodana.core.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LintersTest {

    @Test
    fun `all linters count is 15`() {
        assertEquals(15, Linters.ALL.size)
    }

    @Test
    fun `find by product code`() {
        assertEquals(Linters.JVM, Linters.findByProductCode("QDJVM"))
        assertEquals(Linters.CLANG, Linters.findByProductCode("QDCLC"))
        assertEquals(Linters.DOTNET_COMMUNITY, Linters.findByProductCode("QDNETC"))
        assertNull(Linters.findByProductCode("INVALID"))
    }

    @Test
    fun `find by name`() {
        assertEquals(Linters.JVM, Linters.findByName("qodana-jvm"))
        assertEquals(Linters.CLANG, Linters.findByName("qodana-clang"))
        assertNull(Linters.findByName("nonexistent"))
    }

    @Test
    fun `find by docker image`() {
        assertNotNull(Linters.findByDockerImage("jetbrains/qodana-jvm:2025.3"))
        assertNotNull(Linters.findByDockerImage("jetbrains/qodana-clang:latest"))
        assertNull(Linters.findByDockerImage("some/other-image"))
    }

    @Test
    fun `free linters are not paid`() {
        Linters.ALL_FREE.forEach { linter ->
            assertEquals(false, linter.isPaid, "${linter.name} should not be paid")
        }
    }

    @Test
    fun `native linters support native`() {
        Linters.ALL_NATIVE.forEach { linter ->
            assertEquals(true, linter.supportsNative, "${linter.name} should support native")
        }
    }

    @Test
    fun `image generation for eap and release`() {
        // Since IS_RELEASED is false, all images get -eap suffix
        assertEquals("jetbrains/qodana-jvm:2025.3-eap", Linters.JVM.image())
        assertEquals("jetbrains/qodana-clang:2025.3-eap", Linters.CLANG.image())
    }
}
