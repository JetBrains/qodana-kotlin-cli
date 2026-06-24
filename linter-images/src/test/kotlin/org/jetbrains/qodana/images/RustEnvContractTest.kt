package org.jetbrains.qodana.images

import org.jetbrains.qodana.images.EnvContract.parseEnv
import org.jetbrains.qodana.images.EnvContract.pin
import org.jetbrains.qodana.images.EnvContract.publicDist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    @Test
    fun `qodana-rust env has exactly the rust key set and no node`() {
        // RustRover bundles no JS analysis, so rust is publicDist WITHOUT node, plus its install-stage keys
        // (DIST_BASE_STAGE + RUST_VERSION + RUSTUP_INIT_SHA256). Equivalently python-community minus
        // MINICONDA_* plus the rustup pins.
        val env = parseEnv("qodana-rust")
        val expected =
            publicDist + setOf("DIST_BASE_STAGE", "RUST_VERSION", "RUSTUP_INIT_SHA256")
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
    }

    @Test
    fun `rust pins match phase-0-decisions`() {
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
