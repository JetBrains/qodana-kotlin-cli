package org.jetbrains.qodana.images.dist

import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DistVerifierTest {
    private val fingerprint = "B46DC71E03FEEB7F89D1F2491F7A8F87B9D8F501"
    private val goodSha = "a".repeat(64)

    /** Writes the archive/sha256/asc files into [tmp] (in production these are curl'd earlier). */
    private fun stage(
        tmp: Path,
        shaBody: String,
    ): Triple<Path, Path, Path> {
        val archive = Files.writeString(tmp.resolve("qodana-QDJVM-253.200.tar.gz"), "archive-bytes")
        val sha256 = Files.writeString(tmp.resolve("qodana-QDJVM-253.200.tar.gz.sha256"), shaBody)
        val asc = Files.writeString(tmp.resolve("qodana-QDJVM-253.200.tar.gz.sha256.asc"), "-----BEGIN PGP SIGNATURE-----")
        return Triple(archive, sha256, asc)
    }

    private fun gpgKey(tmp: Path): Path = Files.writeString(tmp.resolve("jetbrains.pub"), "PUBKEY")

    @Test
    fun `verifies gpg signer match then sha256`(
        @TempDir tmp: Path,
    ) {
        val (archive, sha256, asc) = stage(tmp, "$goodSha *qodana-QDJVM-253.200.tar.gz\n")
        val runner = FakeCommandRunner()
        runner.on({ it.contains("--import") }, CommandResult(0, "", ""))
        runner.on({ it.contains("--verify") }, CommandResult(0, "[GNUPG:] VALIDSIG $fingerprint 2025-09-15\n", ""))
        runner.on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha *qodana-QDJVM-253.200.tar.gz\n", ""))

        DistVerifier(runner).verify(archive, sha256, asc, gpgKey(tmp), fingerprint)

        // Every gpg call carries --homedir (no leak into ~/.gnupg); import+verify precede sha256.
        assertTrue(runner.invocations.filter { it.first() == "gpg" }.all { it.contains("--homedir") })
        val verbs = runner.invocations.map { call -> call.first { it == "--import" || it == "--verify" || it == "sha256sum" } }
        assertEquals(listOf("--import", "--verify", "sha256sum"), verbs)
    }

    @Test
    fun `throws on sha256 mismatch`(
        @TempDir tmp: Path,
    ) {
        val (archive, sha256, asc) = stage(tmp, "$goodSha *qodana-QDJVM-253.200.tar.gz\n")
        val runner = FakeCommandRunner()
        runner.on({ it.contains("--import") }, CommandResult(0, "", ""))
        runner.on({ it.contains("--verify") }, CommandResult(0, "[GNUPG:] VALIDSIG $fingerprint 2025-09-15\n", ""))
        // sha256sum computes a DIFFERENT digest than the .sha256 file claims.
        runner.on({ it.contains("sha256sum") }, CommandResult(0, "${"b".repeat(64)} *qodana-QDJVM-253.200.tar.gz\n", ""))

        val ex =
            assertFailsWith<VerificationException> {
                DistVerifier(runner).verify(archive, sha256, asc, gpgKey(tmp), fingerprint)
            }
        assertTrue(ex.message!!.contains("sha256 mismatch"), ex.message)
    }

    @Test
    fun `throws when signer fingerprint does not match`(
        @TempDir tmp: Path,
    ) {
        val (archive, sha256, asc) = stage(tmp, "$goodSha *qodana-QDJVM-253.200.tar.gz\n")
        val runner = FakeCommandRunner()
        runner.on({ it.contains("--import") }, CommandResult(0, "", ""))
        // Exit 0 but VALIDSIG is for an ATTACKER key, not the pinned fingerprint.
        runner.on(
            { it.contains("--verify") },
            CommandResult(0, "[GNUPG:] VALIDSIG DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF 2025-09-15\n", ""),
        )
        runner.on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha *qodana-QDJVM-253.200.tar.gz\n", ""))

        val ex =
            assertFailsWith<VerificationException> {
                DistVerifier(runner).verify(archive, sha256, asc, gpgKey(tmp), fingerprint)
            }
        assertTrue(ex.message!!.contains("pinned signer"), ex.message)
        // sha256 must NOT run once GPG fails.
        assertTrue(runner.invocations.none { call -> call.any { it == "sha256sum" } })
    }

    @Test
    fun `throws when gpg verify exits non-zero`(
        @TempDir tmp: Path,
    ) {
        val (archive, sha256, asc) = stage(tmp, "$goodSha *qodana-QDJVM-253.200.tar.gz\n")
        val runner = FakeCommandRunner()
        runner.on({ it.contains("--import") }, CommandResult(0, "", ""))
        runner.on({ it.contains("--verify") }, CommandResult(1, "", "BAD signature"))
        runner.on({ it.contains("sha256sum") }, CommandResult(0, "$goodSha *qodana-QDJVM-253.200.tar.gz\n", ""))

        val ex =
            assertFailsWith<VerificationException> {
                DistVerifier(runner).verify(archive, sha256, asc, gpgKey(tmp), fingerprint)
            }
        assertTrue(ex.message!!.contains("verification failed"), ex.message)
        assertTrue(runner.invocations.none { call -> call.any { it == "sha256sum" } })
    }
}
