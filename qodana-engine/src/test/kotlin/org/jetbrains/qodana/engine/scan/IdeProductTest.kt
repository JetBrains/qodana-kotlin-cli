package org.jetbrains.qodana.engine.scan

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdeProductTest {
    @Test
    fun `linterFromInfoJson maps known product codes to linters`() {
        assertEquals("qodana-js", IdeProductDiscovery.linterFromInfoJson("""{"productCode":"WS"}""")?.name)
        assertEquals("qodana-go", IdeProductDiscovery.linterFromInfoJson("""{"productCode":"GO"}""")?.name)
        // Shared IntelliJ-Ultimate platform (jvm/android dists) reports IU → the paid JVM linter.
        assertEquals("qodana-jvm", IdeProductDiscovery.linterFromInfoJson("""{"productCode":"IU"}""")?.name)
        // Extra fields are tolerated (FAIL_ON_UNKNOWN_PROPERTIES is off).
        assertEquals(
            "qodana-php",
            IdeProductDiscovery.linterFromInfoJson("""{"productCode":"PS","version":"2026.1"}""")?.name,
        )
    }

    @Test
    fun `linterFromInfoJson returns null for an unknown code or malformed json`() {
        assertNull(IdeProductDiscovery.linterFromInfoJson("""{"productCode":"ZZ"}"""))
        assertNull(IdeProductDiscovery.linterFromInfoJson("not json"))
    }
}
