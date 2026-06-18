package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Per-slug `.env` contract guard for qodana-rust (QD-15041), split out of EnvContractTest to keep each
 * class focused (the RubyEnvContractTest precedent). RustRover-on-trixie-base: the shared trixie base
 * ships NO Rust, so rust layers an install STAGE (lib/toolchain/rust.dockerfile, the conda/clang
 * pattern) that fetches a sha-pinned rustup-init and installs the RUST_VERSION toolchain + rust-src;
 * the dist FROMs that stage via DIST_BASE_STAGE=rust-toolchain (an .env KEY — base.dockerfile does not
 * default DIST_BASE_STAGE, so the INCLUDE_ARGS value survives, the android/php/conda convention). SAME
 * dist/cli/runtime keys as python-community (an IDE-dist toolchain image), with the MINICONDA_*
 * installer pins swapped for RUST_VERSION + RUSTUP_INIT_SHA256. NO NODE_MAJOR: RustRover bundles no JS
 * analysis, so — unlike go/php — rust does NOT layer the node toolchain (the source rust.Dockerfile's
 * ESLINT_VERSION is a dead copy-paste vestige: no node_base COPY, no npm install). Second eap image
 * after ruby (QD_RELEASE_TYPE=eap — the feed has only eap entries).
 */
class RustEnvContractTest {
    private val imagesDir: Path = Path.of("docker/images")
    private val decisions: Path = Path.of("docs/phase-0-decisions.md")

    private fun parseEnv(slug: String): Map<String, String> {
        // Build the map by hand so a duplicate key fails LOUDLY (matches EnvContractTest.parseEnv).
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

    private fun jvmKeys(): Set<String> = parseEnv("qodana-jvm").keys

    @Test
    fun `qodana-rust env has exactly the rust key set and no node`() {
        // python-community's key set minus MINICONDA_* plus RUST_VERSION + RUSTUP_INIT_SHA256. Equally:
        // jvm's key set minus NODE_MAJOR, plus DIST_BASE_STAGE + RUST_VERSION + RUSTUP_INIT_SHA256.
        val env = parseEnv("qodana-rust")
        val expected =
            (jvmKeys() - "NODE_MAJOR") + setOf("DIST_BASE_STAGE", "RUST_VERSION", "RUSTUP_INIT_SHA256")
        assertEquals(expected, env.keys)
        assertTrue("NODE_MAJOR" !in env, "qodana-rust must not set NODE_MAJOR (RustRover bundles no JS analysis)")
        assertTrue(
            "QODANA_UID" !in env && "QODANA_GID" !in env,
            "qodana-rust keeps the default uid 1000 (trixie base does not occupy 1000), no uid keys",
        )
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "qodana-rust uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-rust", env["QD_LINTER_SLUG"], "qodana-rust has its own dist slug")
        assertEquals("RR", env["QD_PRODUCT_INFO_CODE"], "qodana-rust product-info code is RR (RustRover)")
        assertEquals("eap", env["QD_RELEASE_TYPE"], "qodana-rust feed has only eap entries")
        assertEquals("rust-toolchain", env["DIST_BASE_STAGE"], "qodana-rust dist layers onto the rust install stage")
        assertEquals("amd64", env["CLI_ARCH"], "qodana-rust is amd64-only")
    }

    @Test
    fun `rust pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val rust = parseEnv("qodana-rust")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            rust["QD_BASE_IMAGE"],
            "qodana-rust base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(pin("QODANA_RUST_VERSION"), rust["QD_VERSION"], "rust major must match phase-0-decisions")
        assertEquals(pin("QODANA_RUST_BUILD"), rust["QD_BUILD"], "rust build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_RUST_PRODUCT_INFO_CODE"),
            rust["QD_PRODUCT_INFO_CODE"],
            "rust product-info code must match phase-0-decisions",
        )
        assertEquals(pin("RUST_VERSION"), rust["RUST_VERSION"], "rust toolchain version must match phase-0-decisions")
        assertEquals(pin("RUSTUP_INIT_SHA256"), rust["RUSTUP_INIT_SHA256"], "rustup-init sha256 must match phase-0")
    }
}
