package org.jetbrains.qodana.images.cli

/**
 * Pure naming logic for the inner-CLI artifacts published by this repo's release pipeline,
 * mirroring build-logic `qodana-release.gradle.kts` (Go-pipeline parity):
 *   - the `cli` (qodana): a Cli-kind archive `qodana_<os>_<cliArchiveArch(arch)>.tar.gz`
 *     containing a single executable `qodana`. The amd64 -> x86_64 mapping applies ONLY here;
 *   - tools (qodana-clang/qodana-cdnet): a Tool-kind RAW binary `<binary>_<version>_<os>_<arch>`
 *     (no `.tar.gz`, no x86_64 mapping) — `version` is REQUIRED for these.
 *   - checksums manifest: `checksums.txt`, one `<sha256>  <asset-name>` line per asset.
 */
class CliArtifactResolver {
    fun releaseArchiveName(
        binary: String,
        os: String,
        arch: String,
        version: String? = null,
    ): String {
        require(binary in KNOWN_BINARIES) { "Unknown CLI binary: $binary (expected one of $KNOWN_BINARIES)" }
        return if (binary == CLI_BINARY) {
            // Cli archive: x86_64 token on amd64, arm64 unchanged.
            "${binary}_${os}_${cliArchiveArch(arch)}.tar.gz"
        } else {
            // Tool raw binary: keeps amd64, carries the version.
            val v = requireNotNull(version) { "version is required for the Tool asset name of $binary" }
            "${binary}_${v}_${os}_$arch"
        }
    }

    fun executableNameInArchive(binary: String): String {
        require(binary in KNOWN_BINARIES) { "Unknown CLI binary: $binary (expected one of $KNOWN_BINARIES)" }
        return binary
    }

    companion object {
        val KNOWN_BINARIES = setOf("qodana", "qodana-clang", "qodana-cdnet")
        const val CLI_BINARY = "qodana"
        const val CHECKSUMS_MANIFEST = "checksums.txt"

        /**
         * Maps `amd64` -> `x86_64`, otherwise unchanged. Single source of the cli-archive arch
         * asymmetry — mirrors build-logic `qodana-release.gradle.kts` `cliArchiveArch`. Applies ONLY
         * to the `cli` archive name; tools use the raw arch token.
         */
        fun cliArchiveArch(arch: String): String = if (arch == "amd64") "x86_64" else arch
    }
}
