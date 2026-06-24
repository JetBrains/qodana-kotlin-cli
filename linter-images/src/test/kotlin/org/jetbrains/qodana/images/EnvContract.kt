package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Shared support for the linter-image `.env` contract tests: the single `parseEnv`/`pin` and the neutral
 * capability profiles each dist image's key set is composed from. The profiles are deliberately NOT
 * derived from any concrete image's `.env`, so no single image acts as the schema baseline; every dist
 * image's key-set assert composes from these profiles, so a wrong profile literal reddens those tests.
 */
object EnvContract {
    private val imagesDir: Path = Path.of("docker/images")
    private val decisions: Path = Path.of("docs/phase-0-decisions.md")

    /** Hand-built map so a duplicate key fails LOUDLY (`associate` would silently keep the last). */
    fun parseEnv(slug: String): Map<String, String> {
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

    /** Reads a `KEY = value` pin row from phase-0-decisions.md; fails loudly if absent. */
    fun pin(key: String): String =
        Regex("""^\s*$key\s*=\s*(\S+)""", RegexOption.MULTILINE).find(decisions.readText())?.groupValues?.get(1)
            ?: error("$key not recorded in $decisions")

    // Capability profiles — neutral building blocks. camelCase for detekt VariableNaming.
    val publicDist =
        setOf(
            "QD_LINTER_SLUG",
            "QD_VERSION",
            "QD_BUILD",
            "QD_RELEASE_TYPE",
            "QD_PRODUCT_INFO_CODE",
            "QD_BASE_IMAGE",
            "CLI_BINARY",
            "CLI_VERSION",
            "CLI_OS",
        )
    val node = setOf("NODE_MAJOR")
    val internalFeed = setOf("QD_DISTRIBUTION_FEED", "QD_VERIFY_MODE")

    /** Asserts the internalFeed profile's VALUES: the internal nightly feed URL + sha256 (unsigned). */
    fun assertInternalNightlyFeed(
        env: Map<String, String>,
        slug: String,
    ) {
        assertEquals(
            "https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed",
            env["QD_DISTRIBUTION_FEED"],
            "$slug fetches the internal nightly dist feed",
        )
        assertEquals("sha256", env["QD_VERIFY_MODE"], "$slug nightly dist is unsigned (sha256-only, no GPG .asc)")
    }
}
