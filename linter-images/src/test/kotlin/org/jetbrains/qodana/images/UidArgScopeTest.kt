package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the QODANA_UID/QODANA_GID ARG-scoping contract (QD-15038). A language base whose own user
 * occupies uid 1000 (the DHI node base) overrides the qodana uid via INCLUDE_ARGS. Per the documented
 * cli.dockerfile:62-67 trap, a stage-local `ARG QODANA_UID=1000` would SHADOW that override; the
 * `=1000` default must appear ONLY in base.dockerfile's global pre-FROM block, and every consuming
 * stage must re-declare the ARG BARE. EnvContractTest cannot see this (it reads `.env`, not the
 * dockerfile resolution), so this test reads the lib fragments directly.
 */
class UidArgScopeTest {
    private val lib: Path = Path.of("docker/lib")

    private fun argLines(
        file: String,
        name: String,
    ): List<String> =
        lib
            .resolve(file)
            .readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it == "ARG $name" || it.startsWith("ARG $name=") || it.startsWith("ARG $name ") }
            .toList()

    @Test
    fun `base declares the only defaulted QODANA_UID and QODANA_GID globally, plus a bare re-declare`() {
        val base = lib.resolve("base.dockerfile").readText()
        assertEquals(1, Regex("""(?m)^ARG QODANA_UID=1000$""").findAll(base).count(), "one global QODANA_UID=1000")
        assertEquals(1, Regex("""(?m)^ARG QODANA_GID=1000$""").findAll(base).count(), "one global QODANA_GID=1000")
        val firstFrom = base.indexOf("\nFROM ")
        assertTrue(firstFrom > 0, "base must have a FROM")
        val preFrom = base.substring(0, firstFrom)
        assertTrue("ARG QODANA_UID=1000" in preFrom, "the QODANA_UID=1000 default must be pre-FROM (global)")
        assertTrue("ARG QODANA_GID=1000" in preFrom, "the QODANA_GID=1000 default must be pre-FROM (global)")
        val postFrom = base.substring(firstFrom)
        assertTrue(
            Regex("""(?m)^ARG QODANA_UID$""").containsMatchIn(postFrom),
            "base stage re-declares QODANA_UID bare",
        )
        assertTrue(
            Regex("""(?m)^ARG QODANA_GID$""").containsMatchIn(postFrom),
            "base stage re-declares QODANA_GID bare",
        )
    }

    @Test
    fun `consuming stages re-declare QODANA_UID and QODANA_GID BARE (a default would shadow the override)`() {
        for (file in listOf("dist.dockerfile", "cli.dockerfile", "runtime.dockerfile")) {
            for (name in listOf("QODANA_UID", "QODANA_GID")) {
                val lines = argLines(file, name)
                assertTrue(lines.isNotEmpty(), "$file must re-declare $name (it interpolates it)")
                lines.forEach { line ->
                    assertEquals("ARG $name", line, "$file must re-declare $name BARE, not with a default: '$line'")
                }
            }
        }
    }

    @Test
    fun `runtime USER interpolates the parameterized uid_gid`() {
        val runtime = lib.resolve("runtime.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^USER \$\{QODANA_UID}:\$\{QODANA_GID}$""").containsMatchIn(runtime),
            "runtime USER must be the parameterized \${QODANA_UID}:\${QODANA_GID}, not a hardcoded 1000:1000",
        )
    }

    @Test
    fun `the functional uid lines interpolate the ARG and never hardcode 1000`() {
        // The declaration-scoping tests above do not catch a regression that reverts the FUNCTIONAL lines
        // (groupadd/useradd/chown/USER) to literal 1000 while leaving the ARG declarations intact — that
        // would pass the other tests yet break the js build (`groupadd: GID '1000' already exists`). Guard
        // the functional lines directly: they must use ${QODANA_UID}/${QODANA_GID}, never a bare 1000.
        val base = lib.resolve("base.dockerfile").readText()
        assertTrue(
            Regex("""groupadd --gid "\$\{QODANA_GID}"""").containsMatchIn(base),
            "base groupadd must use \${QODANA_GID}",
        )
        assertTrue(
            Regex("""useradd --uid "\$\{QODANA_UID}" --gid "\$\{QODANA_GID}"""").containsMatchIn(base),
            "base useradd must use \${QODANA_UID}/\${QODANA_GID}",
        )
        assertTrue(
            Regex("""chown -R "\$\{QODANA_UID}:\$\{QODANA_GID}"""").containsMatchIn(base),
            "base chown must use \${QODANA_UID}:\${QODANA_GID}",
        )
        for (file in listOf("dist.dockerfile", "cli.dockerfile")) {
            val text = lib.resolve(file).readText()
            assertTrue(
                Regex("""--chown=\$\{QODANA_UID}:\$\{QODANA_GID}""").containsMatchIn(text),
                "$file COPY --chown must use \${QODANA_UID}:\${QODANA_GID}",
            )
        }
        // No functional line in the js lineage may carry a hardcoded user/group 1000. Check the four
        // js-lineage fragments (base/dist/cli/runtime) for the tell-tale literals (chown/USER/uid/gid).
        for (file in listOf("base.dockerfile", "dist.dockerfile", "cli.dockerfile", "runtime.dockerfile")) {
            val text = lib.resolve(file).readText()
            assertFalse(
                Regex("""(--chown=|USER |--uid |--gid |gid )1000\b""").containsMatchIn(text),
                "$file (js lineage) must not hardcode 1000 in a functional line; use \${QODANA_UID}/\${QODANA_GID}",
            )
        }
    }
}
