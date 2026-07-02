package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the shared JBR font-manager fix for the qodana-jvm/android family (QD-15265). IDEA and Android
 * Studio's bundled JBR dlopens libfreetype.so.6 while rendering the Maven/Gradle sync build view during
 * project-model configuration; absent, the font init throws and headless project-open can hang instead of
 * failing. The fix lives in `lib/fonts.dockerfile` (cf. the inline blocks in qodana-cpp/qodana-rust) and is
 * INCLUDEd by each family image. EnvContractTest cannot see this (no `.env` key), so this reads the sources.
 */
class JvmFamilyFontLibTest {
    private val fonts: String = Path.of("docker/lib/fonts.dockerfile").readText()

    @Test
    fun `fonts include installs the font libs in the apt-get install directive`() {
        // Match the package inside the actual `apt-get install` directive, not anywhere in the file, so
        // deleting the install line cannot pass on a comment that merely names the package.
        for (pkg in listOf("fontconfig", "libfreetype6")) {
            assertTrue(
                Regex("""apt-get\s+install\b[^\n]*\b${Regex.escape(pkg)}\b""").containsMatchIn(fonts),
                "lib/fonts.dockerfile must `apt-get install $pkg`: the JBR font manager dlopens " +
                    "libfreetype.so.6 when the sync build view renders; absent → headless project-open hang",
            )
        }
    }

    @Test
    fun `fonts include runs apt as root then restores the unprivileged qodana uid`() {
        val rootBeforeInstall =
            Regex("""(?ms)^USER\s+0\s*$.*?apt-get\s+install""").containsMatchIn(fonts)
        assertTrue(
            rootBeforeInstall,
            "apt must run as root: `USER 0` must precede the apt-get install in lib/fonts.dockerfile",
        )
        val lastUser =
            Regex("""(?m)^USER\s+(\S+)""")
                .findAll(fonts)
                .lastOrNull()
                ?.groupValues
                ?.get(1)
        assertTrue(
            lastUser != null && lastUser != "0" && !lastUser.startsWith("root"),
            "lib/fonts.dockerfile must restore the unprivileged qodana uid; final USER was '$lastUser'",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["qodana-jvm", "qodana-jvm-community", "qodana-android", "qodana-android-community"])
    fun `family image wires in the fonts fix`(image: String) {
        val dockerfile = Path.of("docker/images/$image.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^INCLUDE\s+lib/fonts\.dockerfile\s*$""").containsMatchIn(dockerfile),
            "$image must INCLUDE lib/fonts.dockerfile so the JBR font libs ship (QD-15265)",
        )
    }
}
