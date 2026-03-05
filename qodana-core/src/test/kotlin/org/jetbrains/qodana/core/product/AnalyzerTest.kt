package org.jetbrains.qodana.core.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalyzerTest {

    @Test
    fun `native analyzer linter`() {
        val analyzer = Linters.JVM.nativeAnalyzer()

        assertIs<Analyzer.Native>(analyzer)
        assertEquals(Linters.JVM, analyzer.linter)
    }

    @Test
    fun `native analyzer isEap from linter`() {
        val eapAnalyzer = Linters.RUBY.nativeAnalyzer()

        assertTrue(Linters.RUBY.eapOnly)
        assertTrue(eapAnalyzer.isEap)

        val stableAnalyzer = Linters.JVM.nativeAnalyzer()

        assertFalse(Linters.JVM.eapOnly)
        assertFalse(stableAnalyzer.isEap)
    }

    @Test
    fun `docker analyzer image`() {
        val analyzer = Linters.JVM.dockerAnalyzer()

        assertIs<Analyzer.Docker>(analyzer)
        assertEquals(Linters.JVM.image(), analyzer.image)
        assertEquals(Linters.JVM, analyzer.linter)
    }

    @Test
    fun `docker analyzer default isEap is false`() {
        val analyzer = Linters.JVM.dockerAnalyzer()

        assertIs<Analyzer.Docker>(analyzer)
        assertFalse(analyzer.isEap)
    }

    @Test
    fun `sealed interface pattern matching`() {
        val native = Linters.JVM.nativeAnalyzer()
        val docker = Linters.JVM.dockerAnalyzer()

        val nativeResult = when (native) {
            is Analyzer.Native -> "native"
            is Analyzer.Docker -> "docker"
        }
        assertEquals("native", nativeResult)

        val dockerResult = when (docker) {
            is Analyzer.Native -> "native"
            is Analyzer.Docker -> "docker"
        }
        assertEquals("docker", dockerResult)
    }
}
