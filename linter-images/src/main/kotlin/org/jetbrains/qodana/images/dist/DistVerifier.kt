package org.jetbrains.qodana.images.dist

import org.jetbrains.qodana.images.process.CommandRunner
import java.nio.file.Files
import java.nio.file.Path

/** The downloaded dist triple: the archive and its detached sha256 + GPG signature. */
data class DownloadedDist(
    val archive: Path,
    val sha256: Path,
    val asc: Path,
)

/**
 * Fail-closed GPG + sha256 verification of an existing UPSTREAM JetBrains signature, plus the ONE
 * download path that fetches the dist triple. [download] curls `resolved.link` + its `.sha256` +
 * `.sha256.asc` siblings through [runner] (bearer header IFF [token] != null). [verify] then
 * GPG-signer-matches FIRST (import into an EPHEMERAL homedir+keyring, then match the pinned
 * fingerprint) and only then sha256. Every gpg/sha256/curl call goes through [runner].
 */
class DistVerifier(
    private val runner: CommandRunner,
) {
    /**
     * Downloads the archive + `.sha256` + `.sha256.asc` for [resolved] into [workDir]. The detached
     * signature link is derived as `checksumLink + ".asc"`. Fail-closed: any non-zero curl throws.
     * This is the SINGLE download path shared by provision-dist and verify-pin.
     */
    fun download(
        resolved: ResolvedDist,
        token: String?,
        workDir: Path,
    ): DownloadedDist {
        Files.createDirectories(workDir)
        val archive = workDir.resolve(resolved.link.substringAfterLast('/'))
        val sha256 = workDir.resolve(resolved.checksumLink.substringAfterLast('/'))
        val asc = workDir.resolve(resolved.checksumLink.substringAfterLast('/') + ".asc")
        curl(resolved.link, archive, token)
        curl(resolved.checksumLink, sha256, token)
        curl(resolved.checksumLink + ".asc", asc, token)
        return DownloadedDist(archive, sha256, asc)
    }

    private fun curl(
        url: String,
        dest: Path,
        token: String?,
    ) {
        val cmd = mutableListOf("curl", "-fsSL", "-o", dest.toString())
        if (token != null) cmd += listOf("--header", "Authorization: Bearer $token")
        cmd += url
        val result = runner.run(cmd)
        if (result.exitCode != 0) {
            throw VerificationException("download failed for $url (exit ${result.exitCode}): ${result.stderr}")
        }
    }

    fun verify(
        archive: Path,
        sha256: Path,
        asc: Path,
        gpgKey: Path,
        fingerprint: String,
    ) {
        verifyGpg(gpgKey, fingerprint, asc, sha256, archive.parent)
        verifySha256(archive, sha256)
    }

    private fun verifyGpg(
        gpgKey: Path,
        fingerprint: String,
        asc: Path,
        sha256: Path,
        workDir: Path,
    ) {
        val homedir = Files.createDirectories(workDir.resolve("gnupghome"))
        val keyring = workDir.resolve("jetbrains.keyring.gpg")
        // --homedir is MANDATORY so no state leaks into ~/.gnupg.
        val base =
            listOf(
                "gpg",
                "--homedir",
                homedir.toString(),
                "--no-default-keyring",
                "--keyring",
                keyring.toString(),
            )

        val importResult = runner.run(base + listOf("--import", gpgKey.toString()))
        if (importResult.exitCode != 0) {
            throw VerificationException("gpg key import failed (exit ${importResult.exitCode}): ${importResult.stderr}")
        }

        val verifyResult = runner.run(base + listOf("--status-fd", "1", "--verify", asc.toString(), sha256.toString()))
        if (verifyResult.exitCode != 0) {
            throw VerificationException(
                "gpg signature verification failed (exit ${verifyResult.exitCode}): ${verifyResult.stderr}",
            )
        }
        assertValidSig(verifyResult.stdout, fingerprint)
    }

    private fun assertValidSig(
        statusOutput: String,
        fingerprint: String,
    ) {
        val want = fingerprint.replace(" ", "").uppercase()
        val matched =
            statusOutput.lineSequence().any { line ->
                val marker = "VALIDSIG "
                val idx = line.indexOf(marker)
                if (idx < 0) {
                    false
                } else {
                    val signer =
                        line
                            .substring(idx + marker.length)
                            .trim()
                            .substringBefore(' ')
                            .uppercase()
                    signer == want
                }
            }
        if (!matched) {
            throw VerificationException(
                "gpg signature is not from the pinned signer $want (no matching VALIDSIG line)",
            )
        }
    }

    private fun verifySha256(
        archive: Path,
        sha256: Path,
    ) {
        val expected =
            Files
                .readString(sha256)
                .trim()
                .substringBefore(' ')
                .lowercase()
        val result = runner.run(listOf("sha256sum", archive.toString()), workDir = archive.parent)
        if (result.exitCode != 0) {
            throw VerificationException("sha256sum failed (exit ${result.exitCode}): ${result.stderr}")
        }
        val actual =
            result.stdout
                .trim()
                .substringBefore(' ')
                .lowercase()
        if (actual != expected) {
            throw VerificationException("sha256 mismatch: expected $expected, computed $actual")
        }
    }
}
