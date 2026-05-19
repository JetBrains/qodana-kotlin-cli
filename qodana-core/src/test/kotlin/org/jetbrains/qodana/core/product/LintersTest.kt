package org.jetbrains.qodana.core.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `find by docker image with version tag`() {
        val linter = Linters.findByDockerImage("jetbrains/qodana-jvm:2025.3")
        assertNotNull(linter)
        assertEquals(Linters.JVM, linter)
    }

    @Test
    fun `find by docker image community variant`() {
        val linter = Linters.findByDockerImage("jetbrains/qodana-jvm-community:latest")
        assertNotNull(linter)
        assertEquals(Linters.JVM_COMMUNITY, linter)
    }

    @Test
    fun `free linters are not paid`() {
        Linters.ALL_FREE.forEach { linter ->
            assertFalse(linter.isPaid, "${linter.name} should not be paid")
        }
    }

    @Test
    fun `native linters support native`() {
        Linters.ALL_NATIVE.forEach { linter ->
            assertTrue(linter.supportsNative, "${linter.name} should support native")
        }
    }

    @Test
    fun `image generation for eap and release`() {
        // Since IS_RELEASED is false, all images get -eap suffix
        assertEquals("jetbrains/qodana-jvm:2025.3-eap", Linters.JVM.image())
        assertEquals("jetbrains/qodana-clang:2025.3-eap", Linters.CLANG.image())
    }

    @Test
    fun `all product codes are unique`() {
        val codes = Linters.ALL.map { it.productCode }
        assertEquals(codes.size, codes.distinct().size, "Product codes should be unique")
    }

    @Test
    fun `all names are unique`() {
        val names = Linters.ALL.map { it.name }
        assertEquals(names.size, names.distinct().size, "Linter names should be unique")
    }

    @Test
    fun `all docker images are unique`() {
        val images = Linters.ALL.map { it.dockerImage }
        assertEquals(images.size, images.distinct().size, "Docker images should be unique")
    }

    @Test
    fun `langs to linters covers all expected languages`() {
        val expectedLangs = listOf("Java", "Kotlin", "PHP", "Python", "JavaScript", "TypeScript", "Go", "C#", "C", "C++", "Ruby", "Rust")
        for (lang in expectedLangs) {
            assertTrue(Linters.LANGS_TO_LINTERS.containsKey(lang), "Missing language: $lang")
            assertTrue(Linters.LANGS_TO_LINTERS[lang]!!.isNotEmpty(), "Empty linters for $lang")
        }
    }

    @Test
    fun `all free linters list`() {
        assertTrue(Linters.ALL_FREE.contains(Linters.JVM_COMMUNITY))
        assertTrue(Linters.ALL_FREE.contains(Linters.PYTHON_COMMUNITY))
        assertTrue(Linters.ALL_FREE.contains(Linters.DOTNET_COMMUNITY))
        assertTrue(Linters.ALL_FREE.contains(Linters.CLANG))
        assertFalse(Linters.ALL_FREE.contains(Linters.JVM))
        assertFalse(Linters.ALL_FREE.contains(Linters.DOTNET))
    }

    @Test
    fun `release version is 2025_3`() {
        assertEquals("2025.3", Linters.RELEASE_VERSION)
    }

    @Test
    fun `native analyzer`() {
        val analyzer = Linters.JVM.nativeAnalyzer()
        assertTrue(analyzer is Analyzer.Native)
        assertEquals(Linters.JVM, analyzer.linter)
    }

    @Test
    fun `docker analyzer`() {
        val analyzer = Linters.JVM.dockerAnalyzer()
        assertTrue(analyzer is Analyzer.Docker)
        assertEquals(Linters.JVM, analyzer.linter)
        assertTrue((analyzer as Analyzer.Docker).image.startsWith("jetbrains/qodana-jvm:"))
    }

    @Test
    fun `eap-only linters always get eap tag`() {
        assertTrue(Linters.RUBY.eapOnly)
        assertTrue(Linters.RUBY.image().contains("-eap"))
        assertTrue(Linters.CPP.eapOnly)
        assertTrue(Linters.CPP.image().contains("-eap"))
    }

    @Test
    fun `dotnet linter supports fixes`() {
        assertTrue(Linters.DOTNET.supportsFixes)
        assertFalse(Linters.DOTNET_COMMUNITY.supportsFixes)
    }

    @Test
    fun `find by product code strips EAP suffix`() {
        assertEquals(Linters.JVM, Linters.findByProductCode("QDJVM-EAP"))
        assertEquals(Linters.DOTNET, Linters.findByProductCode("QDNET-EAP"))
        assertEquals(Linters.CLANG, Linters.findByProductCode("QDCLC-EAP"))
    }

    @Test
    fun `find by name strips EAP suffix`() {
        assertEquals(Linters.JVM, Linters.findByName("qodana-jvm-EAP"))
        assertEquals(Linters.PHP, Linters.findByName("qodana-php-EAP"))
    }

    @Test
    fun `find by docker image strips http prefix`() {
        val linter = Linters.findByDockerImage("https://jetbrains/qodana-jvm:latest")
        assertNotNull(linter)
        assertEquals(Linters.JVM, linter)
    }

    @Test
    fun `find by docker image strips https prefix`() {
        val linter = Linters.findByDockerImage("http://jetbrains/qodana-go:2025.3")
        assertNotNull(linter)
        assertEquals(Linters.GO, linter)
    }

    @Test
    fun `getVersionBranch converts version`() {
        assertEquals("253", Linters.getVersionBranch("2025.3"))
        assertEquals("241", Linters.getVersionBranch("2024.1"))
        assertEquals("232", Linters.getVersionBranch("2023.2"))
    }

    @Test
    fun `getVersionBranch handles invalid input`() {
        assertEquals("", Linters.getVersionBranch("invalid"))
        assertEquals("", Linters.getVersionBranch(""))
    }

    @Test
    fun `getScriptSuffix returns sh or bat`() {
        val suffix = Linters.getScriptSuffix()
        assertTrue(suffix == ".sh" || suffix == ".bat")
    }

    @Test
    fun `isRuby returns true for ruby linter`() {
        assertTrue(Linters.isRuby(Linters.RUBY))
    }

    @Test
    fun `isRuby returns false for non-ruby linter`() {
        assertFalse(Linters.isRuby(Linters.JVM))
        assertFalse(Linters.isRuby(Linters.DOTNET))
    }
}
