package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the JBR font-manager native libs the qodana-jvm family (IntelliJ IDEA) needs at scan time
 * (QD-15265). The bundled JBR's font manager dlopens libfreetype.so.6 the moment IDEA renders the
 * Maven/Gradle sync build view during project-model configuration; absent → NoClassDefFoundError(
 * sun.awt.X11FontManager) → UnsatisfiedLinkError in the import coroutine, which intermittently leaves
 * "Project opening" awaiting forever → the e2e hang-timeout kills the scan (exit 124). Same defect and
 * fix already shipped for qodana-cpp (QD-15107) and qodana-rust (QD-15111); it was never applied to the
 * JVM family. IDEA is JBR-only (no .NET backend), so — like qodana-rust — only the fonts are needed.
 * Mirrors [RustImageTest]. EnvContractTest cannot see this (no `.env` key), so this reads the dockerfile.
 */
class JvmImageTest {
    private fun dockerfile(image: String): String = Path.of("docker/images/$image.dockerfile").readText()

    @ParameterizedTest
    @ValueSource(strings = ["qodana-jvm", "qodana-jvm-community"])
    fun `installs the JBR font-manager native libs`(image: String) {
        for (pkg in listOf("fontconfig", "libfreetype6")) {
            assertTrue(
                dockerfile(image).contains(pkg),
                "$image must apt-install $pkg: the JBR font manager dlopens libfreetype.so.6 when IDEA " +
                    "renders the Maven/Gradle sync build view; absent → UnsatisfiedLinkError → intermittent " +
                    "'Project opening' hang (QD-15265)",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["qodana-jvm", "qodana-jvm-community"])
    fun `final USER is the unprivileged qodana uid, not root`(image: String) {
        // The font-lib install needs root (apt), but the shipped image must scan as the unprivileged
        // qodana user — so the LAST USER directive must restore the uid.
        val lastUser =
            Regex("""(?m)^USER\s+(\S+)""")
                .findAll(dockerfile(image))
                .lastOrNull()
                ?.groupValues
                ?.get(1)
        assertTrue(
            lastUser != null && lastUser != "0" && !lastUser.startsWith("root"),
            "$image's final USER must be the qodana uid (not root) so scans run unprivileged; was '$lastUser'",
        )
    }
}
