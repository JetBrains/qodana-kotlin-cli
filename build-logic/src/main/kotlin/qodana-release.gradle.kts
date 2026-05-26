// Convention plugin that wraps a GraalVM-built native binary into a release-ready artifact.
//
// Applied to qodana-cli, qodana-clang, and qodana-cdnet. Each module declares its `kind` (Cli or Tool)
// via the `qodanaRelease { … }` extension, and the CI workflow injects the target OS/arch via
// `-PtargetOs=…` and `-PtargetArch=…` per matrix cell.
//
// Naming (matches GoReleaser's `.goreleaser.yaml` verbatim — DO NOT change without re-aligning the
// download-URL contract with existing Go-pipeline consumers):
//   Cli  → `qodana_<os>_<arch>.tar.gz` (linux/darwin) or `qodana_<os>_<arch>.zip` (windows).
//          Archive contains exactly one file: `qodana` (or `qodana.exe` on windows) at mode 0755.
//          The amd64 → x86_64 mapping ONLY applies to the cli archive name — kept for Go-pipeline
//          parity. The binary inside the archive is just `qodana[.exe]`.
//   Tool → `<module>_<version>_<os>_<arch>[.exe]` raw binary in `build/release/`. NO archive, NO arch
//          renaming (amd64 stays amd64). Mirrors GoReleaser's `formats: ['binary']` for clang/cdnet.
//
// Tasks:
//   releaseBinary    Sync task. dependsOn(nativeCompile). Renames the GraalVM output to its final name
//                    (Tool) or stages it as `qodana[.exe]` in build/release-staging/ (Cli).
//   releaseArchive   (Cli only) Tar (gzip, 0755) or Zip. dependsOn(releaseBinary). Produces the
//                    `qodana_<os>_<arch>.{tar.gz|zip}` archive in build/release/.
//   assembleRelease  Lifecycle. Cli → releaseArchive; Tool → releaseBinary. SBOM is generated in a
//                    dedicated workflow job (not gated on this).
//
// The CycloneDX SBOM task `cyclonedxDirectBom` is configured to emit JSON-only at
// `build/release/<module>-sbom.json`. The workflow's `sbom` job invokes it directly (not via
// assembleRelease).

import org.cyclonedx.gradle.BaseCyclonedxTask
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("org.cyclonedx.bom")
}

interface QodanaReleaseExtension {
    val kind: Property<QodanaReleaseKind>
}

val ext = extensions.create<QodanaReleaseExtension>("qodanaRelease")

// Read target OS/arch via `providers.gradleProperty` so reads are tracked by the configuration cache
// — different `-PtargetOs=X` invocations correctly invalidate cached configurations. At task-execution
// time, missing properties produce a clear error.
val targetOsProvider: Provider<String> = providers.gradleProperty("targetOs")
val targetArchProvider: Provider<String> = providers.gradleProperty("targetArch")

fun requireTargetOs(): String =
    targetOsProvider.orNull
        ?: error(
            "qodana-release: -PtargetOs is required (one of: linux, darwin, windows). " +
                "CI passes this per matrix cell.",
        )

fun requireTargetArch(): String =
    targetArchProvider.orNull
        ?: error(
            "qodana-release: -PtargetArch is required (one of: amd64, arm64). " +
                "CI passes this per matrix cell.",
        )

/**
 * Maps `amd64` → `x86_64` and otherwise returns the input unchanged. Used ONLY for the cli archive
 * filename, preserving GoReleaser's per-cli asymmetry where archives use `x86_64` but clang/cdnet raw
 * binaries use `amd64`. Documented at the top of this file.
 */
fun cliArchiveArch(arch: String): String = if (arch == "amd64") "x86_64" else arch

val releaseStagingDir: Provider<Directory> = layout.buildDirectory.dir("release-staging")
val releaseDir: Provider<Directory> = layout.buildDirectory.dir("release")

// releaseBinary: rename nativeCompile output to final filename (Tool) or stage as qodana[.exe] (Cli).
val releaseBinary = tasks.register<Sync>("releaseBinary") {
    group = "release"
    description = "Rename the GraalVM native binary to its Go-pipeline asset name."
    dependsOn("nativeCompile")

    val targetOs = providers.provider { requireTargetOs() }
    val targetArch = providers.provider { requireTargetArch() }
    val versionString = providers.provider { project.version.toString() }
    val kindProvider = ext.kind

    inputs.property("targetOs", targetOs)
    inputs.property("targetArch", targetArch)
    inputs.property("version", versionString)
    inputs.property("kind", kindProvider.map { it.name })

    val nativeOutputDir = layout.buildDirectory.dir("native/nativeCompile")
    from(nativeOutputDir) {
        include(project.name)
        include("${project.name}.exe")
    }

    // Override the destination at config time — different per Cli vs Tool kind.
    into(
        kindProvider.map { kind ->
            when (kind!!) {
                QodanaReleaseKind.Cli -> releaseStagingDir.get().asFile
                QodanaReleaseKind.Tool -> releaseDir.get().asFile
            }
        },
    )

    rename { originalName ->
        val os = targetOs.get()
        val arch = targetArch.get()
        val version = versionString.get()
        val isWindows = os == "windows"
        val exeSuffix = if (isWindows) ".exe" else ""
        when (kindProvider.get()) {
            QodanaReleaseKind.Cli -> "qodana$exeSuffix"
            QodanaReleaseKind.Tool -> "${project.name}_${version}_${os}_${arch}$exeSuffix"
        }
    }
}

// releaseArchive (Cli only): wrap the staged qodana[.exe] in qodana_<os>_<arch>.{tar.gz|zip}.
val releaseArchive = tasks.register("releaseArchive") {
    group = "release"
    description = "Wrap the qodana-cli native binary in a tar.gz (linux/darwin) or zip (windows) archive."
    onlyIf { ext.kind.orNull == QodanaReleaseKind.Cli }
    dependsOn(releaseBinary)
}

// Register both Tar and Zip but conditionally enable per cell — `Tar` and `Zip` are AbstractArchiveTask
// types, not lifecycle tasks. Wired into `releaseArchive` below.
val releaseTar = tasks.register<Tar>("releaseTar") {
    group = "release"
    description = "Tar.gz archive of the qodana-cli native binary (linux/darwin)."
    onlyIf {
        ext.kind.orNull == QodanaReleaseKind.Cli && requireTargetOs() != "windows"
    }
    dependsOn(releaseBinary)
    compression = Compression.GZIP
    filePermissions { unix("rwxr-xr-x") } // 0755 — required for the binary to be executable on linux/darwin.
    archiveExtension.set("tar.gz")
    archiveBaseName.set(
        providers.provider {
            val os = requireTargetOs()
            val arch = cliArchiveArch(requireTargetArch())
            "qodana_${os}_$arch"
        },
    )
    archiveVersion.set("") // Don't append project.version to the archive name (Go-pipeline parity).
    destinationDirectory.set(releaseDir)
    from(releaseStagingDir) {
        include("qodana") // No .exe on linux/darwin.
    }
}

val releaseZip = tasks.register<Zip>("releaseZip") {
    group = "release"
    description = "Zip archive of the qodana-cli native binary (windows)."
    onlyIf {
        ext.kind.orNull == QodanaReleaseKind.Cli && requireTargetOs() == "windows"
    }
    dependsOn(releaseBinary)
    archiveBaseName.set(
        providers.provider {
            val arch = cliArchiveArch(requireTargetArch())
            "qodana_windows_$arch"
        },
    )
    archiveVersion.set("")
    destinationDirectory.set(releaseDir)
    from(releaseStagingDir) {
        include("qodana.exe")
    }
}

releaseArchive.configure {
    dependsOn(releaseTar, releaseZip)
}

// CycloneDX SBOM: JSON-only, output to build/release/<module>-sbom.json. The SBOM workflow job invokes
// this directly per module.
//
// API surface in cyclonedx-gradle-plugin v3.2.x: `BaseCyclonedxTask` exposes `jsonOutput` and `xmlOutput`
// as `RegularFileProperty`. The XML default is on; unset it to produce JSON only. Set `jsonOutput`
// explicitly so the file lands next to the binary in `build/release/`.
tasks.withType<BaseCyclonedxTask>().configureEach {
    xmlOutput.unsetConvention()
    jsonOutput.set(releaseDir.map { it.file("${project.name}-sbom.json") })
}

// assembleRelease lifecycle.
val assembleRelease = tasks.register("assembleRelease") {
    group = "release"
    description = "Build the release-ready native artifact(s) for this module in build/release/."
    dependsOn(
        ext.kind.map { kind ->
            when (kind!!) {
                QodanaReleaseKind.Cli -> releaseArchive
                QodanaReleaseKind.Tool -> releaseBinary
            }
        },
    )
}
