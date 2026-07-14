package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Pins the shared runtime-axis resolution (which runtime version an (image, version) cell resolves to).
 * The default literals (3.3 / clang20 / clang19) are hardcoded, not read from the config the resolver
 * reads — so a wrong `.env`/versions.txt default makes the impl diverge from these and reddens the test.
 */
class RuntimeResolverTest {
    private val r =
        RuntimeResolver(
            imagesDir = Path.of("docker/images"),
            clangVersions = Path.of("docker/clang-versions.txt"),
            rubyVersions = Path.of("docker/ruby-versions.txt"),
        )

    @Test fun `ruby default`() = assertEquals(Runtime("ruby", "3.3", true), r.resolve("qodana-ruby", ""))

    @Test fun `ruby non-default`() = assertEquals(Runtime("ruby", "3.4", false), r.resolve("qodana-ruby", "3.4"))

    @Test fun `cpp default is clang 20`() = assertEquals(Runtime("clang", "20", true), r.resolve("qodana-cpp", ""))

    @Test fun `clang default is clang 19`() = assertEquals(Runtime("clang", "19", true), r.resolve("qodana-clang", ""))

    @Test fun `cpp non-default`() = assertEquals(Runtime("clang", "17", false), r.resolve("qodana-cpp", "17"))

    @Test fun `non-versioned image is null`() = assertNull(r.resolve("qodana-jvm", ""))

    @Test
    fun `stray version on a non-versioned image throws`() {
        val ex = runCatching { r.resolve("qodana-jvm", "17") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("17"), "want ISE naming 17, was $ex")
    }

    @Test
    fun `unknown clang major throws`() {
        val ex = runCatching { r.resolve("qodana-cpp", "99") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("99"), "want ISE naming 99, was $ex")
    }

    @Test
    fun `unknown ruby version throws`() {
        val ex = runCatching { r.resolve("qodana-ruby", "3.9") }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("3.9"), "want ISE naming 3.9, was $ex")
    }
}
