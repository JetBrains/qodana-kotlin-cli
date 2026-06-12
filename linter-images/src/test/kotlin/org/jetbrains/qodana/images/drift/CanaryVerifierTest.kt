package org.jetbrains.qodana.images.drift

import org.jetbrains.qodana.images.dist.DistVerifier
import org.jetbrains.qodana.images.dist.VerificationException
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * The drift canary re-verifies the currently-pinned dist by reusing [DistVerifier] as-is (the SAME
 * fail-closed GPG-signer-match-then-sha256 used at provision time). These tests pin that reuse: a
 * matching signer passes, a mismatched signer throws.
 */
class CanaryVerifierTest {
    private val fingerprint = "B46DC71E03FEEB7F89D1F2491F7A8F87B9D8F501"
    private val goodSha = "a".repeat(64)

    private fun stage(tmp: Path): Triple<Path, Path, Path> {
        val archive = Files.writeString(tmp.resolve("qodana.tar.gz"), "bytes")
        val sha256 = Files.writeString(tmp.resolve("qodana.tar.gz.sha256"), "$goodSha  qodana.tar.gz\n")
        val asc = Files.writeString(tmp.resolve("qodana.tar.gz.sha256.asc"), "-----BEGIN PGP SIGNATURE-----")
        return Triple(archive, sha256, asc)
    }

    private fun gpgKey(tmp: Path): Path = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")

    @Test
    fun `canary passes when gpg signer matches and sha256 matches`(
        @TempDir tmp: Path,
    ) {
        val (archive, sha256, asc) = stage(tmp)
        val runner = FakeCommandRunner()
        runner.on({ it.contains("--import") }, CommandResult(0, "", ""))
        runner.on({ it.contains("--verify") }, CommandResult(0, "[GNUPG:] VALIDSIG $fingerprint 2026-01-01\n", ""))
        runner.on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha  qodana.tar.gz\n", ""))

        DistVerifier(runner).verify(archive, sha256, asc, gpgKey(tmp), fingerprint)

        assertTrue(runner.invocations.any { it.contains("--verify") })
    }

    @Test
    fun `canary throws when signer fingerprint does not match`(
        @TempDir tmp: Path,
    ) {
        val (archive, sha256, asc) = stage(tmp)
        val runner = FakeCommandRunner()
        runner.on({ it.contains("--import") }, CommandResult(0, "", ""))
        runner.on(
            { it.contains("--verify") },
            CommandResult(0, "[GNUPG:] VALIDSIG 0000000000000000000000000000000000000000 2026-01-01\n", ""),
        )
        runner.on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha  qodana.tar.gz\n", ""))

        assertThrows<VerificationException> {
            DistVerifier(runner).verify(archive, sha256, asc, gpgKey(tmp), fingerprint)
        }
    }
}
