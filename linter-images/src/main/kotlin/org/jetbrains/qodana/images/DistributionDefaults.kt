package org.jetbrains.qodana.images

/** Public JetBrains distribution feed. A1/A2/A3/A4 reference this; do not inline it per-command. */
const val DEFAULT_DISTRIBUTION_FEED = "https://download.jetbrains.com/qodana/feed"

/**
 * Env var carrying the bearer token that reads the private JetBrains Space packages — here the internal
 * nightly dist feed; the same token also reads the qodana-cli-deps mirror that clang/cdnet pull (a CI
 * concern, not the CLI's). Shared by every feed-touching command.
 */
const val QODANA_READ_SPACE_PACKAGES_TOKEN = "QODANA_READ_SPACE_PACKAGES_TOKEN"

/** Env var carrying the per-`.env` integrity-verification mode (`gpg` public / `sha256` internal nightly). */
const val QD_VERIFY_MODE = "QD_VERIFY_MODE"

/**
 * Dist integrity verification mode. [GPG] is the public-feed default (JetBrains signature + sha256).
 * [SHA256] is the internal-nightly feed: unsigned `.tar.gz`+`.sha256`, no `.asc`, so the GPG leg (and
 * the `.asc` download) is skipped. Selected per-`.env` via [QD_VERIFY_MODE].
 */
enum class VerifyMode {
    GPG,
    SHA256,
    ;

    companion object {
        fun fromArg(s: String): VerifyMode =
            when (s.lowercase()) {
                "gpg" -> GPG
                "sha256" -> SHA256
                else -> throw IllegalArgumentException(
                    "unknown QD_VERIFY_MODE '$s' (expected: gpg, sha256)",
                )
            }
    }
}
