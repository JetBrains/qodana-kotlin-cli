package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

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

    // jvm's PUBLIC dist key set: jvm is the sole internal-nightly-feed image, carrying QD_DISTRIBUTION_FEED
    // + QD_VERIFY_MODE that public dist images (cpp included) omit. Interim baseline — replaced by named
    // capability profiles in QD-15167.
    private fun jvmPublicKeys(): Set<String> = parseEnv("qodana-jvm").keys - "QD_DISTRIBUTION_FEED" - "QD_VERIFY_MODE"

    @Test
    fun `qodana-cpp env has exactly the jvm key set plus DIST_BASE_STAGE and CLANG keys`() {
        val env = parseEnv("qodana-cpp")
        val expected = jvmPublicKeys() + "DIST_BASE_STAGE" + "CLANG" + "CLANG_OS"
        assertEquals(
            expected,
            env.keys,
            "cpp must be jvm's key set (dist + node toolchain) plus DIST_BASE_STAGE + CLANG + CLANG_OS",
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
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "qodana-cpp uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-cpp", env["QD_LINTER_SLUG"], "qodana-cpp has its own dist slug")
        assertEquals("CL", env["QD_PRODUCT_INFO_CODE"], "qodana-cpp product-info code is CL (CLion)")
        assertEquals(
            "release",
            env["QD_RELEASE_TYPE"],
            "qodana-cpp is a RELEASE linter (the QDCPP feed has release entries, unlike ruby/rust)",
        )
        assertEquals("amd64", env["CLI_ARCH"], "qodana-cpp is amd64-only")
        assertEquals("qodana", env["CLI_BINARY"], "qodana-cpp's inner CLI is the generic qodana (Cli kind)")
        assertEquals(
            parseEnv("qodana-jvm")["NODE_MAJOR"],
            env["NODE_MAJOR"],
            "cpp's NODE_MAJOR must match jvm's (shared node toolchain pin)",
        )
    }

    @Test
    fun `cpp pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
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
