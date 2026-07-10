package org.jetbrains.qodana.images.e2e

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loads every committed `e2e/fixtures/<image>/<case>/expected.json` through [ManifestLoader] and
 * checks structural invariants. The local floor behind the per-fixture "validate locally" gates:
 * an unknown field (jackson FAIL_ON_UNKNOWN_PROPERTIES is on by default) or a missing required
 * field fails HERE, without Docker. Not `@Tag("linter-e2e")`, so it runs under `./gradlew test`.
 * Vacuously green until the first fixture lands. cwd is the module root (pinned in build.gradle.kts).
 */
class FixtureManifestsTest {
    @TestFactory
    fun `every committed expected_json loads and is well-formed`(): Stream<DynamicTest> {
        val root = Path.of("e2e", "fixtures")
        if (!Files.isDirectory(root)) return Stream.empty()
        val manifests =
            Files.walk(root).use { walk ->
                walk.filter { it.isRegularFile() && it.name == "expected.json" }.toList()
            }
        return manifests
            .map { path ->
                DynamicTest.dynamicTest(root.relativize(path).toString()) {
                    val m = ManifestLoader.load(path) // throws on unknown/missing field
                    assertTrue(m.case.isNotBlank(), "case must be set in $path")
                    assertTrue(m.image.isNotBlank(), "image must be set in $path")
                    assertTrue(m.description.isNotBlank(), "description must be set in $path")
                    // Directory layout is the contract: fixtures/<image>/<case>/expected.json.
                    assertEquals(path.parent.name, m.case, "dir name must equal manifest.case for $path")
                    assertEquals(path.parent.parent.name, m.image, "image dir must equal manifest.image for $path")
                    m.sarif.expectations.forEach { e ->
                        assertTrue(
                            e.presence == "present" || e.presence == "absent",
                            "expectation.presence must be present|absent in $path",
                        )
                        assertTrue(e.reason.isNotBlank(), "expectation.reason is required in $path")
                    }
                    assertTrue(
                        m.sarif.expectations.any { it.presence == "present" },
                        "each fixture must assert at least one present finding: $path",
                    )
                }
            }.stream()
    }
}
