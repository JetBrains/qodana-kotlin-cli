package org.jetbrains.qodana.cli

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Asserts structural equivalence of the SARIF output produced by the JVM
 * (`./gradlew :qodana-cli:run`) and native (`./qodana-cli`) entry points
 * for the same fixture project.
 *
 * The CI `native-e2e` job (see .github/workflows/ci.yaml) runs scan twice —
 * once via the JVM, once via the native binary — against
 * `qodana-cli/src/test/resources/scan-smoke-fixture`, then invokes this test
 * with `-Dtest.sarif.jvm`, `-Dtest.sarif.native`, and `-Dtest.project.dir`
 * pointing at the two output reports and the project root.
 *
 * Use [SarifCompare] for the canonicalisation. The structural compare drops
 * volatile fields (timestamps, semanticVersion, scan locations) and yields a
 * sorted multiset of `(ruleId, normalised-uri, startLine)` tuples; a mismatch
 * indicates that the native binary's reflection metadata is incomplete or
 * that an inspection rule fires differently between the two runtimes.
 *
 * Tagged `native-binary` so the default `test` task skips it — it requires
 * inputs only the CI native-e2e job has. Run via:
 *   `./gradlew :qodana-cli:test --tests SarifCompareIntegrationTest
 *       -Dtest.sarif.jvm=... -Dtest.sarif.native=... -Dtest.project.dir=...`
 */
@Tag("native-binary")
class SarifCompareIntegrationTest {
    @Test
    fun `JVM and native SARIF outputs are structurally equivalent`() {
        val jvm =
            Path.of(
                System.getProperty("test.sarif.jvm")
                    ?: error("set -Dtest.sarif.jvm=path/to/jvm/qodana.sarif.json"),
            )
        val native =
            Path.of(
                System.getProperty("test.sarif.native")
                    ?: error("set -Dtest.sarif.native=path/to/native/qodana.sarif.json"),
            )
        val project =
            Path.of(
                System.getProperty("test.project.dir")
                    ?: error("set -Dtest.project.dir=path/to/project"),
            )
        SarifCompare.assertEquivalent(jvm = jvm, native = native, projectRoot = project)
    }
}
