package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the native-library set the qodana-rust (RustRover) image needs at scan time (QD-15111). The
 * bundled JBR's font manager dlopens libfreetype.so.6 the moment RustRover renders the Cargo-sync build
 * view during project-model configuration; absent → NoClassDefFoundError(sun.awt.X11FontManager) →
 * UnsatisfiedLinkError, which fails the sync, leaves the project unconfigured, and trips the linter's
 * own qd.rust.configuration.timeout.minutes (a 10-min "config timeout", not a hang). RustRover is a
 * JBR-only IDE — no .NET backend — so it needs only the fonts (unlike qodana-cpp, which also bundles the
 * .NET ReSharper libs). Installed cpp-style: as root in the thin image, then dropping back to the qodana
 * user so the shipped image still scans unprivileged. EnvContractTest cannot see this (no `.env` key),
 * so this reads the thin image directly. Mirrors [CppImageTest].
 */
class RustImageTest {
    private val rust: String = Path.of("docker/images/qodana-rust.dockerfile").readText()

    @Test
    fun `qodana-rust installs the JBR font-manager native libs`() {
        for (pkg in listOf("fontconfig", "libfreetype6")) {
            assertTrue(
                rust.contains(pkg),
                "qodana-rust must apt-install $pkg: the JBR font manager dlopens libfreetype.so.6 when " +
                    "RustRover renders the Cargo-sync build view; absent → UnsatisfiedLinkError → config timeout (QD-15111)",
            )
        }
    }

    @Test
    fun `qodana-rust final USER is the unprivileged qodana uid, not root`() {
        // The font-lib install needs root (apt), but the shipped image must scan as the unprivileged qodana
        // user — so the LAST USER directive in the thin image must restore the uid.
        val lastUser =
            Regex("""(?m)^USER\s+(\S+)""")
                .findAll(rust)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
        assertTrue(
            lastUser != null && lastUser != "0" && !lastUser.startsWith("root"),
            "qodana-rust's final USER must be the qodana uid (not root) so scans run unprivileged; was '$lastUser'",
        )
    }
}
