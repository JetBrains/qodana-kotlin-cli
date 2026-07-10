package org.jetbrains.qodana.cli

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Asserts JVM vs native SARIF parity via [SarifCompare]. Inputs come from the
 * CI `e2e` job / CLI workflow (which runs scan twice and passes both reports via
 * `-Dtest.sarif.{jvm,native}` + `-Dtest.project.dir`). Tagged `native-binary`
 * so default `test` skips it; only CI has the artifacts.
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
