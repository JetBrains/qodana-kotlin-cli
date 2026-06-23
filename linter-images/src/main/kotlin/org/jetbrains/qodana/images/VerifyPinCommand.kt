package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
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
    private val distributionFeed by option("--distribution-feed").default(DEFAULT_DISTRIBUTION_FEED)
    private val linterSlug by option("--linter-slug").required()
    private val version by option("--version").required()
    private val build by option("--build").required()
    private val gpgKey by option("--gpg-key").path(mustExist = true)
    private val gpgFingerprint by option("--gpg-fingerprint")
    private val verifyMode by option("--verify-mode", envvar = QD_VERIFY_MODE)
        .choice("gpg", "sha256")
        .default("gpg")

    override fun run() {
        // A private feed needs a bearer token; a public one fetches anonymously. The loud failure for a
        // misconfigured private canary now comes from the feed fetch (FeedClient throws on non-zero curl).
        val mode = VerifyMode.fromArg(verifyMode)
        if (mode == VerifyMode.GPG && (gpgKey == null || gpgFingerprint == null)) {
            throw UsageError("--gpg-key and --gpg-fingerprint are required in gpg verify mode")
        }
        val token = getEnv(QD_FEED_TOKEN)?.takeIf { it.isNotBlank() }
        if (mode == VerifyMode.SHA256 && token == null) {
            throw UsageError("$QD_FEED_TOKEN must be set for the internal (sha256) feed")
        }
        val feed = feedClient.fetch(distributionFeed, linterSlug, token)
        val release = ReleaseSelector.select(feed, majorVersion = version, build = build)
        val resolved = DistResolver.resolve(release, os = "linux", arch = "amd64")
        // SAME download path the provision flow uses: curl link + .sha256 (+ .sha256.asc in GPG mode).
        val work = Files.createTempDirectory("verify-pin")
        try {
            val dl = verifier.download(resolved, token, work, skipAsc = mode == VerifyMode.SHA256)
            verifier.verify(dl.archive, dl.sha256, dl.asc, gpgKey, gpgFingerprint)
        } finally {
            work.toFile().deleteRecursively()
        }
        echo("verify-pin OK: ${resolved.link}")
    }
}
