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
 * Per-slug `.env` contract guard (plan Phase 4.4).
 *
 * Covers every authored slug: jvm(-community), android(-community), python(-community), js, go, clang,
 * cdnet. The clang/cdnet `.env` carry NO IDE-dist/feed keys (no dist) and pin their aux tool via a
 * mirror, asserted byte-identical to phase-0-decisions.md. go is a GoLand-on-DHI-golang-base image:
 * jvm's key set (dist + node toolchain), product-info GO, default uid 1000 (the golang base does not
 * occupy 1000), GOMODCACHE redirect in lib/toolchain/go.dockerfile (see GoToolchainTest).
 *
 * Each dist image's expected key set is composed from the neutral [EnvContract] capability profiles
 * ([publicDist]/[node]/[internalFeed]); shared `parseEnv`/`pin` come from [EnvContract] too.
 *
 * Android carries DIST_BASE_STAGE (beyond the plan's verbatim key set): the dist orphan-fix
 * parameterizes `FROM ${DIST_BASE_STAGE:-base} AS dist`, and android sets it to android-toolchain so
 * the dist inherits the SDK/Corretto. jvm omits the key and falls back to base. CLI_BASE_STAGE
 * (clang's `tools`) is a build ARG, NOT an `.env` key — the clang compose service passes it.
 */
class EnvContractTest {
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
            "qodana-go",
            "qodana-php",
            "qodana-cdnet",
            "qodana-ruby",
            "qodana-ruby-3.2",
            "qodana-ruby-3.4",
            "qodana-rust",
            "qodana-dotnet",
            "qodana-cpp",
        )

    @Test
    fun `no env declares arch or tini keys (derived from TARGETARCH)`() {
        val forbidden = setOf("CLI_ARCH", "TINI_VERSION", "TINI_ARCH", "TINI_SHA256")
        for (slug in authoredSlugs) {
            val present = parseEnv(slug).keys.intersect(forbidden)
            assertTrue(present.isEmpty(), "$slug.env must not declare $forbidden (derived from TARGETARCH): $present")
        }
    }

    /** Asserts the internalFeed profile's VALUES: the internal nightly feed URL + sha256 (unsigned). */
    private fun assertInternalNightlyFeed(
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

    @Test
    fun `qodana-jvm env has exactly the jvm key set`() {
        // Canonical .env CONTRACT: an internal-feed image = publicDist + node + internalFeed, eap channel.
        val jvm = parseEnv("qodana-jvm")
        assertEquals(publicDist + node + internalFeed, jvm.keys)
        assertEquals("eap", jvm["QD_RELEASE_TYPE"], "jvm pulls the eap internal nightly, not a public release")
        assertInternalNightlyFeed(jvm, "qodana-jvm")
    }

    @Test
    fun `qodana-jvm-community env has exactly the jvm key set`() {
        // Community JVM is a normal internal-feed image like jvm; the set assert also pins the profile
        // literals to a real `.env`.
        val community = parseEnv("qodana-jvm-community")
        assertEquals(publicDist + node + internalFeed, community.keys)
        assertTrue("QD_CHANNEL" !in community, "QD_CHANNEL was removed by the foundation refactor")
        assertEquals("eap", community["QD_RELEASE_TYPE"], "jvm-community pulls the eap internal nightly")
        assertInternalNightlyFeed(community, "qodana-jvm-community")
        assertEquals("qodana-jvm-community", community["QD_LINTER_SLUG"], "jvm-community has its own dist slug")
        assertEquals(
            "IU",
            community["QD_PRODUCT_INFO_CODE"],
            "jvm-community/android-community product-info code is IU (the QDJVMC Community dist embeds the IU IDEA " +
                "platform; Community-ness comes from dist.flavour.txt)",
        )
    }

    @Test
    fun `jvm-community pins match phase-0-decisions`() {
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
        // publicDist + internalFeed (no node) + the SDK/Corretto keys + DIST_BASE_STAGE (orphan-fix
        // selector → android-toolchain). On the internal feed because it bakes the qodana-jvm dist (same slug).
        val env = parseEnv("qodana-android")
        val expected =
            publicDist + internalFeed +
                setOf(
                    "DIST_BASE_STAGE",
                    "ANDROID_SDK_VERSION",
                    "ANDROID_SDK_SHA256",
                    "CORRETTO11_IMAGE",
                    "CORRETTO17_IMAGE",
                    "DEVICEID",
                )
        assertEquals(expected, env.keys)
        assertTrue("NODE_MAJOR" !in env, "android must not set NODE_MAJOR (no node toolchain)")
        assertEquals("eap", env["QD_RELEASE_TYPE"], "android pulls the eap internal nightly")
        assertInternalNightlyFeed(env, "qodana-android")
        assertEquals("android-toolchain", env["DIST_BASE_STAGE"], "android dist layers onto the SDK stage")
    }

    @Test
    fun `qodana-android-community env has exactly the android key set and no node`() {
        // Community twin of qodana-android: SAME key set as android (a known delta of its sibling), so it
        // is asserted directly against android's keys, keeping the two android images' contracts in
        // lockstep — exactly as jvm-community mirrors jvm.
        val community = parseEnv("qodana-android-community")
        assertEquals(
            parseEnv("qodana-android").keys,
            community.keys,
            "android-community must share android's exact key set",
        )
        assertTrue("NODE_MAJOR" !in community, "android-community must not set NODE_MAJOR (no node toolchain)")
        assertTrue("QD_CHANNEL" !in community, "QD_CHANNEL was removed by the foundation refactor")
        assertEquals("eap", community["QD_RELEASE_TYPE"], "android-community pulls the eap internal nightly")
        assertInternalNightlyFeed(community, "qodana-android-community")
        assertEquals(
            "android-toolchain",
            community["DIST_BASE_STAGE"],
            "android-community dist layers onto the SDK stage",
        )
        assertEquals(
            "IU",
            community["QD_PRODUCT_INFO_CODE"],
            "jvm-community/android-community product-info code is IU (the QDJVMC Community dist embeds the IU IDEA " +
                "platform; Community-ness comes from dist.flavour.txt)",
        )
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
                "CLANG",
                "CLANG_OS",
                "CLANG_TIDY_VERSION",
                "CLANG_TIDY_MIRROR",
            )
        assertEquals(expected, env.keys)
        for (distKey in listOf("QD_LINTER_SLUG", "QD_VERSION", "QD_BUILD", "QD_PRODUCT_INFO_CODE")) {
            assertTrue(distKey !in env, "clang has no IDE dist, must not set $distKey")
        }
        assertEquals("qodana-clang", env["CLI_BINARY"], "clang's inner CLI is qodana-clang")
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
                "CLT_VERSION",
                "CLT_MIRROR",
            )
        assertEquals(expected, env.keys)
        for (distKey in listOf("QD_LINTER_SLUG", "QD_VERSION", "QD_BUILD", "QD_PRODUCT_INFO_CODE")) {
            assertTrue(distKey !in env, "cdnet has no IDE dist, must not set $distKey")
        }
        assertEquals("qodana-cdnet", env["CLI_BINARY"], "cdnet's inner CLI is qodana-cdnet")
    }

    @Test
    fun `cdnet pins match phase-0-decisions`() {
        val cdnet = parseEnv("qodana-cdnet")
        assertEquals(pin("QD_BASE_IMAGE"), cdnet["QD_BASE_IMAGE"], "cdnet base digest must match phase-0-decisions")
        assertEquals(pin("CLT_VERSION"), cdnet["CLT_VERSION"], "CLT pin must match phase-0-decisions")
        assertEquals(pin("CLT_MIRROR"), cdnet["CLT_MIRROR"], "CLT mirror must match phase-0-decisions")
    }

    @Test
    fun `qodana-python-community env has exactly the python key set and no node`() {
        // A normal IDE-dist image (like jvm-community) that layers the conda toolchain instead of node:
        // publicDist (no node) plus the conda keys (MINICONDA_VERSION + the per-arch installer shas) and
        // DIST_BASE_STAGE=conda-toolchain (the dist layers onto the conda stage, as android does onto its).
        val env = parseEnv("qodana-python-community")
        val conda = setOf("DIST_BASE_STAGE", "MINICONDA_VERSION", "MINICONDA_SHA256_X86_64", "MINICONDA_SHA256_AARCH64")
        assertEquals(publicDist + conda, env.keys)
        assertTrue("NODE_MAJOR" !in env, "python-community must not set NODE_MAJOR (conda toolchain, not node)")
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "python-community uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-python-community", env["QD_LINTER_SLUG"], "python-community has its own dist slug")
        assertEquals(
            "PY",
            env["QD_PRODUCT_INFO_CODE"],
            "python-community product-info code is PY (the QDPYC Community dist embeds the PyCharm Professional " +
                "platform; Community-ness comes from dist.flavour.txt)",
        )
        assertEquals("conda-toolchain", env["DIST_BASE_STAGE"], "python-community dist layers onto the conda stage")
    }

    @Test
    fun `python-community pins match phase-0-decisions`() {
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
        // Ultimate Python = Community Python + node — a known delta of its sibling, so asserted directly
        // against python-community's keys + NODE_MAJOR (not re-composed from profiles). product-info is PY.
        val env = parseEnv("qodana-python")
        val expected = parseEnv("qodana-python-community").keys + "NODE_MAJOR"
        assertEquals(expected, env.keys, "python must be python-community's key set plus NODE_MAJOR")
        assertEquals("qodana-python", env["QD_LINTER_SLUG"], "python has its own Ultimate dist slug")
        assertEquals("PY", env["QD_PRODUCT_INFO_CODE"], "python product-info code is PY (PyCharm Professional)")
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
    fun `python conda Miniconda shas are the upstream digests`() {
        // Upstream Anaconda digests for Miniconda py312_24.5.0-0 (verified against repo.anaconda.com 2026-06-24).
        val upstream =
            mapOf(
                "MINICONDA_SHA256_X86_64" to "4b3b3b1b99215e85fd73fb2c2d7ebf318ac942a457072de62d885056556eb83e",
                "MINICONDA_SHA256_AARCH64" to "70afe954cc8ee91f605f9aa48985bfe01ecfc10751339e8245eac7262b01298d",
            )
        for (slug in listOf("qodana-python", "qodana-python-community")) {
            val env = parseEnv(slug)
            upstream.forEach { (k, v) -> assertEquals(v, env[k], "$slug $k must be the upstream Miniconda digest") }
        }
    }

    @Test
    fun `qodana-js env has exactly the js key set and no node and no uid keys`() {
        // FIRST DHI-language-base image: node + Yarn come from the dhi.io/node base, so NO NODE_MAJOR and
        // NO node toolchain — exactly the bare publicDist profile, NO DIST_BASE_STAGE (eslint is in-place on
        // base, the dist FROMs base). This `== publicDist` assert pins the publicDist profile literal to a
        // real `.env`. The qodana uid shifts to 1001 (the node base's `node` user occupies 1000), but
        // QODANA_UID/QODANA_GID are COMPOSE BUILD ARGS (asserted by ComposeContractTest), NOT `.env` keys —
        // dockerfile-x's INCLUDE_ARGS emit order would clobber an `.env` value back to the base default. The
        // eslint pin lives in lib/toolchain/eslint/package.json (renovate-tracked), NOT in the .env.
        val env = parseEnv("qodana-js")
        assertEquals(publicDist, env.keys)
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
    }

    @Test
    fun `js pins match phase-0-decisions`() {
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
    fun `qodana-go env has exactly the go key set and node`() {
        // GoLand-on-DHI-golang-base: the golang base ships Go pre-baked but NO node, so go layers the
        // node toolchain (NODE_MAJOR) + the in-place eslint pin for its JS/TS support — publicDist + node.
        // NO DIST_BASE_STAGE (node+eslint are in-place on base, the dist FROMs base). The golang base does
        // NOT occupy uid 1000 (empirically: /etc/passwd has only root/nonroot/_apt/nobody), so — unlike
        // qodana-js's dhi.io/node base — go keeps the default uid 1000 and sets NO uid keys/build args. The
        // eslint pin lives in lib/toolchain/eslint/package.json (renovate-tracked), NOT in the .env.
        val env = parseEnv("qodana-go")
        assertEquals(publicDist + node, env.keys, "go must share jvm's PUBLIC key set (dist + node toolchain)")
        assertTrue(
            "DIST_BASE_STAGE" !in env,
            "qodana-go dist layers onto base (node+eslint are in-place), no DIST_BASE_STAGE",
        )
        assertTrue(
            "QODANA_UID" !in env && "QODANA_GID" !in env,
            "qodana-go keeps the default uid 1000 (golang base does not occupy 1000), no uid keys",
        )
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "qodana-go uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-go", env["QD_LINTER_SLUG"], "qodana-go has its own dist slug")
        assertEquals("GO", env["QD_PRODUCT_INFO_CODE"], "qodana-go product-info code is GO (GoLand)")
        assertEquals(
            parseEnv("qodana-jvm")["NODE_MAJOR"],
            env["NODE_MAJOR"],
            "go's NODE_MAJOR must match jvm's (shared node toolchain pin)",
        )
    }

    @Test
    fun `go pins match phase-0-decisions`() {
        val go = parseEnv("qodana-go")
        assertEquals(
            pin("QD_GOLANG_BASE_IMAGE"),
            go["QD_BASE_IMAGE"],
            "qodana-go base digest must match the dhi.io/golang base pin in phase-0-decisions",
        )
        assertEquals(pin("QODANA_GO_VERSION"), go["QD_VERSION"], "go major must match phase-0-decisions")
        assertEquals(pin("QODANA_GO_BUILD"), go["QD_BUILD"], "go build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_GO_PRODUCT_INFO_CODE"),
            go["QD_PRODUCT_INFO_CODE"],
            "go product-info code must match phase-0-decisions",
        )
    }

    @Test
    fun `qodana-php env has exactly the jvm key set plus the composer toolchain keys`() {
        // PhpStorm-on-DHI-php-base: the php base ships PHP pre-baked but NO node and NO composer, so php
        // layers the node toolchain (NODE_MAJOR) + the in-place eslint pin for its JS/TS support —
        // publicDist + node — PLUS the composer toolchain. Composer is a cross-image COPY (the source
        // php.Dockerfile copies it from the upstream composer image), so unlike the in-place node/eslint
        // fragments it needs its own stage: lib/toolchain/composer.dockerfile opens `php-toolchain` (FROM
        // base, so it inherits node+eslint) and COPYs composer from the digest-pinned COMPOSER_IMAGE (like
        // android's CORRETTO*_IMAGE). The dist FROMs that stage, so php carries DIST_BASE_STAGE=php-toolchain
        // (the conda/android pattern) + COMPOSER_IMAGE. The php base does NOT occupy uid 1000 (empirically:
        // /etc/passwd has only root/nonroot/_apt/nobody, like the golang base), so — unlike qodana-js — php
        // keeps the default uid 1000 and sets NO uid keys/build args. The eslint pin lives in
        // lib/toolchain/eslint/package.json (renovate-tracked), NOT in the .env.
        val env = parseEnv("qodana-php")
        val expected = publicDist + node + setOf("DIST_BASE_STAGE", "COMPOSER_IMAGE")
        assertEquals(
            expected,
            env.keys,
            "php must be publicDist + node plus DIST_BASE_STAGE + COMPOSER_IMAGE",
        )
        assertEquals(
            "php-toolchain",
            env["DIST_BASE_STAGE"],
            "qodana-php dist layers onto the php-toolchain stage (the composer COPY)",
        )
        assertTrue(
            "QODANA_UID" !in env && "QODANA_GID" !in env,
            "qodana-php keeps the default uid 1000 (php base does not occupy 1000), no uid keys",
        )
        assertTrue("QD_CHANNEL" !in env, "QD_CHANNEL was removed by the foundation refactor")
        assertTrue(
            "QD_DISTRIBUTION_FEED" !in env,
            "qodana-php uses the public feed (dockerfile default), so it must omit QD_DISTRIBUTION_FEED",
        )
        assertEquals("qodana-php", env["QD_LINTER_SLUG"], "qodana-php has its own dist slug")
        assertEquals("PS", env["QD_PRODUCT_INFO_CODE"], "qodana-php product-info code is PS (PhpStorm)")
        assertEquals(
            parseEnv("qodana-jvm")["NODE_MAJOR"],
            env["NODE_MAJOR"],
            "php's NODE_MAJOR must match jvm's (shared node toolchain pin)",
        )
    }

    @Test
    fun `php pins match phase-0-decisions`() {
        val php = parseEnv("qodana-php")
        assertEquals(
            pin("QD_PHP_BASE_IMAGE"),
            php["QD_BASE_IMAGE"],
            "qodana-php base digest must match the dhi.io/php base pin in phase-0-decisions",
        )
        assertEquals(pin("QODANA_PHP_VERSION"), php["QD_VERSION"], "php major must match phase-0-decisions")
        assertEquals(pin("QODANA_PHP_BUILD"), php["QD_BUILD"], "php build pin must match phase-0-decisions")
        assertEquals(
            pin("QODANA_PHP_PRODUCT_INFO_CODE"),
            php["QD_PRODUCT_INFO_CODE"],
            "php product-info code must match phase-0-decisions",
        )
        assertEquals(
            pin("COMPOSER_IMAGE"),
            php["COMPOSER_IMAGE"],
            "php composer image digest must match phase-0-decisions",
        )
    }

    @Test
    fun `android reuses the qodana-jvm dist slug but carries its own pin`() {
        val android = parseEnv("qodana-android")
        // android bakes the qodana-jvm dist (same slug) -- this coupling stays. VERSION/BUILD are its
        // OWN now (decoupled so jvm's later internal-nightly repoint cannot drag android along), and
        // are asserted against QODANA_ANDROID_* in `android pins match phase-0-decisions`.
        assertEquals("qodana-jvm", android["QD_LINTER_SLUG"], "android reuses the qodana-jvm dist")
        assertEquals("IU", android["QD_PRODUCT_INFO_CODE"])
    }

    @Test
    fun `android pins match phase-0-decisions`() {
        val android = parseEnv("qodana-android")
        assertEquals(pin("QODANA_ANDROID_VERSION"), android["QD_VERSION"], "android major must match phase-0-decisions")
        assertEquals(pin("QODANA_ANDROID_BUILD"), android["QD_BUILD"], "android build pin must match phase-0-decisions")
        // The decouple dropped the jvm↔android byte-identity assert; re-anchor android's base to the
        // shared phase-0 pin (the bookworm digest jvm also asserts) so a drifted digest still fails CI.
        assertEquals(pin("QD_BASE_IMAGE"), android["QD_BASE_IMAGE"], "android base digest must match phase-0-decisions")
    }

    @Test
    fun `android-community reuses the qodana-jvm-community dist slug but carries its own pin`() {
        // Community twin of `android reuses the qodana-jvm dist`: android-community bakes the
        // jvm-community dist (same slug) but carries its OWN VERSION/BUILD pin (decoupled, forward-safe
        // for a future jvm-community repoint). VERSION/BUILD asserted against QODANA_ANDROID_COMMUNITY_*.
        val android = parseEnv("qodana-android-community")
        assertEquals(
            "qodana-jvm-community",
            android["QD_LINTER_SLUG"],
            "android-community reuses the qodana-jvm-community dist",
        )
        assertEquals("IU", android["QD_PRODUCT_INFO_CODE"])
    }

    @Test
    fun `android-community pins match phase-0-decisions`() {
        val android = parseEnv("qodana-android-community")
        assertEquals(
            pin("QD_TRIXIE_BASE_IMAGE"),
            android["QD_BASE_IMAGE"],
            "android-community base digest must match the shared trixie pin in phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_ANDROID_COMMUNITY_VERSION"),
            android["QD_VERSION"],
            "android-community major must match its own pin in phase-0-decisions",
        )
        assertEquals(
            pin("QODANA_ANDROID_COMMUNITY_BUILD"),
            android["QD_BUILD"],
            "android-community build pin must match its own pin in phase-0-decisions",
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
