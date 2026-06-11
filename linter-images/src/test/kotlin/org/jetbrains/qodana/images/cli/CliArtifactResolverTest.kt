package org.jetbrains.qodana.images.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliArtifactResolverTest {
    private val resolver = CliArtifactResolver()

    @Test
    fun `qodana cli on amd64 maps to the x86_64 archive token (cliArchiveArch)`() {
        // The `cli` (qodana) release archive applies the amd64 -> x86_64 mapping for Go-pipeline
        // parity (see build-logic qodana-release.gradle.kts `cliArchiveArch`).
        assertEquals(
            "qodana_linux_x86_64.tar.gz",
            resolver.releaseArchiveName(binary = "qodana", os = "linux", arch = "amd64"),
        )
    }

    @Test
    fun `qodana cli on arm64 keeps the arm64 token`() {
        assertEquals(
            "qodana_linux_arm64.tar.gz",
            resolver.releaseArchiveName(binary = "qodana", os = "linux", arch = "arm64"),
        )
    }

    @Test
    fun `tools keep amd64 and carry the version in the raw-binary asset name`() {
        // clang/cdnet are Tool-kind: raw `<binary>_<version>_<os>_<arch>` (no .tar.gz, no x86_64 map).
        assertEquals(
            "qodana-clang_2026.2_linux_amd64",
            resolver.releaseArchiveName(binary = "qodana-clang", os = "linux", arch = "amd64", version = "2026.2"),
        )
        assertEquals(
            "qodana-cdnet_2026.2_linux_arm64",
            resolver.releaseArchiveName(binary = "qodana-cdnet", os = "linux", arch = "arm64", version = "2026.2"),
        )
    }

    @Test
    fun `unknown binary is rejected so a typo cannot fetch a non-existent asset`() {
        assertFailsWith<IllegalArgumentException> {
            resolver.releaseArchiveName(binary = "qodana-typo", os = "linux", arch = "amd64")
        }
    }

    @Test
    fun `executable inside the archive is the bare binary name`() {
        assertEquals("qodana", resolver.executableNameInArchive("qodana"))
        assertEquals("qodana-clang", resolver.executableNameInArchive("qodana-clang"))
    }

    @Test
    fun `checksums manifest name is the conventional checksums-txt`() {
        assertEquals("checksums.txt", CliArtifactResolver.CHECKSUMS_MANIFEST)
    }

    @Test
    fun `version containing a path separator is rejected (no traversal in the asset name)`() {
        // A crafted --version must not smuggle path separators into the asset name; otherwise the
        // caller's `dir.resolve(assetName)` could escape the download dir before sha verification.
        assertFailsWith<IllegalArgumentException> {
            resolver.releaseArchiveName(binary = "qodana-clang", os = "linux", arch = "amd64", version = "../../etc/x")
        }
    }

    @Test
    fun `isCliArchive distinguishes the archived cli from the raw-binary tools`() {
        // The install-cli release path branches on this: only the `cli` ships as a .tar.gz to untar;
        // tools (clang/cdnet) download as raw executables and must NOT be run through tar.
        assertTrue(resolver.isCliArchive("qodana"), "the cli ships as an archive")
        assertFalse(resolver.isCliArchive("qodana-clang"), "tools ship as raw binaries")
        assertFalse(resolver.isCliArchive("qodana-cdnet"), "tools ship as raw binaries")
    }
}
