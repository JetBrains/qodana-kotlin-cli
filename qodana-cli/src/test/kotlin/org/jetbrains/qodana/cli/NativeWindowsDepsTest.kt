package org.jetbrains.qodana.cli

import org.jetbrains.qodana.core.test.assertWindowsNativeBinaryHasNoVcRuntimeImports
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Verifies `qodana-cli.exe` imports no VC++ runtime DLL — see
 * [assertWindowsNativeBinaryHasNoVcRuntimeImports] for the rationale (single-file via the HybridCRT
 * GraalVM, QD-14925).
 *
 * Tagged `native-deps` so `./gradlew test` skips it unless `-PnativeTests=true` is passed; the CI
 * CLI workflow's Windows `build` matrix opts in. For local repro:
 *
 *     GRAALVM_HOME=<hybridcrt-graalvm> ./gradlew :qodana-cli:nativeCompile \
 *               :qodana-cli:test --tests "*NativeWindowsDepsTest" \
 *               -PnativeTests=true -Pcustom-graalvm=<hybridcrt-graalvm>   # same path as GRAALVM_HOME
 *
 * On non-Windows hosts (or when nativeCompile hasn't run) the assertion fails loudly — no silent skip.
 */
@Tag("native-deps")
class NativeWindowsDepsTest {
    @Test
    fun `Windows native binary has no VC++ runtime imports`() {
        assertWindowsNativeBinaryHasNoVcRuntimeImports(module = "qodana-cli")
    }
}
