package org.jetbrains.qodana.images.cli

import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallCliCommandTest {
    private fun newCommand(
        runner: FakeCommandRunner,
        getEnv: (String) -> String? = { null },
    ) = InstallCliCommand(runner = runner, getEnv = getEnv)

    @Test
    fun `release source downloads archive and manifest, verifies sha, extracts to target`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("out")
        val runner = FakeCommandRunner()
        val downloadDir = tmp.resolve("dl")

        // Canonical FakeCommandRunner: on(predicate, handler) — first match wins, handler gets the argv.
        // curl writes the file named by its `-o <path>` argument. The qodana cli archive uses the
        // x86_64 token on amd64 (cliArchiveArch), so the asset is qodana_linux_x86_64.tar.gz.
        runner.on({ it.contains("curl") && it.last() == "https://rel/qodana_linux_x86_64.tar.gz" }) { argv ->
            Path.of(argv[argv.indexOf("-o") + 1]).writeText("ARCHIVE-BYTES")
            CommandResult(0, "", "")
        }
        runner.on({ it.contains("curl") && it.last() == "https://rel/checksums.txt" }) { argv ->
            Path.of(argv[argv.indexOf("-o") + 1]).writeText("feed  qodana_linux_x86_64.tar.gz\n")
            CommandResult(0, "", "")
        }
        runner.on({ it.firstOrNull() == "sha256sum" }, CommandResult(0, "feed  ignored\n", ""))
        runner.on({ it.firstOrNull() == "tar" }) { argv ->
            // emulate the archive containing a single `qodana` executable
            val dest = Path.of(argv[argv.indexOf("-C") + 1])
            Files.createDirectories(dest)
            dest.resolve("qodana").writeText("BINARY")
            CommandResult(0, "", "")
        }

        newCommand(runner).parse(
            listOf(
                "--binary",
                "qodana",
                "--source",
                "release",
                "--version",
                "2026.2",
                "--os",
                "linux",
                "--arch",
                "amd64",
                "--release-base-url",
                "https://rel",
                "--target",
                target.toString(),
                "--work-dir",
                downloadDir.toString(),
            ),
        )

        assertTrue(Files.isRegularFile(target), "expected the cli executable at --target")
        assertEquals("BINARY", target.readText())
        assertTrue(Files.isExecutable(target), "installed cli must be executable")
    }

    @Test
    fun `context source copies the from-tree binary to target`(
        @TempDir tmp: Path,
    ) {
        val ctx = tmp.resolve("cli-src/qodana")
        Files.createDirectories(ctx.parent)
        ctx.writeText("FROM-TREE-BINARY")
        val target = tmp.resolve("out/qodana")
        val runner = FakeCommandRunner()

        newCommand(runner).parse(
            listOf(
                "--binary",
                "qodana",
                "--source",
                "context",
                "--context-path",
                ctx.toString(),
                "--target",
                target.toString(),
            ),
        )

        assertEquals("FROM-TREE-BINARY", target.readText())
        assertTrue(Files.isExecutable(target))
        assertTrue(runner.invocations.isEmpty(), "context install must not shell out")
    }

    @Test
    fun `context source fails loudly when the bound context is empty`(
        @TempDir tmp: Path,
    ) {
        val missing = tmp.resolve("cli-src/qodana")
        val target = tmp.resolve("out/qodana")
        val runner = FakeCommandRunner()

        assertFailsWith<IllegalArgumentException> {
            newCommand(runner).parse(
                listOf(
                    "--binary",
                    "qodana",
                    "--source",
                    "context",
                    "--context-path",
                    missing.toString(),
                    "--target",
                    target.toString(),
                ),
            )
        }
    }

    @Test
    fun `release source aborts on checksum mismatch and installs nothing`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("out")
        val runner = FakeCommandRunner()

        runner.on({ it.contains("curl") && it.last() == "https://rel/qodana_linux_x86_64.tar.gz" }) { argv ->
            Path.of(argv[argv.indexOf("-o") + 1]).writeText("ARCHIVE-BYTES")
            CommandResult(0, "", "")
        }
        runner.on({ it.contains("curl") && it.last() == "https://rel/checksums.txt" }) { argv ->
            // manifest claims the asset hashes to "expected", ...
            Path.of(argv[argv.indexOf("-o") + 1]).writeText("expected  qodana_linux_x86_64.tar.gz\n")
            CommandResult(0, "", "")
        }
        // ... but the computed sha is something else -> fail-closed before extraction.
        runner.on({ it.firstOrNull() == "sha256sum" }, CommandResult(0, "tampered  ignored\n", ""))

        assertFailsWith<IllegalArgumentException> {
            newCommand(runner).parse(
                listOf(
                    "--binary",
                    "qodana",
                    "--source",
                    "release",
                    "--release-base-url",
                    "https://rel",
                    "--target",
                    target.toString(),
                    "--work-dir",
                    tmp.resolve("dl").toString(),
                ),
            )
        }

        assertFalse(Files.exists(target), "a checksum mismatch must leave no partial install at --target")
        assertTrue(runner.invocations.none { it.firstOrNull() == "tar" }, "must not extract an unverified archive")
    }

    @Test
    fun `release source fails loudly when the manifest lacks the asset entry`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("out")
        val runner = FakeCommandRunner()

        runner.on({ it.contains("curl") && it.last() == "https://rel/qodana_linux_x86_64.tar.gz" }) { argv ->
            Path.of(argv[argv.indexOf("-o") + 1]).writeText("ARCHIVE-BYTES")
            CommandResult(0, "", "")
        }
        runner.on({ it.contains("curl") && it.last() == "https://rel/checksums.txt" }) { argv ->
            // manifest has a row for some other asset, none for ours.
            Path.of(argv[argv.indexOf("-o") + 1]).writeText("feed  qodana_linux_arm64.tar.gz\n")
            CommandResult(0, "", "")
        }
        runner.on({ it.firstOrNull() == "sha256sum" }, CommandResult(0, "feed  ignored\n", ""))
        // A valid tar/sha so that ANY non-throwing manifest lookup would let the install succeed;
        // only the manifest's missing-entry abort can produce the expected failure here.
        runner.on({ it.firstOrNull() == "tar" }) { argv ->
            val dest = Path.of(argv[argv.indexOf("-C") + 1])
            Files.createDirectories(dest)
            dest.resolve("qodana").writeText("BINARY")
            CommandResult(0, "", "")
        }

        assertFailsWith<IllegalArgumentException> {
            newCommand(runner).parse(
                listOf(
                    "--binary",
                    "qodana",
                    "--source",
                    "release",
                    "--release-base-url",
                    "https://rel",
                    "--target",
                    target.toString(),
                    "--work-dir",
                    tmp.resolve("dl").toString(),
                ),
            )
        }

        assertFalse(Files.exists(target), "a missing manifest entry must leave no partial install at --target")
    }
}
