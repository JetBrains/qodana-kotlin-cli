package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

class ResolveBuildArgsCommandTest {
    private val cmd =
        ResolveBuildArgsCommand(
            imagesDir = Path.of("docker/images"),
            clangVersions = Path.of("docker/clang-versions.txt"),
            rubyVersions = Path.of("docker/ruby-versions.txt"),
            debianBases = Path.of("docker/debian-bases.txt"),
        )

    private val bookworm =
        "dhi.io/debian-base:bookworm@sha256:802b1fe0c2ac7827f82f4a33918f3bd69293fe83d18ddf471ae57f4312400cd5"
    private val trixie =
        "dhi.io/debian-base:trixie-debian13-dev@sha256:68b5f4c2c789b99dc6ab7574c7e695e724646f64616619c48c3245f8aaeae459"
    private val ruby34 =
        "dhi.io/ruby:3.4-debian13-dev@sha256:3f86a864dbd00ad09e92268c0cac6604fe2e472604a16e06ea826d57cae45abd"

    // Defaults asserted as literals (3.3/20/19), not re-derived from the files the resolver reads, so a
    // default move must break these (not silently agree).
    @Test
    fun `ruby default emits no build args, expects the default version`() {
        val r = cmd.resolve("qodana-ruby", "")
        assertEquals(emptyList<String>(), r.buildArgs)
        assertEquals("ruby", r.expectTool)
        assertEquals("3.3", r.expectVersion)
    }

    @Test
    fun `ruby 3_4 overrides the base image`() {
        val r = cmd.resolve("qodana-ruby", "3.4")
        assertEquals(listOf("--build-arg", "QD_BASE_IMAGE=$ruby34"), r.buildArgs)
        assertEquals("ruby", r.expectTool)
        assertEquals("3.4", r.expectVersion)
    }

    @Test
    fun `cpp default (clang 20 trixie) emits no build args`() {
        val r = cmd.resolve("qodana-cpp", "")
        assertEquals(emptyList<String>(), r.buildArgs)
        assertEquals("clang", r.expectTool)
        assertEquals("20", r.expectVersion)
    }

    @Test
    fun `cpp clang 17 crosses to bookworm base`() {
        val r = cmd.resolve("qodana-cpp", "17")
        assertEquals(
            listOf(
                "--build-arg",
                "CLANG=17",
                "--build-arg",
                "CLANG_OS=bookworm",
                "--build-arg",
                "QD_BASE_IMAGE=$bookworm",
            ),
            r.buildArgs,
        )
        assertEquals("clang", r.expectTool)
        assertEquals("17", r.expectVersion)
    }

    @Test
    fun `cpp clang 21 stays on trixie`() {
        val r = cmd.resolve("qodana-cpp", "21")
        assertEquals(
            listOf("--build-arg", "CLANG=21", "--build-arg", "CLANG_OS=trixie", "--build-arg", "QD_BASE_IMAGE=$trixie"),
            r.buildArgs,
        )
    }

    @Test
    fun `clang default (clang 19 bookworm) emits no build args`() {
        val r = cmd.resolve("qodana-clang", "")
        assertEquals(emptyList<String>(), r.buildArgs)
        assertEquals("clang", r.expectTool)
        assertEquals("19", r.expectVersion)
    }

    @Test
    fun `clang 20 crosses to trixie base`() {
        val r = cmd.resolve("qodana-clang", "20")
        assertEquals(
            listOf("--build-arg", "CLANG=20", "--build-arg", "CLANG_OS=trixie", "--build-arg", "QD_BASE_IMAGE=$trixie"),
            r.buildArgs,
        )
        assertEquals("20", r.expectVersion)
    }

    @Test
    fun `non-versioned image resolves to none`() {
        val r = cmd.resolve("qodana-jvm", "")
        assertEquals(emptyList<String>(), r.buildArgs)
        assertEquals("none", r.expectTool)
        assertEquals("", r.expectVersion)
    }

    @Test
    fun `a stray version on a non-versioned image fails loudly`() {
        val ex = runCatching { cmd.resolve("qodana-jvm", "17") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "a version on a non-versioned image must throw, was $ex")
        assertTrue(ex.message!!.contains("17"), "the error must name the stray version: ${ex.message}")
    }

    @Test
    fun `an unknown clang major fails loudly`() {
        val ex = runCatching { cmd.resolve("qodana-cpp", "99") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "unknown clang major must throw, was $ex")
        assertTrue(ex.message!!.contains("99"), "the error must name the offending major: ${ex.message}")
    }

    @Test
    fun `an unknown ruby version fails loudly`() {
        val ex = runCatching { cmd.resolve("qodana-ruby", "3.9") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException, "unknown ruby version must throw, was $ex")
        assertTrue(ex.message!!.contains("3.9"), "the error must name the offending version: ${ex.message}")
    }
}
