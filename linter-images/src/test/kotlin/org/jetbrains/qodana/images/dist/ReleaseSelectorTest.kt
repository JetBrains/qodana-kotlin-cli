package org.jetbrains.qodana.images.dist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseSelectorTest {
    private fun release(
        date: String,
        type: String,
        major: String,
        build: String,
    ) = Release(date = date, type = type, majorVersion = major, build = build, version = build)

    private val feed =
        ProductFeed(
            code = "QDJVM",
            releases =
                listOf(
                    release("2025-08-01", "release", "2025.3", "253.100"),
                    release("2025-09-15", "release", "2025.3", "253.200"), // the pinned build
                    release("2025-09-20", "eap", "2025.3", "253.300"),
                    release("2025-10-01", "release", "2025.2", "252.900"),
                ),
        )

    @Test
    fun `selects the exact pinned major and build`() {
        val selected = ReleaseSelector.select(feed, majorVersion = "2025.3", build = "253.200")
        assertEquals("253.200", selected.build)
        assertEquals("2025.3", selected.majorVersion)
    }

    @Test
    fun `does not float to a newer build with the same major`() {
        // Even though 253.300 is newer by date, only the exact pinned build is returned.
        val selected = ReleaseSelector.select(feed, majorVersion = "2025.3", build = "253.100")
        assertEquals("253.100", selected.build)
    }

    @Test
    fun `throws when the exact major+build pin is absent`() {
        val ex =
            assertFailsWith<NoSuchReleaseException> {
                ReleaseSelector.select(feed, majorVersion = "2025.3", build = "253.999")
            }
        assertEquals("QDJVM", ex.code)
        assertEquals("2025.3", ex.majorVersion)
        assertEquals("253.999", ex.build)
    }
}
