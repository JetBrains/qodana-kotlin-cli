package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the native-library set the qodana-cpp (CLion) image needs at scan time (QD-15107). Two distinct
 * subsystems of the CLion dist load native code the hardened trixie base does not ship:
 *  - CLion's analysis backend is a .NET "Rider host"/ReSharper process; without the .NET runtime libs
 *    (ICU/SSL/krb5/…) it aborts at startup with exit 134 (SIGABRT — the classic .NET "no valid ICU").
 *  - The bundled JBR's font manager dlopens libfreetype.so.6 the moment CLion instantiates the CMake
 *    output-console editor during project-model generation; absent → UnsatisfiedLinkError.
 * Either failure leaves the headless analyzer wedged at "Awaits CLion backend activities" — it HANGS
 * instead of failing. The source qodana-cli cpp base pulls these in (fonts via `default-jre`; the .NET
 * runtime libs match its dotnet-community sibling and our own `lib/toolchain/dotnet.dockerfile`).
 * qodana-cpp.dockerfile installs the same set, scoped to cpp, as root, then drops back to the qodana user
 * so the shipped image still scans unprivileged. EnvContractTest cannot see this (no `.env` key), so this
 * reads the thin image directly.
 */
class CppImageTest {
    private val cpp: String = Path.of("docker/images/qodana-cpp.dockerfile").readText()

    @Test
    fun `qodana-cpp installs the JBR font-manager native libs`() {
        for (pkg in listOf("fontconfig", "libfreetype6")) {
            assertTrue(
                cpp.contains(pkg),
                "qodana-cpp must apt-install $pkg: the JBR font manager dlopens libfreetype.so.6 when CLion " +
                    "creates the CMake console editor; absent → UnsatisfiedLinkError → headless hang (QD-15107)",
            )
        }
    }

    @Test
    fun `qodana-cpp installs the dotnet-backend runtime libs`() {
        // Mirrors lib/toolchain/dotnet.dockerfile's runtime-lib set on trixie (libicu76); CLion's .NET
        // analysis backend needs the same shared libs or it SIGABRTs at startup (exit 134).
        for (pkg in listOf("libicu76", "libssl3", "libgssapi-krb5-2", "libstdc++6")) {
            assertTrue(
                cpp.contains(pkg),
                "qodana-cpp must apt-install $pkg (CLion's .NET ReSharper backend aborts at startup without " +
                    "the .NET runtime libs; mirrors qodana-dotnet's trixie set)",
            )
        }
    }

    @Test
    fun `qodana-cpp pins a self-owned clang++ for CLion's CMake and verifies it at build`() {
        // CLion's bundled CMake reads CXX/CC to find the compiler and FATALs if the path's EXISTS check fails
        // (→ "CMake configuration failed", scan hang/fail). The LLVM package's /usr/lib/llvm-N/bin/clang++
        // symlink is unreliable on the dhi base after the gcc-pin --allow-downgrades fallback, so cpp must
        // point CXX at a symlink THIS image pins to the real clang binary, and run clang++ --version at build
        // so a missing/broken compiler fails the BUILD (loud) rather than the scan (a silent late hang).
        assertTrue(
            cpp.contains("/usr/local/bin/clang++"),
            "qodana-cpp must point CXX at a self-pinned /usr/local/bin/clang++ (not the LLVM package's " +
                "/usr/lib/llvm-N/bin/clang++, which is unreliable on the dhi base — QD-15107)",
        )
        assertTrue(
            cpp.contains("clang++ --version") || cpp.contains("clang++\" --version"),
            "qodana-cpp must run clang++ --version at build so a broken compiler fails the build, not the scan",
        )
    }

    @Test
    fun `qodana-cpp final USER is the unprivileged qodana uid, not root`() {
        // The native-lib install needs root (apt), but the shipped image must scan as the unprivileged
        // qodana user — so the LAST USER directive in the cpp-local section must restore the uid.
        val lastUser =
            Regex("""(?m)^USER\s+(\S+)""")
                .findAll(cpp)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
        assertTrue(
            lastUser != null && lastUser != "0" && !lastUser.startsWith("root"),
            "qodana-cpp's final USER must be the qodana uid (not root) so scans run unprivileged; was '$lastUser'",
        )
    }
}
