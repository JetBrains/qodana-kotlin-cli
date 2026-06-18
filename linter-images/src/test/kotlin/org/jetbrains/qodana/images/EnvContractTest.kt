package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Per-slug `.env` contract guard (plan Phase 4.4).
 *
 * Covers every authored slug: jvm, jvm-community, android, android-community, clang. The clang
 * `.env` carries NO IDE-dist/feed keys (clang has no dist) and pins clang-tidy via CLANG_TIDY_VERSION
 * + CLANG_TIDY_MIRROR, both asserted byte-identical to phase-0-decisions.md.
 *
 * Android carries DIST_BASE_STAGE (beyond the plan's verbatim key set): the dist orphan-fix
 * parameterizes `FROM ${DIST_BASE_STAGE:-base} AS dist`, and android sets it to android-toolchain so
 * the dist inherits the SDK/Corretto. jvm omits the key and falls back to base. CLI_BASE_STAGE
 * (clang's `tools`) is a build ARG, NOT an `.env` key — the clang compose service passes it.
 */
class EnvContractTest {
    // Test working dir is the module root (pinned once in Phase 1) — resolve relative to it directly.
    private val imagesDir: Path = Path.of("docker/images")
    private val decisions: Path = Path.of("docs/phase-0-decisions.md")

    /** Slugs whose `.env` are authored. */
    private val authoredSlugs =
        listOf(
            "qodana-jvm",
            "qodana-jvm-community",
            "qodana-android",
            "qodana-android-community",
            "qodana-clang",
            "qodana-python-community",
            "qodana-python",
            "qodana-js",
            "qodana-cdnet",
        )

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
    fun `qodana-jvm-community env has exactly the jvm key set`() {
        // Community JVM is a normal IDE-dist image like jvm: SAME key set (no QD_CHANNEL, no
        // QD_DISTRIBUTION_FEED — it uses the PUBLIC feed via the dockerfile default), differing only in
        // slug/version/build/product-info/base values. Asserting an identical key set keeps the two
        // images' contracts in lockstep.
        val community = parseEnv("qodana-jvm-community")
        assertEquals(parseEnv("qodana-jvm").keys, community.keys, "jvm-community must share jvm's exact key set")
        assertTrue("QD_CHANNEL" !in community, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in community,
            "jvm-community uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-jvm-community", community["QD_LINTER_SLUG"], "jvm-community has its own dist slug")
        assertEquals("IC", community["QD_PRODUCT_INFO_CODE"], "jvm-community product-info code is IC (Community)")
        assertEquals("amd64", community["CLI_ARCH"], "jvm-community is amd64-only")
    }

    @Test
    fun `jvm-community pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val community = parseEnv("qodana-jvm-community")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            community["QD_BASE_IMAGE"],
            "jvm-community base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_JVM_COMMUNITY_VERSION"),
            community["QD_VERSION"],
            "jvm-community major must match phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_JVM_COMMUNITY_BUILD"),
            community["QD_BUILD"],
            "jvm-community build pin must match phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_JVM_COMMUNITY_PRODUCT_INFO_CODE"),
            community["QD_PRODUCT_INFO_CODE"],
            "jvm-community product-info code must match phase-0-decisions",
        )
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
    fun `qodana-android-community env has exactly the android key set and no node`() {
        // Community twin of qodana-android: SAME key set as android (DIST_BASE_STAGE + SDK/Corretto
        // toolchain, no NODE_MAJOR), differing only in slug/product-info/base values. Asserting an
        // identical key set keeps the two android images' contracts in lockstep, exactly as
        // jvm-community mirrors jvm.
        val community = parseEnv("qodana-android-community")
        assertEquals(
            parseEnv("qodana-android").keys,
            community.keys,
            "android-community must share android's exact key set",
        )
        assertTrue("NODE_MAJOR" !in community, "android-community must not set NODE_MAJOR (no node toolchain)")
        assertTrue("QD_CHANNEL" !in community, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in community,
            "android-community uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("amd64", community["CLI_ARCH"], "android-community is amd64-only")
        assertEquals(
            "android-toolchain",
            community["DIST_BASE_STAGE"],
            "android-community dist layers onto the SDK stage",
        )
        assertEquals("IC", community["QD_PRODUCT_INFO_CODE"], "android-community product-info code is IC (Community)")
    }

    @Test
    fun `qodana-clang env has exactly the clang key set and no dist keys`() {
        // Clang has NO IDE dist (no provision-dist): it pins clang-tidy via the qodana-cli-deps mirror.
        // CLI_BASE_STAGE=tools is a build ARG the compose clang service passes, NOT an `.env` key.
        val env = parseEnv("qodana-clang")
        val expected =
            setOf(
                "QD_BASE_IMAGE",
                "CLI_BINARY",
                "CLI_VERSION",
                "CLI_OS",
                "CLI_ARCH",
                "CLANG",
                "CLANG_OS",
                "CLANG_TIDY_VERSION",
                "CLANG_TIDY_MIRROR",
                "TINI_VERSION",
                "TINI_ARCH",
                "TINI_SHA256",
            )
        assertEquals(expected, env.keys)
        for (distKey in listOf("QD_LINTER_SLUG", "QD_VERSION", "QD_BUILD", "QD_PRODUCT_INFO_CODE")) {
            assertTrue(distKey !in env, "clang has no IDE dist, must not set $distKey")
        }
        assertEquals("qodana-clang", env["CLI_BINARY"], "clang's inner CLI is qodana-clang")
        assertEquals("amd64", env["CLI_ARCH"], "clang is amd64-only")
    }

    @Test
    fun `qodana-cdnet env has exactly the cdnet key set and no dist keys`() {
        // cdnet has NO IDE dist (no provision-dist): feed-less, like clang. It pins the ReSharper CLT
        // via CLT_VERSION + CLT_MIRROR. CLI_BASE_STAGE=tools + PRIVILEGED_BASE_STAGE=dotnet-toolchain
        // are build ARGs the compose service passes, NOT .env keys.
        val env = parseEnv("qodana-cdnet")
        val expected =
            setOf(
                "QD_BASE_IMAGE",
                "CLI_BINARY",
                "CLI_VERSION",
                "CLI_OS",
                "CLI_ARCH",
                "CLT_VERSION",
                "CLT_MIRROR",
                "TINI_VERSION",
                "TINI_ARCH",
                "TINI_SHA256",
            )
        assertEquals(expected, env.keys)
        for (distKey in listOf("QD_LINTER_SLUG", "QD_VERSION", "QD_BUILD", "QD_PRODUCT_INFO_CODE")) {
            assertTrue(distKey !in env, "cdnet has no IDE dist, must not set $distKey")
        }
        assertEquals("qodana-cdnet", env["CLI_BINARY"], "cdnet's inner CLI is qodana-cdnet")
        assertEquals("amd64", env["CLI_ARCH"], "cdnet is amd64-only")
    }

    @Test
    fun `cdnet pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val cdnet = parseEnv("qodana-cdnet")
        assertEquals(pin("QD_BASE_IMAGE"), cdnet["QD_BASE_IMAGE"], "cdnet base digest must match phase-0-decisions")
        assertEquals(pin("CLT_VERSION"), cdnet["CLT_VERSION"], "CLT pin must match phase-0-decisions")
        assertEquals(pin("CLT_MIRROR"), cdnet["CLT_MIRROR"], "CLT mirror must match phase-0-decisions")
    }

    @Test
    fun `qodana-python-community env has exactly the python key set and no node`() {
        // First NEW toolchain image: a normal IDE-dist image (like jvm-community) that layers the conda
        // toolchain instead of node. SAME dist/cli/runtime keys, NO NODE_MAJOR, plus the conda keys
        // (MINICONDA_VERSION/MINICONDA_SHA256) and DIST_BASE_STAGE=conda-toolchain (the dist layers onto
        // the conda stage, mirroring android's DIST_BASE_STAGE=android-toolchain).
        val env = parseEnv("qodana-python-community")
        val expected =
            setOf(
                "QD_LINTER_SLUG",
                "QD_VERSION",
                "QD_BUILD",
                "QD_RELEASE_TYPE",
                "QD_PRODUCT_INFO_CODE",
                "QD_BASE_IMAGE",
                "DIST_BASE_STAGE",
                "CLI_BINARY",
                "CLI_VERSION",
                "CLI_OS",
                "CLI_ARCH",
                "MINICONDA_VERSION",
                "MINICONDA_SHA256",
                "TINI_VERSION",
                "TINI_ARCH",
                "TINI_SHA256",
            )
        assertEquals(expected, env.keys)
        assertTrue("NODE_MAJOR" !in env, "python-community must not set NODE_MAJOR (conda toolchain, not node)")
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "python-community uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-python-community", env["QD_LINTER_SLUG"], "python-community has its own dist slug")
        assertEquals("PC", env["QD_PRODUCT_INFO_CODE"], "python-community product-info code is PC (Community)")
        assertEquals("amd64", env["CLI_ARCH"], "python-community is amd64-only")
        assertEquals("conda-toolchain", env["DIST_BASE_STAGE"], "python-community dist layers onto the conda stage")
    }

    @Test
    fun `python-community pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val python = parseEnv("qodana-python-community")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            python["QD_BASE_IMAGE"],
            "python-community base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_PYTHON_COMMUNITY_VERSION"),
            python["QD_VERSION"],
            "python-community major must match phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_PYTHON_COMMUNITY_BUILD"),
            python["QD_BUILD"],
            "python-community build pin must match phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_PYTHON_COMMUNITY_PRODUCT_INFO_CODE"),
            python["QD_PRODUCT_INFO_CODE"],
            "python-community product-info code must match phase-0-decisions",
        )
    }

    @Test
    fun `qodana-python env has exactly the python-community key set plus node`() {
        // Ultimate Python = Community Python + node, exactly as Ultimate jvm = community-JVM-lineage +
        // node. SAME key set as python-community (conda toolchain, DIST_BASE_STAGE=conda-toolchain), PLUS
        // NODE_MAJOR for the appended node stage; product-info is PY (PyCharm Professional), not PC.
        val env = parseEnv("qodana-python")
        val expected = parseEnv("qodana-python-community").keys + "NODE_MAJOR"
        assertEquals(expected, env.keys, "python must be python-community's key set plus NODE_MAJOR")
        assertEquals("qodana-python", env["QD_LINTER_SLUG"], "python has its own Ultimate dist slug")
        assertEquals("PY", env["QD_PRODUCT_INFO_CODE"], "python product-info code is PY (PyCharm Professional)")
        assertEquals("amd64", env["CLI_ARCH"], "python is amd64-only")
        assertEquals("conda-toolchain", env["DIST_BASE_STAGE"], "python dist layers onto the conda(+node) stage")
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "python uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals(
            parseEnv("qodana-jvm")["NODE_MAJOR"],
            env["NODE_MAJOR"],
            "python's NODE_MAJOR must match jvm's (shared node toolchain pin)",
        )
    }

    @Test
    fun `python pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val python = parseEnv("qodana-python")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            python["QD_BASE_IMAGE"],
            "python base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(pin("QODANA_PYTHON_VERSION"), python["QD_VERSION"], "python major must match phase-0-decisions")
        assertEquals(pin("QODANA_PYTHON_BUILD"), python["QD_BUILD"], "python build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_PYTHON_PRODUCT_INFO_CODE"),
            python["QD_PRODUCT_INFO_CODE"],
            "python product-info code must match phase-0-decisions",
        )
    }

    @Test
    fun `qodana-js env has exactly the js key set and no node and no uid keys`() {
        // FIRST DHI-language-base image: node + Yarn come from the dhi.io/node base, so NO NODE_MAJOR and
        // NO node toolchain. SAME dist/cli/runtime keys as a normal IDE-dist image (like jvm), NO
        // DIST_BASE_STAGE (eslint is in-place on base, the dist FROMs base). The qodana uid shifts to 1001
        // (the node base's `node` user occupies 1000), but QODANA_UID/QODANA_GID are COMPOSE BUILD ARGS
        // (asserted by ComposeContractTest), NOT `.env` keys — dockerfile-x's INCLUDE_ARGS emit order would
        // clobber an `.env` value back to the base default. The eslint pin lives in
        // lib/toolchain/eslint/package.json (renovate-tracked), NOT in the .env.
        val env = parseEnv("qodana-js")
        val expected =
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
                "CLI_ARCH",
                "TINI_VERSION",
                "TINI_ARCH",
                "TINI_SHA256",
            )
        assertEquals(expected, env.keys)
        assertTrue("NODE_MAJOR" !in env, "qodana-js must not set NODE_MAJOR (node is in the DHI node base)")
        assertTrue(
            "DIST_BASE_STAGE" !in env,
            "qodana-js dist layers onto base (eslint is in-place), no DIST_BASE_STAGE",
        )
        assertTrue(
            "QODANA_UID" !in env && "QODANA_GID" !in env,
            "qodana-js uid override is a compose build arg, not an .env key (INCLUDE_ARGS would clobber it)",
        )
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "qodana-js uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-js", env["QD_LINTER_SLUG"], "qodana-js has its own dist slug")
        assertEquals("WS", env["QD_PRODUCT_INFO_CODE"], "qodana-js product-info code is WS (WebStorm)")
        assertEquals("amd64", env["CLI_ARCH"], "qodana-js is amd64-only")
    }

    @Test
    fun `js pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val js = parseEnv("qodana-js")
        assertEquals(
            pin("QD_NODE_BASE_IMAGE"),
            js["QD_BASE_IMAGE"],
            "qodana-js base digest must match the dhi.io/node base pin in phase-0-decisions",
        )
        assertEquals(pin("QODANA_JS_VERSION"), js["QD_VERSION"], "js major must match phase-0-decisions")
        assertEquals(pin("QODANA_JS_BUILD"), js["QD_BUILD"], "js build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_JS_PRODUCT_INFO_CODE"),
            js["QD_PRODUCT_INFO_CODE"],
            "js product-info code must match phase-0-decisions",
        )
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
    fun `android-community reuses the qodana-jvm-community dist slug and shares pins`() {
        // Community twin of `android reuses the qodana-jvm dist`: android-community shares the
        // jvm-community Community dist exactly as android shares the jvm Ultimate dist.
        val community = parseEnv("qodana-jvm-community")
        val android = parseEnv("qodana-android-community")
        assertEquals(
            "qodana-jvm-community",
            android["QD_LINTER_SLUG"],
            "android-community reuses the qodana-jvm-community dist",
        )
        assertEquals(community["QD_VERSION"], android["QD_VERSION"])
        assertEquals(community["QD_BUILD"], android["QD_BUILD"])
        assertEquals(community["QD_PRODUCT_INFO_CODE"], android["QD_PRODUCT_INFO_CODE"])
        assertEquals(community["QD_BASE_IMAGE"], android["QD_BASE_IMAGE"])
        assertEquals("IC", android["QD_PRODUCT_INFO_CODE"])
    }

    @Test
    fun `android-community pins match phase-0-decisions`() {
        val d = decisions.readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in $decisions")
        val android = parseEnv("qodana-android-community")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            android["QD_BASE_IMAGE"],
            "android-community base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_JVM_COMMUNITY_VERSION"),
            android["QD_VERSION"],
            "android-community major must match the Community JVM dist pin in phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_JVM_COMMUNITY_BUILD"),
            android["QD_BUILD"],
            "android-community build pin must match the Community JVM dist pin in phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_JVM_COMMUNITY_PRODUCT_INFO_CODE"),
            android["QD_PRODUCT_INFO_CODE"],
            "android-community product-info code must match the Community JVM dist pin in phase-0-decisions",
        )
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
        val clang = parseEnv("qodana-clang")
        assertEquals(pin("QD_BASE_IMAGE"), jvm["QD_BASE_IMAGE"], "base digest must match phase-0-decisions")
        assertEquals(pin("QODANA_JVM_VERSION"), jvm["QD_VERSION"], "jvm major must match phase-0-decisions")
        assertEquals(pin("QODANA_JVM_BUILD"), jvm["QD_BUILD"], "jvm build pin must match phase-0-decisions")
        assertEquals("IU", jvm["QD_PRODUCT_INFO_CODE"], "jvm product-info code is IU")
        assertEquals(
            pin("CLANG_TIDY_VERSION"),
            clang["CLANG_TIDY_VERSION"],
            "clang-tidy pin must match phase-0-decisions",
        )
        assertEquals(
            pin("CLANG_TIDY_MIRROR"),
            clang["CLANG_TIDY_MIRROR"],
            "clang-tidy mirror must match phase-0-decisions",
        )
        // clang shares the SAME pinned hardened base as jvm/android.
        assertEquals(pin("QD_BASE_IMAGE"), clang["QD_BASE_IMAGE"], "clang base digest must match phase-0-decisions")
    }
}
