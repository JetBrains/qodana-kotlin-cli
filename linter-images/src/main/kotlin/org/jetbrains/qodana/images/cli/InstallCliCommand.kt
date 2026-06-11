package org.jetbrains.qodana.images.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.images.process.CommandRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs the inner-CLI executable into the image.
 *
 * `--source release` downloads `<binary>_<os>_<arch>.tar.gz` + `checksums.txt`
 * from `--release-base-url`, verifies the sha256, unpacks, and places the bare
 * executable at `--target` (chmod +x).
 *
 * `--source context` copies the from-tree binary at `--context-path` to `--target`.
 * It fails loudly when the context source is absent — in CI the cli build context
 * is bound at `/cli-src`, and an empty bind must abort rather than silently ship
 * a release default.
 */
class InstallCliCommand(
    private val runner: CommandRunner,
) : CliktCommand(name = "install-cli") {
    override fun help(context: Context) = "Install the inner Qodana CLI executable into the image"

    private val binary by option("--binary", help = "Which CLI to install")
        .choice("qodana", "qodana-clang", "qodana-cdnet")
        .required()
    private val source by option("--source", help = "release: download published binary; context: copy from-tree")
        .choice("release", "context")
        .default("release")
    private val version by option("--version", help = "CLI release version (independent of the engine version)")
        .default("2026.2")
    private val os by option("--os").choice("linux").default("linux")
    private val arch by option("--arch").choice("amd64", "arm64").default("amd64")
    private val releaseBaseUrl by option("--release-base-url", help = "Base URL the release assets live under")
    private val contextPath by option("--context-path", help = "Path to the from-tree binary for --source context")
        .path()
    private val target by option("--target", help = "Destination path for the installed executable")
        .path()
        .required()
    private val workDir by option("--work-dir", help = "Scratch dir for downloads/extraction")
        .path()

    private val resolver = CliArtifactResolver()
    private val sha256 = Sha256Tool(runner)

    override fun run() {
        Files.createDirectories(target.parent)
        when (source) {
            "release" -> installFromRelease()
            "context" -> installFromContext()
            else -> error("unreachable")
        }
        target.toFile().setExecutable(true, false)
    }

    private fun installFromRelease() {
        val baseUrl = requireNotNull(releaseBaseUrl) { "--release-base-url is required for --source release" }
        val scratch = workDir ?: Files.createTempDirectory("install-cli-")
        Files.createDirectories(scratch)

        // version is required for Tool assets (clang/cdnet); harmless for the cli archive.
        val archiveName = resolver.releaseArchiveName(binary, os, arch, version)
        val archive = scratch.resolve(archiveName)
        val manifest = scratch.resolve(CliArtifactResolver.CHECKSUMS_MANIFEST)

        download("$baseUrl/$archiveName", archive)
        download("$baseUrl/${CliArtifactResolver.CHECKSUMS_MANIFEST}", manifest)

        val expected = ChecksumManifest.parse(Files.readString(manifest)).sha256For(archiveName)
        val actual = sha256.sha256(archive)
        require(expected == actual) {
            "Checksum mismatch for $archiveName: expected $expected, got $actual"
        }

        val extractDir = scratch.resolve("extracted")
        val member = resolver.executableNameInArchive(binary)
        extract(archive, extractDir, member)
        val executable = extractDir.resolve(member)
        require(Files.isRegularFile(executable)) {
            "Archive $archiveName did not contain expected executable '${executable.fileName}'"
        }
        Files.copy(executable, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun installFromContext() {
        val src = requireNotNull(contextPath) { "--context-path is required for --source context" }
        require(Files.isRegularFile(src)) {
            "Context CLI source missing or not a file: $src (is the cli build context bound and non-empty?)"
        }
        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun download(
        url: String,
        dest: Path,
    ) {
        val result = runner.run(listOf("curl", "-fsSL", "-o", dest.toString(), url))
        require(result.exitCode == 0) { "Download failed for $url (${result.exitCode}): ${result.stderr}" }
    }

    // The cli tarball is a trusted, sha256-verified, flat archive (a bare `qodana` entry alongside
    // LICENSE/README) — so extracting the single named member with `tar` is sufficient here, rather than
    // the hardened general-purpose TarGzExtractor. Selecting only `member` removes the traversal surface:
    // exactly one named entry is ever written. GNU tar normalizes any leading `./` on the member name.
    // Requires `tar`/`curl`/`sha256sum` on PATH in the builder image (an implicit dep the docker phase satisfies).
    private fun extract(
        archive: Path,
        into: Path,
        member: String,
    ) {
        Files.createDirectories(into)
        val result = runner.run(listOf("tar", "-xzf", archive.toString(), "-C", into.toString(), member))
        require(result.exitCode == 0) { "Extraction failed for $archive (${result.exitCode}): ${result.stderr}" }
    }
}
