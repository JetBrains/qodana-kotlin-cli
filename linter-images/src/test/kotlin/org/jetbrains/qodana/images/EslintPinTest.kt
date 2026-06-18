package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the pinned-eslint deliverable (QD-15038, reusable by go/php/ruby): the eslint version lives in
 * a renovate-trackable package.json (NOT the .env), pinned exact. Renovate's built-in npm manager
 * (enabled by config:recommended) auto-discovers any package.json; this test asserts the pin is exact
 * and that renovate.json5 does not exclude lib/ from npm discovery. An empirical "renovate extracts
 * eslint" check needs a credentialed CI run and is out of this offline scope.
 */
class EslintPinTest {
    private val pkg: Path = Path.of("docker/lib/toolchain/eslint/package.json")
    private val renovate: Path = Path.of("../.github/renovate.json5")

    @Test
    fun `eslint is pinned to an exact version in the renovate-tracked package json`() {
        val node = ObjectMapper().readTree(pkg.readText())
        val eslint = node.path("dependencies").path("eslint").asText("")
        assertTrue(
            eslint.matches(Regex("""\d+\.\d+\.\d+""")),
            "eslint must be pinned exact (X.Y.Z), was: '$eslint'",
        )
    }

    @Test
    fun `renovate does not disable the npm manager or exclude the eslint package json`() {
        // Read as text (json5 with comments — not strict JSON). config:recommended enables the built-in
        // npm manager and auto-discovers package.json; assert nothing turns it off or ignores lib/.
        val cfg = renovate.readText()
        if ("\"enabledManagers\"" in cfg) {
            assertTrue("\"npm\"" in cfg, "if enabledManagers is set, it must include npm so the eslint pin is tracked")
        }
        // No ignorePaths entry may swallow the eslint package.json path.
        val ignoresEslint =
            Regex(""""ignorePaths"\s*:\s*\[[^]]*toolchain/eslint[^]]*]""").containsMatchIn(cfg)
        assertFalse(ignoresEslint, "renovate.json5 must not ignore the eslint toolchain package.json")
    }
}
