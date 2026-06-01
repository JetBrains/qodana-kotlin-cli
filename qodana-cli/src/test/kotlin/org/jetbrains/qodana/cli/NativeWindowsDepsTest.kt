package org.jetbrains.qodana.cli

import org.jetbrains.qodana.core.test.assertWindowsNativeBinaryIsSelfContained
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Verifies `qodana-cli.exe` is self-contained for the VC++ runtime — see
 * [assertWindowsNativeBinaryIsSelfContained] for the full rationale, the upstream-of-GraalVM
 * constraint, and the [BundleWindowsCrt][internal.BundleWindowsCrt] mitigation. QD-14812.
 *
 * Tagged `native-deps` so `./gradlew test` skips it unless `-PnativeTests=true` is passed. The CI
 * Windows native-build matrix entry opts in. For local repro:
 *
 *     ./gradlew :qodana-cli:nativeCompile \
 *               :qodana-cli:bundleWindowsCrt \
 *               :qodana-cli:test --tests "*NativeWindowsDepsTest" \
 *               -PnativeTests=true
 *
 * On non-Windows hosts (or when nativeCompile hasn't run) the assertion fails loudly — no silent
 * skip.
 */
@Tag("native-deps")
class NativeWindowsDepsTest {
    @Test
    fun `Windows native binary is self-contained for VC++ runtime`() {
        assertWindowsNativeBinaryIsSelfContained(module = "qodana-cli")
    }
}
