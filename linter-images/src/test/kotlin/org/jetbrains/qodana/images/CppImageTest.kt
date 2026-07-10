package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertFalse
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
        // The .NET-specific libs CLion's ReSharper backend needs that the base lacks (or it SIGABRTs at
        // startup, exit 134). The gcc-runtime libs it also needs (libstdc++6/libgcc-s1/libc6/zlib1g) are
        // already present and are deliberately NOT re-installed — pulling them evicts clang (see the
        // gcc-stock pin in the dockerfile), so they must NOT appear in the install list.
        for (pkg in listOf("libicu76", "libssl3", "libgssapi-krb5-2", "tzdata")) {
            assertTrue(
                cpp.contains(pkg),
                "qodana-cpp must apt-install $pkg (CLion's .NET ReSharper backend aborts at startup without " +
                    "the .NET runtime libs; mirrors qodana-dotnet's trixie set)",
            )
        }
    }

    @Test
    fun `qodana-cpp does not re-install the gcc-runtime libs that would evict clang`() {
        // clang-20's libobjc-14-dev `=`-pins the stock gcc-14 runtime; explicitly installing libstdc++6 etc.
        // drags in the +dhi runtime and apt evicts clang-20 (and /usr/lib/llvm) to resolve. They're already
        // present, so the install list must omit them and the layer must re-assert the gcc-stock apt pin.
        val installList = cpp.substringAfter("apt-get install").substringBefore("rm -f /etc/apt/preferences")
        for (pkg in listOf("libstdc++6", "libgcc-s1", "zlib1g")) {
            assertTrue(
                !installList.contains(pkg),
                "qodana-cpp must NOT apt-install $pkg in the cpp-local layer — it is present already and " +
                    "pulling it evicts clang-20 via the libobjc-14-dev pin (QD-15107)",
            )
        }
        assertTrue(
            cpp.contains("/etc/apt/preferences.d/gcc-stock"),
            "qodana-cpp must re-assert the gcc-stock apt pin so its native-lib install cannot evict clang-20",
        )
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

    @Test
    fun `cpp libicu major and gcc-stock pin are CLANG_OS-aware, not trixie-hardcoded`() {
        val dockerfile =
            java.nio.file.Path
                .of("docker/images/qodana-cpp.dockerfile")
                .let {
                    java.nio.file.Files
                        .readString(it)
                }
        // Anchor on the real `case "${CLANG_OS}" in … esac` block, then pin each arm inside it, so a stray
        // matching string in a comment or unrelated block can't satisfy the assertion.
        val caseBlock =
            Regex("""case\s+"\$\{CLANG_OS}"\s+in([\s\S]*?)esac""").find(dockerfile)?.groupValues?.get(1)
                ?: error("cpp dockerfile must select libicu with a `case \"\${CLANG_OS}\" in … esac` block")
        assertTrue(Regex("""bookworm\)\s*libicu_pkg=libicu72""").containsMatchIn(caseBlock), "bookworm arm → libicu72")
        assertTrue(Regex("""trixie\)\s*libicu_pkg=libicu76""").containsMatchIn(caseBlock), "trixie arm → libicu76")
        assertFalse(
            Regex("""install[^\n]*\blibicu7[26]\b""").containsMatchIn(dockerfile),
            "the apt install line must reference the derived package, not a literal libicu major",
        )
        // The gcc-stock pin (trixie +dhi gcc-14 eviction, QD-15107) must be guarded on CLANG_OS=trixie, else
        // it runs unconditionally over the bookworm base it was never written for.
        assertTrue(
            Regex("""(if|case)[^\n]*CLANG_OS[\s\S]{0,120}gcc-stock""").containsMatchIn(dockerfile) ||
                Regex("""CLANG_OS[^\n]*=\s*trixie[\s\S]{0,200}gcc-stock""").containsMatchIn(dockerfile),
            "the gcc-stock apt-pin must be conditional on CLANG_OS=trixie",
        )
    }
}
