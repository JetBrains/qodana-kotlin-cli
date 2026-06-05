package org.jetbrains.qodana.core.test

import io.github.struppigel.parser.PELoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Verifies that a GraalVM-built Windows `.exe` is self-contained on a clean Windows install — i.e.
 * any Microsoft VC++ runtime DLL the binary imports (regular OR delay-load) must be present next
 * to the `.exe` in the same directory, so the binary runs on hosts without the Microsoft VC++
 * Redistributable installed (Server Core, LTSC SKUs, Windows containers, stripped corporate VDIs).
 *
 * Shared by the three modules that produce Windows native binaries: `qodana-cli`, `qodana-clang`,
 * `qodana-cdnet`. Each module's `NativeWindowsDepsTest` is a thin delegator that supplies the
 * module name; this function does the actual import-table inspection + assertion.
 *
 * The bundling that keeps this assertion green lives in
 * `build-logic/src/main/kotlin/internal/BundleWindowsCrt.kt`: it locates the Microsoft VC++
 * Redistributable's copy of the imported DLLs and copies them into the `nativeCompile` output
 * directory before this test reads the import table.
 *
 * ### Why "self-contained" rather than "no VC++ imports"?
 *
 * Static `/MT` (which would produce a `.exe` with zero VC++ DLL imports) is NOT viable on this
 * toolchain. GraalVM 25 (stock) — as of 2026-06-05, like GraalVM 21 before it — hard-codes `/MD` in
 * `substratevm/.../image/CCLinkerInvocation.java`:
 *
 *     // cmd.add("/MT");
 *     // Must use /MD in order to link with JDK native libraries built that way
 *     cmd.add("/MD");
 *
 * The JDK's own native libraries are built with `/MD` (OpenJDK
 * `make/autoconf/flags-cflags.m4:591`), so GraalVM cannot link them with `/MT` user objects
 * without producing a mixed-CRT binary. Upstream issue `oracle/graal#1762` tracks this; open since
 * 2019, no resolution. App-local bundling is the only viable mitigation, hence the
 * "imported AND bundled alongside" assertion shape rather than "no VC++ imports".
 *
 * ### UCRT (`ucrtbase.dll`, `api-ms-win-crt-*`)
 *
 * Intentionally outside the assertion: ships with Windows 10 1803+ / Server 2016+ and is not a
 * redistributable concern for our supported targets. If that floor drops (e.g. Server 2016 LTSC
 * without the UCRT update), extend [VC_RUNTIME_REGEX].
 *
 * QD-14812.
 *
 * @param module the Gradle module name; used both to find the `.exe` under
 *   `build/native/nativeCompile/<module>.exe` and to phrase the "run nativeCompile first" message.
 *   The test's CWD is the module's project directory (Gradle's default `Test.workingDir`).
 */
fun assertWindowsNativeBinaryIsSelfContained(module: String) {
    val exe = Path.of("build/native/nativeCompile/$module.exe")
    check(Files.exists(exe)) {
        "$exe not found. Run :$module:nativeCompile first."
    }

    val data = PELoader.loadPE(exe.toFile())
    val imports =
        (data.loadImports() + data.loadDelayLoadImports())
            .map { it.name }
            .distinct()
            .sorted()
    val vcImports = imports.filter { VC_RUNTIME_REGEX.matches(it) }
    val unbundled = vcImports.filter { dll -> !Files.exists(exe.resolveSibling(dll)) }

    if (unbundled.isNotEmpty()) {
        fail(
            buildString {
                appendLine(
                    "${exe.fileName} imports VC++ runtime DLLs that are not bundled alongside the " +
                        "binary: $unbundled.",
                )
                appendLine("All imports: ${imports.joinToString()}")
                appendLine(
                    "Fix by extending `BundleWindowsCrt.REQUIRED_DLLS` in " +
                        "build-logic/src/main/kotlin/internal/BundleWindowsCrt.kt with the " +
                        "newly-imported DLL. Static `/MT` is upstream-impossible on GraalVM 25 — " +
                        "see this function's KDoc for the rationale.",
                )
            },
        )
    }
}

// Matches the VC++ Redistributable DLL family: VCRUNTIME140 (+ _1), MSVCP140, CONCRT140, MSVCR120
// (legacy CRT), VCOMP140 (OpenMP), MFC and MFCM (Microsoft Foundation Classes), VCAMP (C++ AMP),
// ATL (Active Template Library). UCRT and api-ms-win-crt-* are intentionally excluded — see KDoc.
private val VC_RUNTIME_REGEX =
    Regex(
        """^(vcruntime|msvcp|concrt|msvcr|vcomp|mfc|mfcm|vcamp|atl)\d+[a-z]?(_\d+)?\.dll$""",
        RegexOption.IGNORE_CASE,
    )
