package org.jetbrains.qodana.core.product

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IntellijLinterPropertiesTest {

    @Test
    fun `findByLinter returns properties for JVM`() {
        val props = IntellijLinterProperties.findByLinter(Linters.JVM)
        assertNotNull(props)
        assertEquals("IIU", props.feedProductCode)
        assertEquals("IU", props.productInfoJsonCode)
        assertEquals("IDEA_VM_OPTIONS", props.vmOptionsEnv)
        assertEquals("idea", props.scriptName)
    }

    @Test
    fun `findByLinter returns properties for Go`() {
        val props = IntellijLinterProperties.findByLinter(Linters.GO)
        assertNotNull(props)
        assertEquals("GO", props.feedProductCode)
        assertEquals("goland", props.scriptName)
    }

    @Test
    fun `findByLinter returns null for CLANG`() {
        val props = IntellijLinterProperties.findByLinter(Linters.CLANG)
        assertNull(props)
    }

    @Test
    fun `findByLinter returns null for DOTNET_COMMUNITY`() {
        val props = IntellijLinterProperties.findByLinter(Linters.DOTNET_COMMUNITY)
        assertNull(props)
    }

    @Test
    fun `findByProductInfoCode finds by IDE product code`() {
        val props = IntellijLinterProperties.findByProductInfoCode("PS")
        assertNotNull(props)
        assertEquals(Linters.PHP, props.linter)
        assertEquals("phpstorm", props.scriptName)
    }

    @Test
    fun `findByProductInfoCode returns null for unknown`() {
        assertNull(IntellijLinterProperties.findByProductInfoCode("UNKNOWN"))
    }

    @Test
    fun `ALL has 13 entries`() {
        assertEquals(13, IntellijLinterProperties.ALL.size)
    }

    @Test
    fun `android has empty feed product code`() {
        val props = IntellijLinterProperties.findByLinter(Linters.ANDROID)
        assertNotNull(props)
        assertEquals("", props.feedProductCode)
    }

    @Test
    fun `presentableName delegates to linter`() {
        val props = IntellijLinterProperties.findByLinter(Linters.JVM)!!
        assertEquals("Qodana Ultimate for JVM", props.presentableName)
    }

    @Test
    fun `each linter in ALL has unique feedProductCode or empty`() {
        val nonEmpty = IntellijLinterProperties.ALL
            .map { it.feedProductCode }
            .filter { it.isNotEmpty() }
        assertEquals(nonEmpty.size, nonEmpty.distinct().size)
    }
}
