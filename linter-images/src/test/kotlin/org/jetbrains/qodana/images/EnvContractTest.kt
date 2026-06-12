package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Per-slug `.env` contract guard (plan Phase 4.4).
 *
 * Scope NOTE: jvm + android slices. `qodana-clang.env` lands with the clang image (its pins are
 * token-gated and deferred), so the clang key-set + no-dist-keys assertions are NOT here yet. This
 * class enforces every rule that applies to the authored env; the clang follow-up extends
 * `authoredSlugs`/`expected` when its `.env` arrives. It is NOT a silent skip.
 *
 * Android carries DIST_BASE_STAGE (beyond the plan's verbatim key set): the dist orphan-fix
 * parameterizes `FROM ${DIST_BASE_STAGE:-base} AS dist`, and android sets it to android-toolchain so
 * the dist inherits the SDK/Corretto. jvm omits the key and falls back to base.
 */
class EnvContractTest {
    // Test working dir is the module root (pinned once in Phase 1) — resolve relative to it directly.
    private val imagesDir: Path = Path.of("docker/images")
    private val decisions: Path = Path.of("docs/phase-0-decisions.md")

    /** Slugs whose `.env` are authored so far (extended as clang lands). */
    private val authoredSlugs = listOf("qodana-jvm", "qodana-android")

    private fun parseEnv(slug: String): Map<String, String> {
        // Build the map by hand so a duplicate key fails LOUDLY: `associate` would silently keep the
        // last occurrence, and the exact-key-set assertions would not notice a copy-paste duplicate.
        val env = linkedMapOf<String, String>()
        imagesDir
            .resolve("$slug.env")
            .readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val i = line.indexOf('=')
                assertTrue(i > 0, "malformed env line in $slug.env: '$line'")
                val key = line.substring(0, i)
                assertTrue(key !in env, "duplicate key '$key' in $slug.env")
                env[key] = line.substring(i + 1)
            }
        return env
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
    fun `qodana-android env has exactly the android key set and no node`() {
        // Same dist/cli/runtime keys as jvm, minus NODE_MAJOR, plus the SDK/Corretto toolchain keys and
        // DIST_BASE_STAGE (the orphan-fix selector that layers the dist onto android-toolchain).
        val env = parseEnv("qodana-android")
        val expected =
            setOf(
                "QD_LINTER_SLUG",
                "QD_VERSION",
                "QD_BUILD",
                "QD_CHANNEL",
                "QD_RELEASE_TYPE",
                "QD_PRODUCT_INFO_CODE",
                "QD_BASE_IMAGE",
                "DIST_BASE_STAGE",
                "CLI_BINARY",
                "CLI_VERSION",
                "CLI_OS",
                "CLI_ARCH",
                "ANDROID_SDK_VERSION",
                "ANDROID_SDK_SHA256",
                "CORRETTO11_IMAGE",
                "CORRETTO17_IMAGE",
                "DEVICEID",
                "TINI_VERSION",
                "TINI_ARCH",
                "TINI_SHA256",
            )
        assertEquals(expected, env.keys)
        assertTrue("NODE_MAJOR" !in env, "android must not set NODE_MAJOR (no node toolchain)")
        assertEquals("amd64", env["CLI_ARCH"], "android is amd64-only")
        assertEquals("android-toolchain", env["DIST_BASE_STAGE"], "android dist layers onto the SDK stage")
    }

    @Test
    fun `android reuses the qodana-jvm dist slug and shares pins`() {
        val jvm = parseEnv("qodana-jvm")
        val android = parseEnv("qodana-android")
        assertEquals("qodana-jvm", android["QD_LINTER_SLUG"], "android reuses the qodana-jvm dist")
        assertEquals(jvm["QD_VERSION"], android["QD_VERSION"])
        assertEquals(jvm["QD_BUILD"], android["QD_BUILD"])
        assertEquals(jvm["QD_PRODUCT_INFO_CODE"], android["QD_PRODUCT_INFO_CODE"])
        assertEquals(jvm["QD_BASE_IMAGE"], android["QD_BASE_IMAGE"])
        assertEquals("IU", android["QD_PRODUCT_INFO_CODE"])
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
