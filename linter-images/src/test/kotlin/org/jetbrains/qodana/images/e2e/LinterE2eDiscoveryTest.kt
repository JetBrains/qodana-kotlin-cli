package org.jetbrains.qodana.images.e2e

import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * LOCAL, no-Docker contract test for [LinterE2eTest]'s discovery factory.
 *
 * The full `@Tag("linter-e2e")` suite drives amd64-only containers and CANNOT run on the arm64
 * dev box, so its behavioral assertions live in CI. This test pins the one host-independent
 * invariant we CAN check locally: with no `linter.e2e.image` system property, discovery yields
 * an empty stream (a contributor's bare `./gradlew :linter-images:test` must not error out trying
 * to glob a `fixtures/null/` tree). It is intentionally NOT tagged `linter-e2e` so it runs under
 * the default `test` task.
 */
class LinterE2eDiscoveryTest {
    @Test
    fun `discovery returns empty when linter_e2e_image is unset`() {
        val previous = System.getProperty("linter.e2e.image")
        System.clearProperty("linter.e2e.image")
        try {
            val nodes: List<DynamicNode> = LinterE2eTest().discover().toList()
            assertTrue(
                nodes.isEmpty(),
                "no -Dlinter.e2e.image set => discovery must be empty, got ${nodes.map { it.displayName }}",
            )
        } finally {
            if (previous != null) System.setProperty("linter.e2e.image", previous)
        }
    }
}
