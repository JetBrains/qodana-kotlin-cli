package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the shared JBR font-manager fix in `lib/fonts.dockerfile` (rationale in that file's header),
 * INCLUDEd by each jvm/android family image. Separate from EnvContractTest because there is no `.env`
 * key, so this reads the Dockerfile sources directly.
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
                "lib/fonts.dockerfile must `apt-get install $pkg` (the JBR font manager needs libfreetype)",
            )
        }
    }

    @Test
    fun `fonts include declares bare QODANA_UID and QODANA_GID with no shadowing default`() {
        // A `=1000` default on these ARGs would shadow the INCLUDE_ARGS override, so the image would run
        // as that uid regardless — and the final `USER ${QODANA_UID}` line would still look correct.
        for (arg in listOf("QODANA_UID", "QODANA_GID")) {
            assertTrue(
                Regex("""(?m)^ARG\s+$arg\s*$""").containsMatchIn(fonts),
                "lib/fonts.dockerfile must declare bare `ARG $arg`",
            )
            assertTrue(
                !Regex("""(?m)^ARG\s+$arg\s*=""").containsMatchIn(fonts),
                "`ARG $arg` must carry no default (a default shadows the INCLUDE_ARGS uid/gid override)",
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
            "$image must INCLUDE lib/fonts.dockerfile so the JBR font libs ship",
        )
    }
}
