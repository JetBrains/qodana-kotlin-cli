package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
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

    private val distributionFeed by option("--distribution-feed", help = "Feed base URL")
        .default(DEFAULT_DISTRIBUTION_FEED)
    private val linterSlug by option("--linter-slug", help = "Feed slug, e.g. qodana-jvm").required()
    private val version by option("--version", help = "Engine major version, e.g. 2025.3").required()
    private val build by option("--build", help = "Exact pinned build, e.g. 253.1234.56").required()
    private val gpgKey by option("--gpg-key", help = "Vendored JetBrains public key (GPG mode)")
        .path(mustExist = true)

    private val gpgFingerprint by option("--gpg-fingerprint", help = "Pinned signer fingerprint (GPG mode)")

    private val verifyMode by option(
        "--verify-mode",
        envvar = QD_VERIFY_MODE,
        help = "gpg (public) | sha256 (internal nightly)",
    ).choice("gpg", "sha256").default("gpg")
    private val target by option("--target", help = "IDE root staging dir (becomes the dist root)").path().required()

    override fun run() {
        val mode = VerifyMode.fromArg(verifyMode)
        if (mode == VerifyMode.GPG && (gpgKey == null || gpgFingerprint == null)) {
            throw UsageError("--gpg-key and --gpg-fingerprint are required in gpg verify mode")
        }
        val token = getEnv(QODANA_READ_SPACE_PACKAGES_TOKEN)?.takeIf { it.isNotBlank() }
        if (mode == VerifyMode.SHA256 && token == null) {
            throw UsageError("$QODANA_READ_SPACE_PACKAGES_TOKEN must be set for the internal (sha256) feed")
        }
        val feed = feedClient.fetch(distributionFeed, linterSlug, token)
        // EXACT major+build pin — never max-by-Date.
        val release = ReleaseSelector.select(feed, majorVersion = version, build = build)
        val resolved = DistResolver.resolve(release, os = "linux", arch = "amd64")

        Files.createDirectories(target)
        // ONE download path (curl through the runner); GPG-signer-match THEN sha256 (public), or sha256
        // only when the dist is unsigned (internal nightly). Fail-closed either way.
        val work = Files.createTempDirectory("provision-dist")
        try {
            val dl = verifier.download(resolved, token, work, skipAsc = mode == VerifyMode.SHA256)
            verifier.verify(dl.archive, dl.sha256, dl.asc, gpgKey, gpgFingerprint)
            // Flatten the archive's single top-level dir so `target` directly contains product-info.json.
            extractor.extractFlattened(dl.archive, target)
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}
