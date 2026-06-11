package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Per-slug `.env` contract guard (plan Phase 4.4).
 *
 * Scope NOTE: this is the JVM slice. Only `qodana-jvm.env` is authored so far; the android/clang
 * `.env` files (and the android-shared-pin + clang-no-dist-keys assertions) land with their images
 * in the follow-up tasks. The default `test` task must stay green, so this class asserts ONLY the
 * jvm key set + the jvm rows of `pins match phase-0-decisions` + the no-placeholder guard over the
 * slugs that exist today. It is NOT a silent skip: it enforces every rule that applies to the
 * authored env, and the follow-ups extend `slugs`/`expected` as their `.env` arrive.
 */
class EnvContractTest {
    // Test working dir is the module root (pinned once in Phase 1) — resolve relative to it directly.
    private val imagesDir: Path = Path.of("docker/images")
    private val decisions: Path = Path.of("docs/phase-0-decisions.md")

    /** Slugs whose `.env` are authored so far (extended as android/clang land). */
    private val authoredSlugs = listOf("qodana-jvm")

    private fun parseEnv(slug: String): Map<String, String> =
        imagesDir
            .resolve("$slug.env")
            .readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val i = line.indexOf('=')
                assertTrue(i > 0, "malformed env line in $slug.env: '$line'")
                line.substring(0, i) to line.substring(i + 1)
            }

    @Test
    fun `qodana-jvm env has exactly the jvm key set`() {
        // Canonical .env CONTRACT: exact major+build pin, public source channel, arch-parameterized tini.
        val expected =
            setOf(
                "QD_LINTER_SLUG",
                "QD_VERSION",
                "QD_BUILD",
                "QD_CHANNEL",
                "QD_RELEASE_TYPE",
                "QD_PRODUCT_INFO_CODE",
                "QD_BASE_IMAGE",
                "CLI_BINARY",
                "CLI_VERSION",
                "CLI_OS",
                "CLI_ARCH",
                "NODE_MAJOR",
                "TINI_VERSION",
                "TINI_ARCH",
                "TINI_SHA256",
            )
        assertEquals(expected, parseEnv("qodana-jvm").keys)
    }

    @Test
    fun `no committed env carries a placeholder all-zero digest`() {
        for (slug in authoredSlugs) {
            parseEnv(slug).forEach { (k, v) ->
                assertTrue("0000000000000000" !in v, "$slug.env $k is a placeholder all-zero value: '$v'")
            }
        }
    }

    @Test
    fun `jvm pins match phase-0-decisions`() {
        val d = decisions.readText()

        // Anchor the key to line start (MULTILINE) so a key cannot match as a substring of a longer
        // key or mid-line text — only a real `KEY = value` row is read.
        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val jvm = parseEnv("qodana-jvm")
        assertEquals(pin("QD_BASE_IMAGE"), jvm["QD_BASE_IMAGE"], "base digest must match phase-0-decisions")
        assertEquals(pin("QODANA_JVM_VERSION"), jvm["QD_VERSION"], "jvm major must match phase-0-decisions")
        assertEquals(pin("QODANA_JVM_BUILD"), jvm["QD_BUILD"], "jvm build pin must match phase-0-decisions")
        assertEquals("IU", jvm["QD_PRODUCT_INFO_CODE"], "jvm product-info code is IU")
    }
}
