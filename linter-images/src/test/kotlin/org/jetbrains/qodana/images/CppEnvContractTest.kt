package org.jetbrains.qodana.images

import org.jetbrains.qodana.images.EnvContract.internalFeed
import org.jetbrains.qodana.images.EnvContract.node
import org.jetbrains.qodana.images.EnvContract.parseEnv
import org.jetbrains.qodana.images.EnvContract.pin
import org.jetbrains.qodana.images.EnvContract.publicDist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Per-slug `.env` contract guard for qodana-cpp (QD-15043), split out of EnvContractTest to keep each
 * class focused (the Ruby/Rust/Dotnet precedent). CLion-on-trixie-base: the shared trixie base ships NO
 * clang, so cpp REUSES `lib/toolchain/clang.dockerfile` (`FROM base AS clang-toolchain`, the LLVM apt
 * repo pinned by CLANG/CLANG_OS) — the same fragment qodana-clang uses — then the in-place node +
 * eslint fragments append onto `clang-toolchain` (CLion analyzes JS/TS). CLion scans shell out to sudo
 * (source PRIVILEGED=true), so cpp INCLUDEs lib/privileged.dockerfile; since node+eslint sit on
 * `clang-toolchain`, privileged FROMs `clang-toolchain` via base.dockerfile's GLOBAL DEFAULT
 * PRIVILEGED_BASE_STAGE — so cpp sets NO PRIVILEGED_BASE_STAGE override (the qodana-clang convention),
 * unlike ruby/dotnet whose node+eslint sit on `base`. The dist FROMs the privileged stage:
 * DIST_BASE_STAGE=privileged is an `.env` KEY (base.dockerfile does NOT default DIST_BASE_STAGE, so the
 * INCLUDE_ARGS value survives — the android/php/ruby/dotnet convention).
 *
 * Key set = jvm's key set (dist + node-toolchain keys) PLUS DIST_BASE_STAGE + CLANG + CLANG_OS. Unlike
 * ruby/rust (eap-only feeds), the QDCPP feed has `release` entries (cpp is no longer eap), so
 * QD_RELEASE_TYPE=release. CLANG=20/CLANG_OS=trixie pins the SINGLE build cell (the trixie LLVM repo row
 * in clang-versions.txt; the multi-clang tag matrix is deferred). Pins are the concrete values in
 * docs/phase-0-decisions.md; this asserts byte-identity for QD_VERSION/QD_BUILD/QD_PRODUCT_INFO_CODE +
 * the shared trixie base.
 */
class CppEnvContractTest {
    @Test
    fun `qodana-cpp env has exactly the jvm key set plus DIST_BASE_STAGE and CLANG keys`() {
        val env = parseEnv("qodana-cpp")
        val expected = publicDist + node + internalFeed + setOf("DIST_BASE_STAGE", "CLANG", "CLANG_OS")
        assertEquals(
            expected,
            env.keys,
            "cpp must be publicDist + node + internalFeed plus DIST_BASE_STAGE + CLANG + CLANG_OS",
        )
        assertEquals(
            "privileged",
            env["DIST_BASE_STAGE"],
            "cpp dist layers onto the privileged stage (sudo on top of clang-toolchain + node + eslint)",
        )
        assertEquals("20", env["CLANG"], "cpp pins the single build cell CLANG=20 (trixie LLVM repo)")
        assertEquals("trixie", env["CLANG_OS"], "cpp's CLANG_OS=trixie matches the clang-versions.txt 20-trixie row")
        assertTrue(
            "PRIVILEGED_BASE_STAGE" !in env,
            "cpp relies on base.dockerfile's clang-toolchain PRIVILEGED_BASE_STAGE default (node+eslint sit there)",
        )
        assertTrue(
            "QODANA_UID" !in env && "QODANA_GID" !in env,
            "qodana-cpp keeps the default uid 1000 (trixie base does not occupy 1000), no uid keys",
        )
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        EnvContract.assertInternalNightlyFeed(env, "qodana-cpp")
        assertEquals("qodana-cpp", env["QD_LINTER_SLUG"], "qodana-cpp has its own dist slug")
        assertEquals("CL", env["QD_PRODUCT_INFO_CODE"], "qodana-cpp product-info code is CL (CLion)")
        assertEquals(
            "eap",
            env["QD_RELEASE_TYPE"],
            "qodana-cpp pulls the eap internal nightly (the QDCPP nightly feed is eap-only)",
        )
        assertEquals("qodana", env["CLI_BINARY"], "qodana-cpp's inner CLI is the generic qodana (Cli kind)")
        assertEquals(
            parseEnv("qodana-jvm")["NODE_MAJOR"],
            env["NODE_MAJOR"],
            "cpp's NODE_MAJOR must match jvm's (shared node toolchain pin)",
        )
    }

    @Test
    fun `cpp pins match phase-0-decisions`() {
        val cpp = parseEnv("qodana-cpp")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            cpp["QD_BASE_IMAGE"],
            "qodana-cpp base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(pin("QODANA_CPP_VERSION"), cpp["QD_VERSION"], "cpp major must match phase-0-decisions")
        assertEquals(pin("QODANA_CPP_BUILD"), cpp["QD_BUILD"], "cpp build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_CPP_PRODUCT_INFO_CODE"),
            cpp["QD_PRODUCT_INFO_CODE"],
            "cpp product-info code must match phase-0-decisions",
        )
    }
}
