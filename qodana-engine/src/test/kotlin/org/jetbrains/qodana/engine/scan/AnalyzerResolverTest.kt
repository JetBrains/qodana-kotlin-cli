package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.product.Analyzer
import org.jetbrains.qodana.core.product.Linters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyzerResolverTest {
    // --- resolveFromIdeField ---

    @Test
    fun `resolveFromIdeField QDJVM`() {
        val analyzer = AnalyzerResolver.resolveFromIdeField("QDJVM")
        assertIs<Analyzer.Native>(analyzer)
        assertEquals(Linters.JVM, analyzer.linter)
        assertFalse(analyzer.isEap)
    }

    @Test
    fun `resolveFromIdeField QDJS-EAP`() {
        val analyzer = AnalyzerResolver.resolveFromIdeField("QDJS-EAP")
        assertIs<Analyzer.Native>(analyzer)
        assertEquals(Linters.JS, analyzer.linter)
        assertTrue(analyzer.isEap)
    }

    @Test
    fun `resolveFromIdeField QDGO`() {
        val analyzer = AnalyzerResolver.resolveFromIdeField("QDGO")
        assertIs<Analyzer.Native>(analyzer)
        assertEquals(Linters.GO, analyzer.linter)
        assertFalse(analyzer.isEap)
    }

    @Test
    fun `resolveFromIdeField null`() {
        assertNull(AnalyzerResolver.resolveFromIdeField(null))
    }

    @Test
    fun `resolveFromIdeField empty`() {
        assertNull(AnalyzerResolver.resolveFromIdeField(""))
    }

    @Test
    fun `resolveFromIdeField unknown code`() {
        assertNull(AnalyzerResolver.resolveFromIdeField("UNKNOWN"))
    }

    @Test
    fun `resolveFromIdeField eapOnly linter is always eap`() {
        val analyzer = AnalyzerResolver.resolveFromIdeField("QDRUBY")
        assertIs<Analyzer.Native>(analyzer)
        assertTrue(analyzer.isEap)
    }

    // --- resolveFromDockerImage ---

    @Test
    fun `resolveFromDockerImage valid`() {
        val analyzer = AnalyzerResolver.resolveFromDockerImage("jetbrains/qodana-jvm:2025.3")
        assertIs<Analyzer.Docker>(analyzer)
        assertEquals(Linters.JVM, analyzer.linter)
    }

    @Test
    fun `resolveFromDockerImage null`() {
        assertNull(AnalyzerResolver.resolveFromDockerImage(null))
    }

    @Test
    fun `resolveFromDockerImage unknown`() {
        assertNull(AnalyzerResolver.resolveFromDockerImage("unknown/image:latest"))
    }

    // --- resolveFromLinterName ---

    @Test
    fun `resolveFromLinterName valid`() {
        val analyzer = AnalyzerResolver.resolveFromLinterName("qodana-jvm")
        assertIs<Analyzer.Native>(analyzer)
        assertEquals(Linters.JVM, analyzer.linter)
        assertFalse(analyzer.isEap)
    }

    @Test
    fun `resolveFromLinterName with EAP`() {
        val analyzer = AnalyzerResolver.resolveFromLinterName("qodana-php-EAP")
        assertIs<Analyzer.Native>(analyzer)
        assertEquals(Linters.PHP, analyzer.linter)
        assertTrue(analyzer.isEap)
    }

    @Test
    fun `resolveFromLinterName null`() {
        assertNull(AnalyzerResolver.resolveFromLinterName(null))
    }

    @Test
    fun `resolveFromLinterName unknown`() {
        assertNull(AnalyzerResolver.resolveFromLinterName("nonexistent"))
    }
}
