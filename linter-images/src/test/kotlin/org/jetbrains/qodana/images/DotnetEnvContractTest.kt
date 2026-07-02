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
 * Per-slug `.env` contract guard for qodana-dotnet (QD-15042), split out of EnvContractTest to keep each
 * class focused (the Ruby/Rust precedent). Ultimate-Rider-on-trixie-base: the shared trixie base ships
 * NO .NET, so dotnet layers the SHARED `dotnet-toolchain` install stage (.NET SDK 8/9/10), plus the
 * node toolchain (NODE_MAJOR) + the in-place eslint pin (Rider analyzes JS/TS — the upstream Rider base
 * layers node + global eslint). Rider scans shell out to sudo (source PRIVILEGED=true), so dotnet
 * INCLUDEs lib/privileged.dockerfile and its dist FROMs the privileged stage: DIST_BASE_STAGE=privileged
 * is an `.env` KEY (base.dockerfile does NOT default DIST_BASE_STAGE, so the INCLUDE_ARGS value survives
 * — the android/php/ruby convention), while PRIVILEGED_BASE_STAGE=dotnet-toolchain is its dual — a
 * compose build arg only (base.dockerfile defaults it to clang-toolchain, which would clobber an `.env`
 * value), so it is NOT an `.env` key. dotnet is the FIRST dist+privileged+dotnet-toolchain image.
 *
 * Key set = ruby's key set (jvm's dist + node-toolchain keys + DIST_BASE_STAGE) PLUS `LIBICU_PKG`: the
 * shared `lib/toolchain/dotnet.dockerfile` apt-installs `${LIBICU_PKG:-libicu72}`, and the trixie base
 * needs libicu76 (the cdnet bookworm default is libicu72). Unlike ruby/rust (eap-only feeds), the QDNET
 * feed has `release` entries, so QD_RELEASE_TYPE=release. Pins are the concrete values in
 * docs/phase-0-decisions.md; this asserts byte-identity for QD_VERSION/QD_BUILD/QD_PRODUCT_INFO_CODE +
 * the shared trixie base + LIBICU_PKG.
 */
class DotnetEnvContractTest {
    @Test
    fun `qodana-dotnet env has exactly the jvm key set plus DIST_BASE_STAGE and LIBICU_PKG`() {
        val env = parseEnv("qodana-dotnet")
        val expected = publicDist + node + internalFeed + setOf("DIST_BASE_STAGE", "LIBICU_PKG")
        assertEquals(expected, env.keys)
        assertEquals(
            "privileged",
            env["DIST_BASE_STAGE"],
            "dotnet dist layers onto the privileged stage (sudo + the dotnet/node/eslint toolchains)",
        )
        assertEquals(
            "libicu76",
            env["LIBICU_PKG"],
            "dotnet builds on the trixie base, which needs libicu76 (cdnet's bookworm default is libicu72)",
        )
        assertTrue(
            "PRIVILEGED_BASE_STAGE" !in env,
            "dotnet PRIVILEGED_BASE_STAGE is a compose build arg (base.dockerfile clobbers an .env value)",
        )
        assertTrue(
            "QODANA_UID" !in env && "QODANA_GID" !in env,
            "qodana-dotnet keeps the default uid 1000 (trixie base does not occupy 1000), no uid keys",
        )
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        EnvContract.assertInternalNightlyFeed(env, "qodana-dotnet")
        assertEquals("qodana-dotnet", env["QD_LINTER_SLUG"], "qodana-dotnet has its own dist slug")
        assertEquals("RD", env["QD_PRODUCT_INFO_CODE"], "qodana-dotnet product-info code is RD (Rider)")
        assertEquals(
            "eap",
            env["QD_RELEASE_TYPE"],
            "qodana-dotnet pulls the eap internal nightly (the QDNET nightly feed is eap-only)",
        )
        assertEquals("qodana", env["CLI_BINARY"], "qodana-dotnet's inner CLI is the generic qodana (Cli kind)")
        assertEquals(
            parseEnv("qodana-jvm")["NODE_MAJOR"],
            env["NODE_MAJOR"],
            "dotnet's NODE_MAJOR must match jvm's (shared node toolchain pin)",
        )
    }

    @Test
    fun `dotnet pins match phase-0-decisions`() {
        val dotnet = parseEnv("qodana-dotnet")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            dotnet["QD_BASE_IMAGE"],
            "qodana-dotnet base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(pin("QODANA_DOTNET_VERSION"), dotnet["QD_VERSION"], "dotnet major must match phase-0-decisions")
        assertEquals(pin("QODANA_DOTNET_BUILD"), dotnet["QD_BUILD"], "dotnet build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_DOTNET_PRODUCT_INFO_CODE"),
            dotnet["QD_PRODUCT_INFO_CODE"],
            "dotnet product-info code must match phase-0-decisions",
        )
    }
}
