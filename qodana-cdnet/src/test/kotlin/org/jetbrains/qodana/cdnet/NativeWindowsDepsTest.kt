package org.jetbrains.qodana.cdnet

import org.jetbrains.qodana.core.test.assertWindowsNativeBinaryIsSelfContained
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** See `qodana-cli/.../NativeWindowsDepsTest.kt`'s KDoc. Scoped to `qodana-cdnet.exe`. QD-14812. */
@Tag("native-deps")
class NativeWindowsDepsTest {
    @Test
    fun `Windows native binary is self-contained for VC++ runtime`() {
        assertWindowsNativeBinaryIsSelfContained(module = "qodana-cdnet")
    }
}
