package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.images.dist.DistResolver
import org.jetbrains.qodana.images.dist.DistVerifier
import org.jetbrains.qodana.images.dist.Extractor
import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.dist.ReleaseSelector
import java.nio.file.Files

/**
 * `image-tool provision-dist` — fetch + GPG/sha256-verify the IDE distribution; flatten so `--target`
 * IS the IDE root that directly contains product-info.json.
 *
 * ONE constructor (canonical 4-arg), reused unchanged by later phases. All I/O is injected so unit
 * tests use fakes. [getEnv] is the token accessor (production: `System::getenv`).
 */
class ProvisionDistCommand(
    private val feedClient: FeedClient,
    private val verifier: DistVerifier,
    private val extractor: Extractor,
    private val getEnv: (String) -> String? = System::getenv,
) : CliktCommand("provision-dist") {
    override fun help(context: Context) = "Fetch and verify the IDE distribution (GPG + sha256, fail-closed)"

    private val feedUrl by option("--feed-url", help = "Feed base URL")
        .default("https://download.jetbrains.com/qodana/feed")
    private val linterSlug by option("--linter-slug", help = "Feed slug, e.g. qodana-jvm").required()
    private val version by option("--version", help = "Engine major version, e.g. 2025.3").required()
    private val build by option("--build", help = "Exact pinned build, e.g. 253.1234.56").required()

    // Channel is the artifact SOURCE (which mirror), NOT release-vs-eap.
    private val channel by option("--channel", help = "public|private").choice("public", "private").default("public")
    private val gpgKey by option("--gpg-key", help = "Vendored JetBrains public key")
        .path(mustExist = true)
        .required()

    // The fingerprint is ALWAYS passed by the caller (dist.dockerfile/canary do `$(cat .fpr)`), so it
    // is REQUIRED here — no blank-fallback `.fpr`-read branch (single behavior).
    private val gpgFingerprint by option("--gpg-fingerprint", help = "Pinned signer fingerprint").required()
    private val target by option("--target", help = "IDE root staging dir (becomes the dist root)").path().required()

    override fun run() {
        val token = resolveToken()
        val feed = feedClient.fetch(feedUrl, linterSlug, token)
        // EXACT major+build pin — never max-by-Date.
        val release = ReleaseSelector.select(feed, majorVersion = version, build = build)
        val resolved = DistResolver.resolve(release, os = "linux", arch = "amd64")

        Files.createDirectories(target)
        // ONE download path (curl through the runner), then GPG-signer-match THEN sha256, fail-closed.
        val work = Files.createTempDirectory("provision-dist")
        try {
            val dl = verifier.download(resolved, token, work)
            verifier.verify(dl.archive, dl.sha256, dl.asc, gpgKey, gpgFingerprint)
            // Flatten the archive's single top-level dir so `target` directly contains product-info.json.
            extractor.extractFlattened(dl.archive, target)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun resolveToken(): String? {
        if (channel == "public") return null
        return getEnv(QD_FEED_TOKEN)?.takeIf { it.isNotBlank() }
            ?: throw ProgramResult(
                fail("--channel private selected but \$$QD_FEED_TOKEN is unset or blank"),
            )
    }

    private fun fail(message: String): Int {
        echo("provision-dist: $message", err = true)
        return 1
    }

    companion object {
        const val QD_FEED_TOKEN = "QD_FEED_TOKEN"
    }
}
