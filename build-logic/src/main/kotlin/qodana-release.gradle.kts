// Convention plugin that wraps a GraalVM-built native binary into a release-ready artifact.
//
// Applied to qodana-cli, qodana-clang, and qodana-cdnet. Each module declares its `kind` (Cli or Tool)
// via the `qodanaRelease { … }` extension, and the CI workflow injects the target OS/arch via
// `-PtargetOs=…` and `-PtargetArch=…` per matrix cell.
//
// Naming (matches GoReleaser's `.goreleaser.yaml` verbatim — DO NOT change without re-aligning the
// download-URL contract with existing Go-pipeline consumers):
//   Cli  → `qodana_<os>_<arch>.tar.gz` (linux/darwin) or `qodana_<os>_<arch>.zip` (windows).
//          Archive contains `qodana` (or `qodana.exe` on windows) at mode 0755. On Windows the
//          archive also includes `vcruntime140.dll` + `vcruntime140_1.dll` next to `qodana.exe`
//          (QD-14812 app-local bundling — GraalVM 25 hard-codes /MD and the binary cannot run on
//          hosts without the VC++ Redistributable otherwise). The amd64 → x86_64 mapping ONLY
//          applies to the cli archive name — kept for Go-pipeline parity.
//   Tool → On linux/darwin: `<module>_<version>_<os>_<arch>` raw binary in `build/release/`.
//          Mirrors GoReleaser's `formats: ['binary']`. On windows: `<module>_<version>_<os>_<arch>.zip`
//          containing `<module>_<version>_<os>_<arch>.exe` + the two VC++ runtime DLLs — same
//          rationale as the cli archive. This is a deliberate divergence from `.goreleaser.yaml`
//          (was `.exe`, now `.zip`); coordinate with Go-pipeline consumers.
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

// releaseBinary: rename nativeCompile output to final filename (Tool, non-Windows) or stage as
// qodana[.exe] / <module>_<version>_<os>_<arch>.exe (Cli, or Tool on Windows for further zipping).
val releaseBinary = tasks.register<Sync>("releaseBinary") {
    group = "release"
    description = "Rename the GraalVM native binary to its Go-pipeline asset name."
    dependsOn("nativeCompile")
    // QD-14812: on Windows we also drag in the bundled VC++ runtime DLLs that
    // graalvm-native's bundleWindowsCrt placed next to the .exe.
    dependsOn(
        providers.provider {
            if (targetOsProvider.orNull == "windows") listOf("bundleWindowsCrt") else emptyList()
        },
    )

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
        // QD-14812: bundleWindowsCrt populates these on Windows. The glob matches nothing on
        // linux/darwin (no DLLs produced), so Sync is a no-op for non-Windows.
        include("*.dll")
    }

    // Override the destination at config time:
    //   Cli                  → releaseStagingDir (then releaseArchive wraps it)
    //   Tool + windows       → releaseStagingDir (then releaseToolZip wraps it)
    //   Tool + linux/darwin  → releaseDir directly (raw binary distribution, unchanged)
    into(
        kindProvider.map { kind ->
            when (kind!!) {
                QodanaReleaseKind.Cli -> releaseStagingDir.get().asFile
                QodanaReleaseKind.Tool ->
                    if (requireTargetOs() == "windows") {
                        releaseStagingDir.get().asFile
                    } else {
                        releaseDir.get().asFile
                    }
            }
        },
    )

    rename { originalName ->
        // QD-14812: bundled VC++ DLLs keep their canonical filenames so Windows resolves them.
        if (originalName.endsWith(".dll", ignoreCase = true)) {
            return@rename originalName
        }
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
    description = "Zip archive of the qodana-cli native binary + bundled VC++ runtime DLLs (windows)."
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
        // QD-14812: app-local VC++ runtime alongside the binary.
        include("*.dll")
    }
}

// QD-14812: Windows-tool zip equivalent of releaseZip. Tool kind on linux/darwin still ships a
// raw binary (releaseBinary writes directly to releaseDir for that path); on windows we wrap the
// renamed .exe + bundled DLLs in a zip with the same naming stem the raw .exe used to have.
val releaseToolZip = tasks.register<Zip>("releaseToolZip") {
    group = "release"
    description = "Zip archive of a qodana-tool windows binary + bundled VC++ runtime DLLs."
    onlyIf {
        ext.kind.orNull == QodanaReleaseKind.Tool && requireTargetOs() == "windows"
    }
    dependsOn(releaseBinary)
    archiveBaseName.set(
        providers.provider {
            val os = requireTargetOs()
            val arch = requireTargetArch()
            "${project.name}_${project.version}_${os}_$arch"
        },
    )
    archiveVersion.set("")
    destinationDirectory.set(releaseDir)
    from(releaseStagingDir) {
        // The renamed .exe carries the version + os + arch already (via releaseBinary's rename
        // block); match it loosely so we don't have to re-derive the exact string here.
        include("${project.name}_*_windows_*.exe")
        include("*.dll")
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

// assembleRelease lifecycle. Tool on Windows ships a zip (QD-14812 DLL bundle); everything else
// continues to ship per the historical Go-pipeline naming.
val assembleRelease = tasks.register("assembleRelease") {
    group = "release"
    description = "Build the release-ready native artifact(s) for this module in build/release/."
    dependsOn(
        ext.kind.map { kind ->
            when (kind!!) {
                QodanaReleaseKind.Cli -> releaseArchive
                QodanaReleaseKind.Tool ->
                    if (requireTargetOs() == "windows") releaseToolZip else releaseBinary
            }
        },
    )
}
