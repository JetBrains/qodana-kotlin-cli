package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the Ruby gem/bundle-cache redirect (QD-15040). The DHI ruby base ships ruby/gem/bundle
 * pre-baked at /usr/local/bin and defaults GEM_HOME to /usr/local/bundle, which is ROOT-owned; the
 * image runs scans as the unprivileged uid 1000, so any Ruby project that resolves project gems
 * (`gem install` / `bundle install`) would fail to populate that cache. lib/base.dockerfile creates
 * /data/cache owned by the qodana user, so the fragment redirects GEM_HOME/BUNDLE_PATH/
 * BUNDLE_APP_CONFIG there (in-place, ruby itself pre-baked so nothing is installed) and prepends
 * /data/cache/gem/bin to PATH so project-installed gem executables (e.g. rubocop) run as uid 1000.
 * This follows lib/toolchain/go.dockerfile's ENV-redirect idiom, NOT the source qodana-cli
 * ruby.Dockerfile (which redirects only a HOME-relative BUNDLE_PATH) — a documented divergence.
 * EnvContractTest cannot see this (no `.env` key), so this reads the fragment + the ruby thin images.
 */
class RubyToolchainTest {
    private val lib: Path = Path.of("docker/lib")
    private val images: Path = Path.of("docker/images")

    @Test
    fun `ruby toolchain fragment redirects the gem cache to the writable data cache`() {
        val ruby = lib.resolve("toolchain/ruby.dockerfile").readText()
        assertTrue(
            Regex("""GEM_HOME=/data/cache/gem""").containsMatchIn(ruby),
            "ruby toolchain must set GEM_HOME=/data/cache/gem (the writable mount; the base default " +
                "/usr/local/bundle is root-owned and breaks `gem install` of project gems as uid 1000)",
        )
        assertTrue(
            Regex("""BUNDLE_PATH=/data/cache/gem""").containsMatchIn(ruby),
            "ruby toolchain must set BUNDLE_PATH=/data/cache/gem",
        )
        assertTrue(
            Regex("""BUNDLE_APP_CONFIG=/data/cache/gem""").containsMatchIn(ruby),
            "ruby toolchain must set BUNDLE_APP_CONFIG=/data/cache/gem",
        )
    }

    @Test
    fun `ruby toolchain prepends the gem bin dir to PATH`() {
        val ruby = lib.resolve("toolchain/ruby.dockerfile").readText()
        assertTrue(
            Regex("""ENV PATH="?/data/cache/gem/bin:\$\{PATH}"?""").containsMatchIn(ruby),
            "ruby toolchain must prepend /data/cache/gem/bin to PATH so project gem executables run as uid 1000",
        )
    }

    @Test
    fun `all ruby thin images include the ruby toolchain fragment`() {
        for (slug in listOf("qodana-ruby")) {
            val thin = images.resolve("$slug.dockerfile").readText()
            assertTrue(
                Regex("""(?m)^INCLUDE lib/toolchain/ruby\.dockerfile$""").containsMatchIn(thin),
                "$slug.dockerfile must INCLUDE lib/toolchain/ruby.dockerfile (the gem-cache redirect)",
            )
        }
    }
}
