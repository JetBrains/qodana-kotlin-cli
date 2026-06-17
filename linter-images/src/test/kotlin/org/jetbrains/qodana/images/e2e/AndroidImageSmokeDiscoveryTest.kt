package org.jetbrains.qodana.images.e2e

import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LOCAL, no-Docker contract test for [AndroidImageSmokeTest]'s `@TestFactory` routing.
 *
 * The smoke check itself drives an amd64-only container and CANNOT run on the arm64 dev box, so its
 * filesystem assertions live in CI. This test pins the host-independent routing we CAN check locally:
 * the factory fires for EITHER android-family image (`qodana-android`, `qodana-android-community`) and
 * yields an empty stream otherwise (unset, or an unrelated image) — preserving the bare
 * `./gradlew :linter-images:test` empty-stream contract. The node runs against the SELECTED image, so
 * its display name carries that tag, not a hardcoded `qodana-android`. NOT tagged `linter-e2e`, so it
 * runs under the default `test` task.
 */
class AndroidImageSmokeDiscoveryTest {
    @Test
    fun `discovery is empty when linter_e2e_image is unset`() {
        withImage(null) {
            assertTrue(
                discover().isEmpty(),
                "no -Dlinter.e2e.image set => discovery must be empty",
            )
        }
    }

    @Test
    fun `discovery is empty for an unrelated image`() {
        withImage("qodana-jvm") {
            assertTrue(
                discover().isEmpty(),
                "-Dlinter.e2e.image=qodana-jvm is not android-family => discovery must be empty",
            )
        }
    }

    @Test
    fun `discovery fires for qodana-android`() {
        withImage("qodana-android") {
            val names = discover().map { it.displayName }
            assertEquals(1, names.size, "exactly one smoke node expected for qodana-android, got $names")
            assertTrue(
                names.single().contains("qodana-android"),
                "smoke node must name the image under test, got ${names.single()}",
            )
        }
    }

    @Test
    fun `discovery fires for qodana-android-community and names the community image`() {
        withImage("qodana-android-community") {
            val names = discover().map { it.displayName }
            assertEquals(1, names.size, "exactly one smoke node expected for qodana-android-community, got $names")
            assertTrue(
                names.single().contains("qodana-android-community"),
                "smoke node must name the COMMUNITY image under test, not a hardcoded qodana-android, " +
                    "got ${names.single()}",
            )
        }
    }

    private fun discover(): List<DynamicNode> = AndroidImageSmokeTest().androidImageSmoke().toList()

    private fun withImage(
        image: String?,
        block: () -> Unit,
    ) {
        val previous = System.getProperty("linter.e2e.image")
        setImage(image)
        try {
            block()
        } finally {
            setImage(previous)
        }
    }

    private fun setImage(image: String?) {
        if (image == null) {
            System.clearProperty("linter.e2e.image")
        } else {
            System.setProperty("linter.e2e.image", image)
        }
    }
}
