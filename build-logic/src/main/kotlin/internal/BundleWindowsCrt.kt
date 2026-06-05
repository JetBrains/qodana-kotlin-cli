package internal

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * Copies the Microsoft Visual C++ runtime DLLs that GraalVM-built Windows
 * binaries import into the binary's directory, producing a self-contained
 * single-folder distribution.
 *
 * GraalVM 25 (stock) still hard-codes `/MD` on Windows, mirroring the
 * JDK's own `/MD` build (`flags-cflags.m4:591`). The produced `.exe` therefore
 * imports `VCRUNTIME140.dll` + `VCRUNTIME140_1.dll`, which only ship as part
 * of the *Microsoft Visual C++ Redistributable for Visual Studio 2015–2022*.
 * Server Core, LTSC SKUs, and stripped corporate VDIs typically don't have
 * the redistributable installed; this task makes the binary runnable there
 * without the user having to install anything separately.
 *
 * Source lookup order:
 *   1. Latest VS install's redistributable directory via `vswhere.exe`:
 *      `<VS>\VC\Redist\MSVC\<version>\<arch>\Microsoft.VC*.CRT\<dll>`.
 *      This is the canonical Microsoft-shipped copy of the redistributable.
 *   2. `C:\Windows\System32\<dll>` — fallback when `vswhere` can't find a
 *      VS install (e.g. on a CI runner without VS Installer, though
 *      `windows-latest` always has it).
 *
 * Fails loudly if neither source produces both DLLs — no silent skip.
 *
 * The exact filename list (`vcruntime140.dll`, `vcruntime140_1.dll`) is
 * derived from the Phase A test's import dump on `windows-latest`
 * (CI run #26507941487). If GraalVM's Windows toolchain shifts and a new
 * DLL family appears in the import table, `NativeWindowsDepsTest` will go
 * red and the implementer needs to extend [REQUIRED_DLLS] here in lockstep.
 *
 * Referenced from `graalvm-native.gradle.kts`; QD-14812.
 */
abstract class BundleWindowsCrt
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        /**
         * The directory the DLLs are written into — typically `build/native/nativeCompile/`,
         * shared with the `nativeCompile` task's output. Marked `@Internal` because the
         * directory itself is owned by `nativeCompile`; the actual outputs of this task are
         * declared as the individual DLLs via [bundledDllFiles].
         */
        @get:Internal
        abstract val outputDir: DirectoryProperty

        /**
         * Concrete `@OutputFiles` for Gradle's up-to-date check. Declared per-file rather than
         * via `@OutputDirectory` so we don't claim ownership of `nativeCompile`'s output dir.
         */
        @get:OutputFiles
        val bundledDllFiles: Provider<List<RegularFile>>
            get() =
                outputDir.map { dir ->
                    REQUIRED_DLLS.map { dir.file(it) }
                }

        @TaskAction
        fun bundle() {
            val target = outputDir.get().asFile.toPath()
            Files.createDirectories(target)

            val redistDir = locateRedistDir()
            val missing = mutableListOf<String>()

            for (dll in REQUIRED_DLLS) {
                val source =
                    redistDir?.resolve(dll)?.takeIf { it.isRegularFile() }
                        ?: SYSTEM32.resolve(dll).takeIf { it.isRegularFile() }
                if (source == null) {
                    missing += dll
                    continue
                }
                Files.copy(source, target.resolve(dll), StandardCopyOption.REPLACE_EXISTING)
                logger.lifecycle("Bundled $dll from $source")
            }

            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Could not locate the following Visual C++ runtime DLL(s) for app-local bundling: $missing. " +
                        "Searched: (1) VS install via vswhere (${redistDir ?: "not found"}), " +
                        "(2) $SYSTEM32. Install the Microsoft VC++ Redistributable for VS 2015–2022 " +
                        "(or Visual Studio 2022 Build Tools).",
                )
            }
        }

        /**
         * Walks `<VS>\VC\Redist\MSVC\<version>\x64\Microsoft.VC*.CRT\` to find the latest
         * Microsoft.VC<N>.CRT directory shipped with the active VS install. Returns null when
         * vswhere is missing, errors, or no matching directory exists.
         */
        private fun locateRedistDir(): Path? {
            if (!VSWHERE.exists()) return null

            val out = ByteArrayOutputStream()
            val result =
                runCatching {
                    execOperations.exec {
                        commandLine(VSWHERE.toString(), "-latest", "-property", "installationPath")
                        standardOutput = out
                        isIgnoreExitValue = true
                    }
                }
            if (result.isFailure) return null

            val vsInstall = out.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() } ?: return null
            val msvcRoot = Path.of(vsInstall, "VC", "Redist", "MSVC").takeIf { it.exists() } ?: return null

            // Pick the most recent VS-shipped CRT redist. Sort numerically per segment to stay
            // robust against Microsoft eventually shipping 14.100.x — lexicographic sort would put
            // 14.100 before 14.42 because '1' < '4' in codepoint order.
            val latestVersion =
                msvcRoot
                    .listDirectoryEntries()
                    .filter { Files.isDirectory(it) }
                    .maxWithOrNull(compareBy(VERSION_COMPARATOR) { it.fileName.toString() })
                    ?: return null

            val arch = latestVersion.resolve("x64").takeIf { it.exists() } ?: return null
            // Microsoft.VC143.CRT for VS 2022; could become Microsoft.VC144.CRT etc. Pick the
            // numerically-largest VC<N>.CRT directory rather than `firstOrNull` over an unsorted
            // listing — selection becomes deterministic when multiple redist sets coexist.
            return arch
                .listDirectoryEntries()
                .filter { Files.isDirectory(it) }
                .mapNotNull { dir ->
                    VC_CRT_DIR_REGEX.matchEntire(dir.fileName.toString())?.let { match ->
                        dir to match.groupValues[1].toInt()
                    }
                }
                .maxByOrNull { it.second }
                ?.first
        }

        companion object {
            private val SYSTEM32: Path = Path.of("C:\\Windows\\System32")
            private val VSWHERE: Path =
                Path.of(
                    "C:\\Program Files (x86)",
                    "Microsoft Visual Studio",
                    "Installer",
                    "vswhere.exe",
                )
            private val VC_CRT_DIR_REGEX = Regex("""^Microsoft\.VC(\d+)\.CRT$""")
            val REQUIRED_DLLS = listOf("vcruntime140.dll", "vcruntime140_1.dll")

            /**
             * Compares dotted-numeric version strings like `14.42.34433` or `14.100.0` segment-by-
             * segment numerically. Non-numeric or missing segments compare as 0. Used to sort
             * Microsoft VS redist directory names robustly against future segment width changes.
             */
            private val VERSION_COMPARATOR: Comparator<String> =
                Comparator { a, b ->
                    val aSegs = a.split('.').map { it.toIntOrNull() ?: 0 }
                    val bSegs = b.split('.').map { it.toIntOrNull() ?: 0 }
                    val len = maxOf(aSegs.size, bSegs.size)
                    var result = 0
                    for (i in 0 until len) {
                        val av = aSegs.getOrElse(i) { 0 }
                        val bv = bSegs.getOrElse(i) { 0 }
                        if (av != bv) {
                            result = av.compareTo(bv)
                            break
                        }
                    }
                    result
                }
        }
    }
