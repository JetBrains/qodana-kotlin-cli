package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the conditional apt-state repair in lib/base.dockerfile (QD-15040). The dhi.io/php:8.4-dev base
 * ships a pre-broken apt dependency state: its cross-`gcc-14` has an unsatisfiable `Depends: binutils
 * (>= 2.39)` (only the unversioned-Providing `binutils-latest` libs are present), which poisons apt's
 * solver so even unrelated installs (jq, locales) fail. `apt-get --fix-broken install` removes the broken
 * gcc-14 chain (the php image needs no C compiler) and repairs the state. The repair is gated on
 * `apt-get check` so the non-broken bases (debian-base, golang, node) skip it and stay byte-equivalent in
 * behavior. EnvContractTest can't see this (no `.env` key), so this reads the fragment directly.
 */
class BaseAptRepairTest {
    private val base: String = Path.of("docker/lib/base.dockerfile").readText()

    @Test
    fun `base conditionally repairs a broken apt state before installing OS glue`() {
        val checkIdx = base.indexOf("apt-get check")
        assertTrue(checkIdx >= 0, "base must probe the apt state with `apt-get check`")
        assertTrue(
            Regex("""apt-get (--fix-broken|-f) install""").containsMatchIn(base),
            "base must repair a broken apt state with `apt-get --fix-broken install`",
        )
        val installIdx = base.indexOf("apt-get install -y --no-install-recommends")
        assertTrue(installIdx >= 0, "base must install the OS glue")
        assertTrue(
            checkIdx < installIdx,
            "the repair must run BEFORE the OS-glue install (a poisoned solver fails the install)",
        )
    }

    @Test
    fun `the repair is conditional so non-broken bases stay byte-equivalent`() {
        assertTrue(
            Regex("""if\s+!\s*apt-get check""").containsMatchIn(base),
            "the `apt-get --fix-broken install` must be gated on `if ! apt-get check` so healthy bases " +
                "(debian-base/golang/node) skip it and keep the existing images byte-equivalent",
        )
    }
}
