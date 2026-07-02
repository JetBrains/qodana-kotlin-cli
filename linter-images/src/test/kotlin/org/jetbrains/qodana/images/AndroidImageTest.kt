package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the JBR font-manager native libs the qodana-android family (Android Studio) needs at scan time
 * (QD-15265). Android Studio shares IDEA's JBR and renders the Gradle sync build view during
 * project-model configuration; absent libfreetype.so.6 → NoClassDefFoundError(sun.awt.X11FontManager) →
 * intermittent "Project opening" hang → exit 124. Same fix as the JVM family — see [JvmImageTest] — and
 * as qodana-cpp (QD-15107) / qodana-rust (QD-15111). Mirrors [RustImageTest].
 */
class AndroidImageTest {
    private fun dockerfile(image: String): String = Path.of("docker/images/$image.dockerfile").readText()

    @ParameterizedTest
    @ValueSource(strings = ["qodana-android", "qodana-android-community"])
    fun `installs the JBR font-manager native libs`(image: String) {
        for (pkg in listOf("fontconfig", "libfreetype6")) {
            assertTrue(
                dockerfile(image).contains(pkg),
                "$image must apt-install $pkg: the JBR font manager dlopens libfreetype.so.6 when Android " +
                    "Studio renders the Gradle sync build view; absent → UnsatisfiedLinkError → intermittent " +
                    "'Project opening' hang (QD-15265)",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["qodana-android", "qodana-android-community"])
    fun `final USER is the unprivileged qodana uid, not root`(image: String) {
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
