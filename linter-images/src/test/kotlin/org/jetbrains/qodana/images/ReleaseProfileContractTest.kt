package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.jetbrains.qodana.images.EnvContract.parseEnv
import org.jetbrains.qodana.images.EnvContract.pin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Contract for compose.release.yaml — the token-free public-release build profile. Each dist service
 * overrides its nightly `.env` back to the public feed + GPG + its seeded release pin, anchored to the
 * QODANA_<X>_RELEASE_* rows in phase-0-decisions.md. Test CWD is the module root.
 */
class ReleaseProfileContractTest {
    private val publicFeed = "https://download.jetbrains.com/qodana/feed"
    private val overrideKeys = setOf("QD_DISTRIBUTION_FEED", "QD_VERIFY_MODE", "QD_VERSION", "QD_BUILD")

    // slug -> its phase-0 release-pin key prefix. android* reuse the jvm(-community) dist and the ruby
    // variants share the qodana-ruby dist, so those map onto a shared key.
    private val releaseKey =
        mapOf(
            "qodana-jvm" to "QODANA_JVM_RELEASE",
            "qodana-jvm-community" to "QODANA_JVM_COMMUNITY_RELEASE",
            "qodana-android" to "QODANA_ANDROID_RELEASE",
            "qodana-android-community" to "QODANA_ANDROID_COMMUNITY_RELEASE",
            "qodana-python-community" to "QODANA_PYTHON_COMMUNITY_RELEASE",
            "qodana-python" to "QODANA_PYTHON_RELEASE",
            "qodana-js" to "QODANA_JS_RELEASE",
            "qodana-go" to "QODANA_GO_RELEASE",
            "qodana-php" to "QODANA_PHP_RELEASE",
            "qodana-ruby" to "QODANA_RUBY_RELEASE",
            "qodana-ruby-3.2" to "QODANA_RUBY_RELEASE",
            "qodana-ruby-3.4" to "QODANA_RUBY_RELEASE",
            "qodana-rust" to "QODANA_RUST_RELEASE",
            "qodana-dotnet" to "QODANA_DOTNET_RELEASE",
            "qodana-cpp" to "QODANA_CPP_RELEASE",
        )

    private val overlay: JsonNode = YAMLMapper().readTree(Path.of("compose.release.yaml").readText())

    /** Dist services = images with an IDE dist (a QD_LINTER_SLUG), derived from the real .env set. */
    private fun distServicesFromEnv(): Set<String> =
        Path
            .of("docker/images")
            .listDirectoryEntries("*.env")
            .map { it.name.removeSuffix(".env") }
            .filter { "QD_LINTER_SLUG" in parseEnv(it) }
            .toSet()

    private fun argsOf(slug: String): JsonNode {
        val args = overlay["services"]?.get(slug)?.get("build")?.get("args")
        assertNotNull(args, "$slug must declare build.args in compose.release.yaml")
        return args!!
    }

    @Test
    fun `overlay and release-key map both cover exactly the dist services`() {
        val fromEnv = distServicesFromEnv()
        assertEquals(fromEnv, releaseKey.keys, "releaseKey map must cover exactly the dist services (.env with a slug)")
        assertEquals(
            fromEnv,
            overlay["services"].fieldNames().asSequence().toSet(),
            "compose.release.yaml must cover exactly the dist services (no clang/cdnet)",
        )
    }

    @Test
    fun `every dist service overrides exactly the public feed, gpg, and its anchored release pin`() {
        for ((slug, key) in releaseKey) {
            val args = argsOf(slug)
            assertEquals(
                overrideKeys,
                args.fieldNames().asSequence().toSet(),
                "$slug release build.args must be exactly the 4 override keys (no token/extra arg)",
            )
            assertEquals(publicFeed, args["QD_DISTRIBUTION_FEED"].asText(), "$slug release feed must be public")
            assertEquals("gpg", args["QD_VERIFY_MODE"].asText(), "$slug release verify must be gpg")
            assertEquals(pin("${key}_VERSION"), args["QD_VERSION"].asText(), "$slug release major must match phase-0")
            assertEquals(pin("${key}_BUILD"), args["QD_BUILD"].asText(), "$slug release build must match phase-0")
        }
    }

    @Test
    fun `the overlay declares no secret`() {
        for (slug in releaseKey.keys) {
            assertEquals(null, overlay["services"][slug]["build"]["secrets"], "$slug release build is token-free")
        }
        assertEquals(null, overlay["secrets"], "compose.release.yaml must declare no secrets")
    }

    @Test
    fun `the overlay flips every internal-feed image back to the public feed and gpg`() {
        // Today only qodana-jvm is on the internal nightly feed; iterating by .env state auto-covers any
        // image a later family PR repoints, so an overlay left stale (still matching the internal .env) reddens.
        val internalFeedSlugs = releaseKey.keys.filter { parseEnv(it)["QD_DISTRIBUTION_FEED"] != null }
        assertTrue(internalFeedSlugs.isNotEmpty(), "expected at least one internal-feed image (today: qodana-jvm)")
        for (slug in internalFeedSlugs) {
            val env = parseEnv(slug)
            val feed = env["QD_DISTRIBUTION_FEED"]
            val args = argsOf(slug)
            assertTrue(feed != args["QD_DISTRIBUTION_FEED"].asText(), "$slug overlay must flip the feed")
            assertTrue(env["QD_VERIFY_MODE"] != args["QD_VERIFY_MODE"].asText(), "$slug must flip verify-mode")
        }
    }
}
