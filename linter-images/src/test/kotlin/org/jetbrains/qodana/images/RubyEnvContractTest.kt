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
 * Per-slug `.env` contract guard for the 3 qodana-ruby variants (QD-15040), split out of EnvContractTest
 * to keep each class focused. RubyMine-on-DHI-ruby-base: the ruby base ships ruby/gem/bundle pre-baked
 * but NO node, so ruby layers the node toolchain (NODE_MAJOR) + the in-place eslint pin + the in-place
 * gem-cache redirect (lib/toolchain/ruby.dockerfile) — jvm's key set. Ruby scans shell out to sudo to
 * install project gems (source PRIVILEGED=true), so ruby INCLUDEs lib/privileged.dockerfile and its dist
 * FROMs the privileged stage: DIST_BASE_STAGE=privileged is an .env KEY (base.dockerfile does NOT default
 * DIST_BASE_STAGE, so the INCLUDE_ARGS value survives — the android/php convention). PRIVILEGED_BASE_STAGE
 * is its dual — a compose build arg only (base.dockerfile defaults it, clobbering an .env value), so it is
 * NOT an .env key. All 3 variants share the SAME RM dist (QD_LINTER_SLUG=qodana-ruby — the IDE dist is
 * ruby-runtime-agnostic, the android precedent) and differ ONLY in QD_BASE_IMAGE.
 */
class RubyEnvContractTest {
    @Test
    fun `qodana-ruby env has exactly the jvm key set plus DIST_BASE_STAGE`() {
        val env = parseEnv("qodana-ruby")
        val expected = publicDist + node + internalFeed + setOf("DIST_BASE_STAGE")
        assertEquals(expected, env.keys)
        assertEquals(
            "privileged",
            env["DIST_BASE_STAGE"],
            "ruby dist layers onto the privileged stage (sudo + the in-place node/eslint/ruby toolchains)",
        )
        assertTrue(
            "PRIVILEGED_BASE_STAGE" !in env,
            "ruby PRIVILEGED_BASE_STAGE is a compose build arg (base.dockerfile clobbers an .env value)",
        )
        assertTrue(
            "QODANA_UID" !in env && "QODANA_GID" !in env,
            "qodana-ruby keeps the default uid 1000 (ruby base does not occupy 1000), no uid keys",
        )
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        EnvContract.assertInternalNightlyFeed(env, "qodana-ruby")
        assertEquals("qodana-ruby", env["QD_LINTER_SLUG"], "qodana-ruby's shared dist slug")
        assertEquals("RM", env["QD_PRODUCT_INFO_CODE"], "qodana-ruby product-info code is RM (RubyMine)")
        assertEquals(
            parseEnv("qodana-jvm")["NODE_MAJOR"],
            env["NODE_MAJOR"],
            "ruby's NODE_MAJOR must match jvm's (shared node toolchain pin)",
        )
    }

    @Test
    fun `ruby variants share the same dist pins and slug, differing only in base image`() {
        val primary = parseEnv("qodana-ruby")
        val sharedKeys =
            listOf(
                "QD_LINTER_SLUG",
                "QD_VERSION",
                "QD_BUILD",
                "QD_DISTRIBUTION_FEED",
                "QD_VERIFY_MODE",
                "QD_PRODUCT_INFO_CODE",
                "DIST_BASE_STAGE",
                "NODE_MAJOR",
                "CLI_BINARY",
                "CLI_VERSION",
            )
        for (variant in listOf("qodana-ruby-3.2", "qodana-ruby-3.4")) {
            val v = parseEnv(variant)
            assertEquals(primary.keys, v.keys, "$variant must share qodana-ruby's exact key set")
            for (shared in sharedKeys) {
                assertEquals(primary[shared], v[shared], "$variant must share qodana-ruby's $shared (same RM dist)")
            }
            assertTrue(primary["QD_BASE_IMAGE"] != v["QD_BASE_IMAGE"], "$variant must use its OWN ruby base digest")
            assertEquals("qodana-ruby", v["QD_LINTER_SLUG"], "$variant reuses the shared qodana-ruby dist")
        }
    }

    @Test
    fun `ruby pins match phase-0-decisions`() {
        val primary = parseEnv("qodana-ruby")
        assertEquals(
            pin("QD_RUBY_BASE_IMAGE"),
            primary["QD_BASE_IMAGE"],
            "ruby primary (3.3) base digest must match phase-0-decisions",
        )
        assertEquals(pin("QODANA_RUBY_VERSION"), primary["QD_VERSION"], "ruby major must match phase-0-decisions")
        assertEquals(pin("QODANA_RUBY_BUILD"), primary["QD_BUILD"], "ruby build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_RUBY_PRODUCT_INFO_CODE"),
            primary["QD_PRODUCT_INFO_CODE"],
            "ruby product-info code must match phase-0-decisions",
        )
        assertEquals(
            pin("QD_RUBY_32_BASE_IMAGE"),
            parseEnv("qodana-ruby-3.2")["QD_BASE_IMAGE"],
            "ruby 3.2 base digest must match phase-0-decisions",
        )
        assertEquals(
            pin("QD_RUBY_34_BASE_IMAGE"),
            parseEnv("qodana-ruby-3.4")["QD_BASE_IMAGE"],
            "ruby 3.4 base digest must match phase-0-decisions",
        )
    }
}
