package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.images.dist.DistResolver
import org.jetbrains.qodana.images.dist.DistVerifier
import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.dist.ReleaseSelector
import java.nio.file.Files

/**
 * Re-resolve the currently-pinned feed Link + .sha256 + .sha256.asc (EXACT major+build pin) and
 * RE-VERIFY GPG+sha256. Fail-closed: a 404 or any verification failure throws/exits non-zero so the
 * scheduled canary opens an issue. No `|| true` anywhere. Reuses the canonical dist-package types —
 * including the SINGLE download path [DistVerifier.download] (no local curl loop).
 */
class VerifyPinCommand(
    private val feedClient: FeedClient,
    private val verifier: DistVerifier,
    private val getEnv: (String) -> String? = System::getenv,
) : CliktCommand(name = "verify-pin") {
    private val feedUrl by option("--feed-url").default("https://download.jetbrains.com/qodana/feed")
    private val linterSlug by option("--linter-slug").required()
    private val version by option("--version").required()
    private val build by option("--build").required()
    private val channel by option("--channel").choice("public", "private").default("public")
    private val gpgKey by option("--gpg-key").path(mustExist = true).required()
    private val gpgFingerprint by option("--gpg-fingerprint").required()

    override fun run() {
        // Fail closed on a misconfigured private canary, matching provision-dist/bump-pins: a blank
        // token would otherwise fetch anonymously and surface a misleading "feed fetch failed".
        val token =
            if (channel == "private") {
                getEnv("QD_FEED_TOKEN")?.takeIf { it.isNotBlank() }
                    ?: error("--channel private selected but \$QD_FEED_TOKEN is unset or blank")
            } else {
                null
            }
        val feed = feedClient.fetch(feedUrl, linterSlug, token)
        val release = ReleaseSelector.select(feed, majorVersion = version, build = build)
        val resolved = DistResolver.resolve(release, os = "linux", arch = "amd64")
        // SAME download path the provision flow uses: curl link + .sha256 + .sha256.asc through the runner.
        val work = Files.createTempDirectory("verify-pin")
        try {
            val dl = verifier.download(resolved, token, work)
            verifier.verify(dl.archive, dl.sha256, dl.asc, gpgKey, gpgFingerprint)
        } finally {
            work.toFile().deleteRecursively()
        }
        echo("verify-pin OK: ${resolved.link}")
    }
}
