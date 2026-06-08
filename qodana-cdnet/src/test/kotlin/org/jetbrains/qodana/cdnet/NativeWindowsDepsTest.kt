package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.test.assertWindowsNativeBinaryHasNoVcRuntimeImports
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** See `qodana-cli/.../NativeWindowsDepsTest.kt`'s KDoc. Scoped to `qodana-cdnet.exe`. QD-14925. */
@Tag("native-deps")
class NativeWindowsDepsTest {
    @Test
    fun `Windows native binary has no VC++ runtime imports`() {
        assertWindowsNativeBinaryHasNoVcRuntimeImports(module = "qodana-cdnet")
    }
}
