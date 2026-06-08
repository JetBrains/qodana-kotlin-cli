package org.jetbrains.qodana.core.test

import io.github.struppigel.parser.PELoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Verifies that a GraalVM-built Windows `.exe` is a single self-sufficient file: it imports NO
 * Microsoft VC++ redistributable runtime DLL (regular OR delay-load), so it runs on a clean Windows
 * host (Win10 1803+ / Server 2016+) with no VC++ Redistributable and no sidecar DLLs.
 *
 * The binary is built with the HybridCRT-patched GraalVM (`qodana/graalvm-hybridcrt`), which links
 * the VC++ runtime statically (`/MT`) while keeping the in-box Universal CRT (`ucrtbase.dll`)
 * dynamic. A `vcruntime140*.dll` import — or any VC++ redistributable DLL — therefore means the
 * binary was built with a stock `/MD` GraalVM instead of the patched toolchain.
 *
 * Shared by the three modules that produce Windows native binaries (`qodana-cli`, `qodana-clang`,
 * `qodana-cdnet`); each module's `NativeWindowsDepsTest` is a thin delegator supplying the module
 * name. UCRT (`ucrtbase.dll`, `api-ms-win-crt-*`) is intentionally allowed — it ships in-box on the
 * supported floor and stays dynamically linked under HybridCRT.
 *
 * QD-14925.
 *
 * @param module the Gradle module name; used to find the `.exe` under
 *   `build/native/nativeCompile/<module>.exe`.
 */
fun assertWindowsNativeBinaryHasNoVcRuntimeImports(module: String) {
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
    val forbidden = forbiddenVcRuntimeImports(imports)

    if (forbidden.isNotEmpty()) {
        fail(
            buildString {
                appendLine(
                    "${exe.fileName} imports VC++ runtime DLLs: $forbidden. A HybridCRT-built binary " +
                        "must import none — it links the VC++ runtime statically (/MT).",
                )
                appendLine("All imports: ${imports.joinToString()}")
                appendLine(
                    "If this is red, the build used a stock /MD GraalVM, not the vendored HybridCRT " +
                        "toolchain. Check the -Pcustom-graalvm wiring and GRAALVM_HOME in setup-native-build.",
                )
            },
        )
    }
}

/**
 * Returns, in input order, the DLL names belonging to the Microsoft VC++ redistributable runtime
 * family — the imports a HybridCRT `/MT` binary must NOT have. Pure and host-independent so it is
 * unit-testable without a PE file. The forbidden family mirrors `qodana/graalvm-hybridcrt`'s
 * `scripts/check_imports.py`.
 */
fun forbiddenVcRuntimeImports(importedDllNames: List<String>): List<String> =
    importedDllNames.filter { VC_RUNTIME_REGEX.containsMatchIn(it) }

// Forbidden VC++ runtime DLL families (aligned with graalvm-hybridcrt/scripts/check_imports.py):
// vccorlib, vcruntime, concrt, vcamp, vcomp, msvcp, msvcr, mfcm, mfc, atl — the trailing
// [0-9a-z_]* swallows the version/variant suffix (140, 140_1, 120u, 140ud, ...). Word boundaries
// guard against mid-word near-misses such as "statlib.dll" (contains "atl"). UCRT and
// api-ms-win-crt-* are intentionally excluded — see assertWindowsNativeBinaryHasNoVcRuntimeImports.
private val VC_RUNTIME_REGEX =
    Regex(
        """\b(?:vccorlib|vcruntime|concrt|vcamp|vcomp|msvcp|msvcr|mfcm|mfc|atl)[0-9a-z_]*\.dll\b""",
        RegexOption.IGNORE_CASE,
    )
